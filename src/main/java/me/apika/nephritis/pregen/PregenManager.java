package me.apika.nephritis.pregen;

import me.apika.nephritis.Nephritis;
import me.apika.nephritis.monitor.NephritisProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.chunk.ChunkStatus;
import com.mojang.datafixers.util.Either;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class PregenManager {
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 50;
    private static final int DEFAULT_INFLIGHT = 50;
    private static final int SNAPSHOT_MAGIC = 0x46475031; // FGP1
    private static final int SNAPSHOT_VERSION = 1;
    private static final int SAVE_EVERY_COMPLETIONS = 32;
    private static final int MAX_RETRIES = 3;

    private static final Queue<Completion> COMPLETIONS = new ConcurrentLinkedQueue<>();
    private static final Queue<Request> ACTIVE = new ArrayDeque<>();
    private static final Set<Long> SCHEDULED = new HashSet<>();
    private static final Set<Long> COMPLETED_SNAPSHOT = new HashSet<>();
    private static final Set<Long> PERMANENT_FAILURES = new HashSet<>();
    private static final java.util.Map<Long, Integer> RETRIES = new java.util.HashMap<>();
    private static volatile ServerLevel level;
    private static volatile boolean running;
    private static volatile int targetRadius;
    private static volatile int inflightLimit = readInflight();
    private static int total;
    private static int completed;
    private static int skipped;
    private static int failed;
    private static int unsavedCompletions;

    private static final ArrayDeque<Long> NEXT_ORDER = new ArrayDeque<>();

    private PregenManager() {}

    public static synchronized boolean start(MinecraftServer server, int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) return false;
        if (running) return false;

        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int cx = spawn.getX() >> 4;
        int cz = spawn.getZ() >> 4;

        level = overworld;
        targetRadius = radius;
        total = (radius * 2 + 1) * (radius * 2 + 1);
        completed = 0;
        skipped = 0;
        failed = 0;
        unsavedCompletions = 0;
        SCHEDULED.clear();
        COMPLETIONS.clear();
        ACTIVE.clear();
        NEXT_ORDER.clear();
        COMPLETED_SNAPSHOT.clear();
        PERMANENT_FAILURES.clear();
        RETRIES.clear();
        PENDING_REQUEUE.clear();

        loadSnapshot(overworld, radius, cx, cz);
        completed = COMPLETED_SNAPSHOT.size();

        // Center-out ring order. This makes chunks around spawn available first.
        NEXT_ORDER.add(ChunkPos.asLong(cx, cz));
        for (int r = 1; r <= radius; r++) {
            int minX = cx - r;
            int maxX = cx + r;
            int minZ = cz - r;
            int maxZ = cz + r;
            for (int x = minX; x <= maxX; x++) NEXT_ORDER.add(ChunkPos.asLong(x, minZ));
            for (int z = minZ + 1; z <= maxZ; z++) NEXT_ORDER.add(ChunkPos.asLong(maxX, z));
            for (int x = maxX - 1; x >= minX; x--) NEXT_ORDER.add(ChunkPos.asLong(x, maxZ));
            for (int z = maxZ - 1; z > minZ; z--) NEXT_ORDER.add(ChunkPos.asLong(minX, z));
        }

        running = true;
        Nephritis.LOGGER.info("[pregen] started radius={} total={} resumed={} inflight={} spawnChunk={},{}",
            radius, total, completed, inflightLimit, cx, cz);
        return true;
    }

    public static synchronized void cancel() {
        if (!running) return;
        saveSnapshot(level);
        running = false;
        level = null;
        NEXT_ORDER.clear();
        ACTIVE.clear();
        SCHEDULED.clear();
        COMPLETIONS.clear();
        Nephritis.LOGGER.info("[pregen] cancelled completed={} skipped={} failed={} snapshotSaved=true",
            completed, skipped, failed);
    }

    public static boolean isRunning() {
        return running;
    }

    public static synchronized String status() {
        if (!running && total == 0) return "idle";
        int progress = Math.min(total, completed + skipped + PERMANENT_FAILURES.size());
        return "running=" + running + " radius=" + targetRadius
            + " progress=" + progress + "/" + total
            + " completed=" + completed + " skipped=" + skipped + " failed=" + failed
            + " unresolved=" + PERMANENT_FAILURES.size()
            + " active=" + ACTIVE.size() + " queued=" + NEXT_ORDER.size()
            + " inflight=" + inflightLimit + " snapshot=" + (level != null && snapshotPath(level) != null);
    }

    public static synchronized void setInflight(int value) {
        inflightLimit = Math.max(1, Math.min(200, value));
    }

    public static int getInflight() {
        return inflightLimit;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        final long profileStart = System.nanoTime();
        if (event.phase != TickEvent.Phase.END || !running) return;
        drainCompletions();
        drainRetryQueue();
        fillInflight();
        if (NEXT_ORDER.isEmpty() && ACTIVE.isEmpty()) {
            if (PERMANENT_FAILURES.isEmpty()) {
                saveSnapshot(level);
                deleteSnapshot(level);
                Nephritis.LOGGER.info("[pregen] finished radius={} completed={} skipped={} failed={} snapshotDeleted=true",
                    targetRadius, completed, skipped, failed);
            } else {
                saveSnapshot(level);
                Nephritis.LOGGER.warn("[pregen] stopped with {} unresolved chunk failures; snapshot retained for a later retry",
                    PERMANENT_FAILURES.size());
            }
            running = false;
            level = null;
            COMPLETED_SNAPSHOT.clear();
        }
        NephritisProfiler.record(NephritisProfiler.Bucket.PREGEN, profileStart);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (running) {
            saveSnapshot(level);
            running = false;
        }
        level = null;
        ACTIVE.clear();
        NEXT_ORDER.clear();
        COMPLETIONS.clear();
        SCHEDULED.clear();
        PENDING_REQUEUE.clear();
        RETRIES.clear();
        PERMANENT_FAILURES.clear();
    }

    private static synchronized void fillInflight() {
        ServerLevel current = level;
        if (!running || current == null) return;

        while (ACTIVE.size() < inflightLimit && !NEXT_ORDER.isEmpty()) {
            long key = NEXT_ORDER.removeFirst();
            if (COMPLETED_SNAPSHOT.contains(key)) {
                // Already counted in `completed` when the snapshot was loaded.
                continue;
            }
            if (!SCHEDULED.add(key)) {
                skipped++;
                continue;
            }

            ChunkPos pos = new ChunkPos(key);
            try {
                CompletableFuture<?> future = current.getChunkSource()
                    .getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true);
                ACTIVE.add(new Request(key, future));
                final ServerLevel capturedLevel = current;
                future.whenComplete((value, throwable) ->
                    COMPLETIONS.add(new Completion(key, capturedLevel, throwable, futureSucceeded(value, throwable))));
            } catch (Throwable t) {
                removeScheduledForRetry(key);
                failed++;
                Nephritis.LOGGER.warn("[pregen] request failed for {},{}", pos.x, pos.z, t);
                retryOrFail(key);
            }
        }

        if (unsavedCompletions >= SAVE_EVERY_COMPLETIONS) {
            saveSnapshot(current);
        }
    }

    private static synchronized void drainCompletions() {
        Completion completion;
        while ((completion = COMPLETIONS.poll()) != null) {
            removeActive(completion.key());
            if (completion.success()) {
                RETRIES.remove(completion.key());
                completed++;
                COMPLETED_SNAPSHOT.add(completion.key());
                unsavedCompletions++;
            } else {
                failed++;
                ChunkPos pos = new ChunkPos(completion.key());
                Nephritis.LOGGER.warn("[pregen] chunk generation/load failed for {},{}",
                    pos.x, pos.z, completion.throwable());
                SCHEDULED.remove(completion.key());
                retryOrFail(completion.key());
            }
        }
    }

    private static void retryOrFail(long key) {
        int attempt = RETRIES.getOrDefault(key, 0) + 1;
        RETRIES.put(key, attempt);
        if (attempt <= MAX_RETRIES) {
            PENDING_REQUEUE.add(key);
        } else {
            PERMANENT_FAILURES.add(key);
        }
    }

    private static final ArrayDeque<Long> PENDING_REQUEUE = new ArrayDeque<>();

    private static void removeScheduledForRetry(long key) {
        SCHEDULED.remove(key);
    }

    private static void drainRetryQueue() {
        while (!PENDING_REQUEUE.isEmpty()) {
            long key = PENDING_REQUEUE.removeFirst();
            if (!COMPLETED_SNAPSHOT.contains(key) && !PERMANENT_FAILURES.contains(key)) {
                NEXT_ORDER.addLast(key);
            }
        }
    }

    private static boolean futureSucceeded(Object value, Throwable throwable) {
        if (throwable != null) return false;
        if (value instanceof Either<?, ?> either) return either.left().isPresent();
        return value != null;
    }

    private static void removeActive(long key) {
        ACTIVE.removeIf(request -> request.key() == key);
    }

    private static void loadSnapshot(ServerLevel world, int radius, int spawnX, int spawnZ) {
        Path path = snapshotPath(world);
        if (path == null || !Files.isRegularFile(path)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = in.readInt();
            int version = in.readInt();
            int storedRadius = in.readInt();
            int storedSpawnX = in.readInt();
            int storedSpawnZ = in.readInt();
            int count = in.readInt();
            if (magic != SNAPSHOT_MAGIC || version != SNAPSHOT_VERSION
                || storedRadius != radius || storedSpawnX != spawnX || storedSpawnZ != spawnZ
                || count < 0 || count > 20000) {
                Nephritis.LOGGER.info("[pregen] ignoring incompatible snapshot {}", path.getFileName());
                return;
            }
            int accepted = 0;
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                ChunkPos pos = new ChunkPos(key);
                if (Math.abs(pos.x - spawnX) <= radius && Math.abs(pos.z - spawnZ) <= radius) {
                    if (COMPLETED_SNAPSHOT.add(key)) accepted++;
                }
            }
            Nephritis.LOGGER.info("[pregen] resumed {} completed chunks from {}", accepted, path.getFileName());
        } catch (IOException | RuntimeException e) {
            Nephritis.LOGGER.warn("[pregen] could not read snapshot {}; starting fresh", path, e);
            COMPLETED_SNAPSHOT.clear();
        }
    }

    private static void saveSnapshot(ServerLevel world) {
        if (world == null || !running) return;
        Path path = snapshotPath(world);
        if (path == null) return;
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        BlockPos spawn = world.getSharedSpawnPos();
        int spawnX = spawn.getX() >> 4;
        int spawnZ = spawn.getZ() >> 4;
        try {
            Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(SNAPSHOT_MAGIC);
                out.writeInt(SNAPSHOT_VERSION);
                out.writeInt(targetRadius);
                out.writeInt(spawnX);
                out.writeInt(spawnZ);
                out.writeInt(COMPLETED_SNAPSHOT.size());
                for (long key : COMPLETED_SNAPSHOT) out.writeLong(key);
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            unsavedCompletions = 0;
        } catch (IOException e) {
            Nephritis.LOGGER.warn("[pregen] failed to save snapshot {}", path, e);
        }
    }

    private static void deleteSnapshot(ServerLevel world) {
        Path path = snapshotPath(world);
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Nephritis.LOGGER.warn("[pregen] failed to delete finished snapshot {}", path, e);
        }
    }

    private static Path snapshotPath(ServerLevel world) {
        try {
            return world.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("nephritis_pregen.dat");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readInflight() {
        try {
            return Math.max(1, Math.min(200,
                Integer.parseInt(System.getProperty("nephritis.pregen.inflight", Integer.toString(DEFAULT_INFLIGHT)))));
        } catch (NumberFormatException ignored) {
            return DEFAULT_INFLIGHT;
        }
    }

    private record Request(long key, CompletableFuture<?> future) {}
    private record Completion(long key, ServerLevel level, Throwable throwable, boolean success) {}
}
