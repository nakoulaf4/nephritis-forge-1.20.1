package me.apika.nephritis.chunkforce;

import com.mojang.datafixers.util.Either;
import me.apika.nephritis.Nephritis;
import me.apika.nephritis.monitor.NephritisProfiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class PredictiveChunkForce {
    private static final int DEFAULT_INFLIGHT = 32;
    private static final int DEFAULT_LEAD = 8;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int HALF_WIDTH = 2;

    private static volatile boolean enabled = readInitialEnabled();
    private static volatile int inflightLimit = readInflight();
    private static volatile int lead = readLead();

    private static final Map<UUID, PreviousPos> PREVIOUS = new HashMap<>();
    private static final Map<String, ServerLevel> LEVELS = new HashMap<>();
    private static final Queue<ChunkKey> PENDING = new ArrayDeque<>();
    private static final Set<ChunkKey> QUEUED = new HashSet<>();
    private static final Set<ChunkKey> ACTIVE_KEYS = new HashSet<>();
    private static final Queue<Completion> COMPLETIONS = new ConcurrentLinkedQueue<>();
    private static int completed;
    private static int failed;

    private PredictiveChunkForce() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            synchronized (PredictiveChunkForce.class) {
                PENDING.clear();
                QUEUED.clear();
            }
        }
    }
    public static int getInflight() { return inflightLimit; }
    public static void setInflight(int value) { inflightLimit = Math.max(1, Math.min(128, value)); }
    public static int getLead() { return lead; }
    public static void setLead(int value) { lead = Math.max(2, Math.min(12, value)); }

    public static String status() {
        synchronized (PredictiveChunkForce.class) {
            return (enabled ? "ON" : "OFF") + " pending=" + PENDING.size()
                + " active=" + ACTIVE_KEYS.size() + " completed=" + completed
                + " failed=" + failed + " inflight=" + inflightLimit + " lead=" + lead;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        final long profileStart = System.nanoTime();
        if (!enabled || event.phase != TickEvent.Phase.END) return;
        drainCompletions();
        if (event.getServer().getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanPlayers(event.getServer());
        }
        fillInflight();
        NephritisProfiler.record(NephritisProfiler.Bucket.CHUNKFORCE, profileStart);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (PredictiveChunkForce.class) {
            PREVIOUS.clear();
            LEVELS.clear();
            PENDING.clear();
            QUEUED.clear();
            ACTIVE_KEYS.clear();
            COMPLETIONS.clear();
        }
    }

    private static synchronized void scanPlayers(MinecraftServer server) {
        if (!enabled) return;
        LEVELS.clear();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            LEVELS.put(dimension, level);
            for (ServerPlayer player : level.players()) {
                UUID id = player.getUUID();
                double x = player.getX();
                double z = player.getZ();
                PreviousPos previous = PREVIOUS.put(id, new PreviousPos(x, z, dimension));
                double dx = previous != null && dimension.equals(previous.dimension()) ? x - previous.x() : 0.0;
                double dz = previous != null && dimension.equals(previous.dimension()) ? z - previous.z() : 0.0;

                int cx = player.chunkPosition().x;
                int cz = player.chunkPosition().z;
                double speedSq = dx * dx + dz * dz;
                int centerX = cx;
                int centerZ = cz;
                if (speedSq > 0.04) {
                    double len = Math.sqrt(speedSq);
                    centerX += (int) Math.round((dx / len) * lead);
                    centerZ += (int) Math.round((dz / len) * lead);
                }

                for (int ox = -HALF_WIDTH; ox <= HALF_WIDTH; ox++) {
                    for (int oz = -HALF_WIDTH; oz <= HALF_WIDTH; oz++) {
                        enqueue(dimension, centerX + ox, centerZ + oz);
                    }
                }
            }
        }
    }

    private static void enqueue(String dimension, int x, int z) {
        ChunkKey key = new ChunkKey(dimension, x, z);
        if (!QUEUED.add(key) || ACTIVE_KEYS.contains(key)) return;
        PENDING.add(key);
    }

    private static synchronized void fillInflight() {
        while (ACTIVE_KEYS.size() < inflightLimit && !PENDING.isEmpty()) {
            ChunkKey key = PENDING.remove();
            QUEUED.remove(key);
            if (!ACTIVE_KEYS.add(key)) continue;

            ServerLevel level = LEVELS.get(key.dimension());
            if (level == null) {
                ACTIVE_KEYS.remove(key);
                failed++;
                continue;
            }
            try {
                CompletableFuture<?> future = level.getChunkSource()
                    .getChunkFuture(key.x(), key.z(), ChunkStatus.FULL, true);
                future.whenComplete((value, throwable) ->
                    COMPLETIONS.add(new Completion(key, throwable, futureSucceeded(value, throwable))));
            } catch (Throwable t) {
                ACTIVE_KEYS.remove(key);
                failed++;
                Nephritis.LOGGER.debug("[chunkforce] request failed for {} {} {}", key.dimension(), key.x(), key.z(), t);
            }
        }
    }

    private static synchronized void drainCompletions() {
        Completion completion;
        while ((completion = COMPLETIONS.poll()) != null) {
            ACTIVE_KEYS.remove(completion.key());
            if (completion.success()) completed++;
            else failed++;
        }
    }

    private static int readInflight() {
        try { return Math.max(1, Math.min(128, Integer.parseInt(System.getProperty("nephritis.chunkforce.inflight", Integer.toString(DEFAULT_INFLIGHT))))); }
        catch (NumberFormatException ignored) { return DEFAULT_INFLIGHT; }
    }

    private static int readLead() {
        try { return Math.max(2, Math.min(12, Integer.parseInt(System.getProperty("nephritis.chunkforce.lead", Integer.toString(DEFAULT_LEAD))))); }
        catch (NumberFormatException ignored) { return DEFAULT_LEAD; }
    }

    private static boolean readInitialEnabled() {
        return Boolean.parseBoolean(System.getProperty("nephritis.chunkforce", "false"));
    }

    private record PreviousPos(double x, double z, String dimension) {}
    private record ChunkKey(String dimension, int x, int z) {}
    private static boolean futureSucceeded(Object value, Throwable throwable) {
        if (throwable != null) return false;
        if (value instanceof Either<?, ?> either) return either.left().isPresent();
        return value != null;
    }

    private record Completion(ChunkKey key, Throwable throwable, boolean success) {}
}
