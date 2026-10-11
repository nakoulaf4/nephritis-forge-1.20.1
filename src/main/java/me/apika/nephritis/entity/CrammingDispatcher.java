package me.apika.nephritis.entity;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.apika.nephritis.Nephritis;
import me.apika.nephritis.nativebridge.NalimBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;


public final class CrammingDispatcher {
    private static volatile boolean enabled = true;
    private static volatile boolean preferNative = true;

    private static final int MAX_ENTITIES = 2048;
    private static final int BRUTE_FORCE_THRESHOLD = 16;
    private static final double MIN_CONTACT = 0.01D;
    private static final double PUSH_SCALE = 0.05D;
    // Vanilla's LivingEntity#pushEntities runs for both participants of every
    // pair, and LivingEntity#doPush applies the push in both directions, so
    // each entity receives the per-pair push twice per tick. This batch runs
    // each pair exactly once, so the push must be doubled to keep vanilla
    // separation strength.
    private static final double VANILLA_PAIR_FACTOR = 2.0D;
    private static final double MIN_CELL_SIZE = 2.0D;
    private static final int IN_STRIDE = 8;
    private static final int OUT_STRIDE = 4;

    private static final List<Mob> MOBS = new ArrayList<>(MAX_ENTITIES);
    private static final Long2ObjectOpenHashMap<IntArrayList> CELLS = new Long2ObjectOpenHashMap<>();
    private static final ArrayList<Input> INPUTS = new ArrayList<>(MAX_ENTITIES);
    private static final double[] ACCUM_DX = new double[MAX_ENTITIES];
    private static final double[] ACCUM_DZ = new double[MAX_ENTITIES];
    private static final int[] CROWDED = new int[MAX_ENTITIES];
    private static final double[] NALIM_REQUEST = new double[MAX_ENTITIES * IN_STRIDE];
    private static final double[] NALIM_RESULT = new double[MAX_ENTITIES * OUT_STRIDE];

    private static final IdentityHashMap<ServerLevel, Long> LAST_TICKS = new IdentityHashMap<>();
    private static final IdentityHashMap<ServerLevel, Long> FALLBACK_TICKS = new IdentityHashMap<>();

    private static long diagBatches;
    private static long diagMobs;
    private static long diagPushed;
    private static long diagDamaged;
    private static long diagNative;
    private static long diagJava;
    private static long diagLastLogNs = System.nanoTime();
    private static long diagLastMaxWarnNs = System.nanoTime();

    private CrammingDispatcher() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        Nephritis.LOGGER.info("Nephritis cramming {}", value ? "enabled" : "disabled");
    }

    public static boolean isNativePreferred() { return preferNative; }

    public static void setNativePreferred(boolean value) {
        preferNative = value;
        Nephritis.LOGGER.info("Nephritis cramming native backend preference: {}", value ? "NALIM" : "JAVA");
    }

    public static String backendStatus() {
        return preferNative && NalimBridge.isAvailable() ? "NALIM/RUST" : "JAVA";
    }


    public static boolean handle(Mob caller) {
        if (!enabled || caller.level().isClientSide || !(caller.level() instanceof ServerLevel world)) {
            return false;
        }

        final long tick = world.getGameTime();
        if (tick == FALLBACK_TICKS.getOrDefault(world, Long.MIN_VALUE)) {
            // The first call decided that this tick must use vanilla semantics.
            return false;
        }
        if (tick == LAST_TICKS.getOrDefault(world, Long.MIN_VALUE)) {
            return true;
        }
        LAST_TICKS.put(world, tick);

        if (!runBatch(world)) {
            FALLBACK_TICKS.put(world, tick);
            return false;
        }
        logDiagnosticsIfDue();
        return true;
    }

    private static boolean runBatch(ServerLevel world) {
        final long profileStart = System.nanoTime();
        try {
            MOBS.clear();
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof Mob mob && entity.isAlive() && !entity.isRemoved()) {
                MOBS.add(mob);
            }
        }
        final int count = MOBS.size();
        if (count == 0) return true;
        if (count > MAX_ENTITIES) {
            // Rate-limit the warning: on overcrowded servers this branch would
            // otherwise fire every tick and flood the log.
            long now = System.nanoTime();
            if (now - diagLastMaxWarnNs >= 30_000_000_000L) {
                diagLastMaxWarnNs = now;
                Nephritis.LOGGER.warn("[cramming-dispatch] {} mobs exceeds MAX_ENTITIES={}, falling back to vanilla",
                    count, MAX_ENTITIES);
            }
            return false;
        }

        INPUTS.clear();
        for (int i = 0; i < count; i++) {
            Mob mob = MOBS.get(i);
            AABB box = mob.getBoundingBox();
            final int rootId = mob.isPassenger() && mob.getRootVehicle() != null
                ? mob.getRootVehicle().getId() : mob.getId();
            INPUTS.add(new Input(
                mob.getId(),
                mob.isPushable(),
                mob.isVehicle(),
                mob.isPassenger(),
                mob.noPhysics,
                mob.getX(), mob.getZ(),
                (box.maxX - box.minX) * 0.5D,
                box.minY, box.maxY,
                rootId
            ));
        }

        java.util.Arrays.fill(ACCUM_DX, 0, count, 0.0D);
        java.util.Arrays.fill(ACCUM_DZ, 0, count, 0.0D);
        java.util.Arrays.fill(CROWDED, 0, count, 0);

        boolean nativeRan = false;
        if (preferNative && NalimBridge.isAvailable()) {
            try {
                buildNalimInput(count);
                NalimBridge.compute(NALIM_REQUEST, NALIM_RESULT, count);
                validateNativeResults(count);
                readNalimResults(count);
                diagNative++;
                nativeRan = true;
            } catch (Throwable t) {
                NalimBridge.disable("native cramming call failed; disabling it for this process", t);
            }
        }

        if (!nativeRan) {
            if (count < BRUTE_FORCE_THRESHOLD) {
                bruteForce(count);
            } else {
                final double cellSize = computeCellSize(count);
                buildCells(count, cellSize);
                for (int i = 0; i < count; i++) {
                    Input a = INPUTS.get(i);
                    if (a.noPhysics) continue;
                    int cx = cellX(a.x, cellSize);
                    int cz = cellX(a.z, cellSize);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            IntArrayList bucket = CELLS.get(cellKey(cx + dx, cz + dz));
                            if (bucket == null) continue;
                            for (int k = 0; k < bucket.size(); k++) {
                                int j = bucket.getInt(k);
                                if (j <= i) continue;
                                processPair(i, j);
                            }
                        }
                    }
                }
            }
            diagJava++;
        }

        final int maxCramming = world.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        int pushed = 0;
        int damaged = 0;
        for (int i = 0; i < count; i++) {
            Mob mob = MOBS.get(i);
            double dx = ACCUM_DX[i];
            double dz = ACCUM_DZ[i];
            if (dx != 0.0D || dz != 0.0D) {
                mob.push(dx, 0.0D, dz);
                pushed++;
            }
            if (maxCramming > 0 && CROWDED[i] > maxCramming - 1 && mob.getRandom().nextInt(4) == 0) {
                mob.hurt(world.damageSources().cramming(), 6.0F);
                damaged++;
            }
        }

        diagBatches++;
        diagMobs += count;
        diagPushed += pushed;
            diagDamaged += damaged;
            return true;
        } finally {
            me.apika.nephritis.monitor.NephritisProfiler.record(
                me.apika.nephritis.monitor.NephritisProfiler.Bucket.CRAMMING, profileStart);
        }
    }

    private static void buildNalimInput(int count) {
        for (int i = 0; i < count; i++) {
            Input in = INPUTS.get(i);
            int flags = 0;
            if (in.pushable) flags |= 1;
            if (in.vehicle) flags |= 1 << 1;
            if (in.passenger) flags |= 1 << 2;
            if (in.noPhysics) flags |= 1 << 3;
            int b = i * IN_STRIDE;
            NALIM_REQUEST[b] = in.id;
            NALIM_REQUEST[b + 1] = flags;
            NALIM_REQUEST[b + 2] = in.x;
            NALIM_REQUEST[b + 3] = in.z;
            NALIM_REQUEST[b + 4] = in.halfWidth;
            NALIM_REQUEST[b + 5] = in.minY;
            NALIM_REQUEST[b + 6] = in.maxY;
            NALIM_REQUEST[b + 7] = in.rootVehicleId;
        }
    }

    private static void validateNativeResults(int count) {
        for (int i = 0; i < count * OUT_STRIDE; i++) {
            if (!Double.isFinite(NALIM_RESULT[i])) {
                throw new IllegalStateException("Nalim cramming returned a non-finite value at index " + i);
            }
        }
        for (int i = 0; i < count; i++) {
            double dx = NALIM_RESULT[i * OUT_STRIDE + 1];
            double dz = NALIM_RESULT[i * OUT_STRIDE + 2];
            double crowded = NALIM_RESULT[i * OUT_STRIDE + 3];
            if (Math.abs(dx) > 128.0D || Math.abs(dz) > 128.0D
                || crowded < 0.0D || crowded > count) {
                throw new IllegalStateException("Nalim cramming returned implausible output for entity index " + i);
            }
        }
    }

    private static void readNalimResults(int count) {
        for (int i = 0; i < count; i++) {
            int b = i * OUT_STRIDE;
            ACCUM_DX[i] = NALIM_RESULT[b + 1] * VANILLA_PAIR_FACTOR;
            ACCUM_DZ[i] = NALIM_RESULT[b + 2] * VANILLA_PAIR_FACTOR;
            CROWDED[i] = (int) NALIM_RESULT[b + 3];
        }
    }

    private static double computeCellSize(int count) {
        double maxHalf = 0.0D;
        for (int i = 0; i < count; i++) maxHalf = Math.max(maxHalf, INPUTS.get(i).halfWidth);
        return Math.max(MIN_CELL_SIZE, 2.0D * maxHalf);
    }

    private static void bruteForce(int count) {
        for (int i = 0; i < count; i++) {
            if (INPUTS.get(i).noPhysics) continue;
            for (int j = i + 1; j < count; j++) processPair(i, j);
        }
    }

    private static void buildCells(int count, double cellSize) {
        CELLS.clear();
        for (int i = 0; i < count; i++) {
            Input input = INPUTS.get(i);
            long key = cellKey(cellX(input.x, cellSize), cellX(input.z, cellSize));
            IntArrayList bucket = CELLS.get(key);
            if (bucket == null) {
                bucket = new IntArrayList();
                CELLS.put(key, bucket);
            }
            bucket.add(i);
        }
    }

    private static void processPair(int i, int j) {
        Input a = INPUTS.get(i);
        Input b = INPUTS.get(j);
        if (b.noPhysics || a.rootVehicleId == b.rootVehicleId) return;

        final double dx = b.x - a.x;
        final double dz = b.z - a.z;
        final double halfSum = a.halfWidth + b.halfWidth;
        if (Math.abs(dx) >= halfSum || Math.abs(dz) >= halfSum) return;
        if (!(a.minY < b.maxY && b.minY < a.maxY)) return;

        if (b.pushable && !b.passenger) CROWDED[i]++;
        if (a.pushable && !a.passenger) CROWDED[j]++;

        final double f = Math.max(Math.abs(dx), Math.abs(dz));
        if (f < MIN_CONTACT) return;
        final double sqrtF = Math.sqrt(f);
        final double dn = dx / sqrtF;
        final double en = dz / sqrtF;
        final double g = Math.min(1.0D, 1.0D / sqrtF);
        final double pushDx = dn * g * PUSH_SCALE * VANILLA_PAIR_FACTOR;
        final double pushDz = en * g * PUSH_SCALE * VANILLA_PAIR_FACTOR;

        if (a.pushable && !a.vehicle) {
            ACCUM_DX[i] -= pushDx;
            ACCUM_DZ[i] -= pushDz;
        }
        if (b.pushable && !b.vehicle) {
            ACCUM_DX[j] += pushDx;
            ACCUM_DZ[j] += pushDz;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        LAST_TICKS.remove(level);
        FALLBACK_TICKS.remove(level);
    }

    private static int cellX(double value, double cellSize) { return (int) Math.floor(value / cellSize); }

    private static long cellKey(int x, int z) {
        long k = ((long) z & 0xffffffffL) << 32 | ((long) x & 0xffffffffL);
        k ^= 0xFE221E5EED1A0DBEL;
        k *= 0xBF58476D1CE4E5B9L;
        k ^= k >>> 27;
        k *= 0x94D049BB133111EBL;
        k ^= k >>> 31;
        return k;
    }

    private static void logDiagnosticsIfDue() {
        long now = System.nanoTime();
        if (now - diagLastLogNs < 5_000_000_000L) return;
        diagLastLogNs = now;
        Nephritis.LOGGER.info(
            "[cramming-dispatch] batches={} mobs={} pushed={} damaged={} native={} java={}",
            diagBatches, diagMobs, diagPushed, diagDamaged, diagNative, diagJava);
        diagBatches = diagMobs = diagPushed = diagDamaged = diagNative = diagJava = 0L;
    }

    private record Input(
        int id, boolean pushable, boolean vehicle, boolean passenger, boolean noPhysics,
        double x, double z, double halfWidth, double minY, double maxY, int rootVehicleId) {}
}
