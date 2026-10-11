package me.apika.nephritis.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.apika.nephritis.Nephritis;
import me.apika.nephritis.monitor.NephritisProfiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;


public final class EntityQueryIndex {
    private static final String CACHE_PROPERTY = "nephritis.entityquery.cache";
    private static final String CANDIDATE_PROPERTY = "nephritis.entityquery.candidates";
    private static final String ORACLE_PROPERTY = "nephritis.entityquery.oracle";
    private static final int CELL_SHIFT = 4;
    private static final int MAX_QUERY_CELLS = 4096;
    private static final int MAX_ENTITY_CELLS = 1024;
    private static final int ORACLE_MASK = 0xFF; // 1/256 sampled queries by default.
    // Vanilla inflates collision query boxes by 1.0E-7; we use a 10x larger
    // epsilon so the index is always on the conservative side of the boundary.
    private static final double COLLISION_EPSILON = 1.0E-6D;

    private static volatile boolean enabled = readBoolean(CACHE_PROPERTY, true);
    private static volatile boolean candidatesEnabled = readBoolean(CANDIDATE_PROPERTY, false);
    private static final boolean oracleEnabled = readBoolean(ORACLE_PROPERTY, true);
    private static volatile boolean oracleTripped;

    private static final Map<ServerLevel, WorldIndex> WORLDS = new IdentityHashMap<>();
    // Fast-path flag for EntitySpatialIndexMovementMixin: true only while at
    // least one world index exists. Avoids taking the WORLDS lock on every
    // setPosRaw call when the candidate index was never built.
    private static volatile boolean anyIndex;

    private static final AtomicLong queries = new AtomicLong();
    private static final AtomicLong candidateQueries = new AtomicLong();
    private static final AtomicLong emptyQueries = new AtomicLong();
    private static final AtomicLong oracleChecks = new AtomicLong();
    private static final AtomicLong oracleMismatches = new AtomicLong();

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private EntityQueryIndex() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        synchronized (WORLDS) {
            WORLDS.clear();
            anyIndex = false;
        }
        if (!value) {
            // Turning the cache off must also stop serving candidate results.
            candidatesEnabled = false;
        }
    }

    public static void resetOracle() {
        oracleTripped = false;
        candidatesEnabled = readBoolean(CANDIDATE_PROPERTY, false) && oracleEnabled;
        synchronized (WORLDS) {
            WORLDS.clear();
        }
    }

    public static boolean isCandidatesEnabled() {
        return candidatesEnabled;
    }

    public static void setCandidatesEnabled(boolean value) {
        if (value && !oracleEnabled) {
            Nephritis.LOGGER.warn("Entity candidate index requires the vanilla oracle; set -D{}=true and restart.", ORACLE_PROPERTY);
            candidatesEnabled = false;
            return;
        }
        candidatesEnabled = value && !oracleTripped;
    }

    public static boolean isBypass() {
        return BYPASS.get();
    }


    public static List<Entity> query(ServerLevel level, Entity except, AABB bounds,
                                     Predicate<? super Entity> predicate) {
        // Cheap guard first: when the feature is off this must not pay for
        // nanoTime/counter/profiler bookkeeping on every Level.getEntities call.
        if (!enabled || !candidatesEnabled || oracleTripped || bounds == null) return null;
        long start = System.nanoTime();
        try {
            queries.incrementAndGet();
            WorldIndex index = world(level);
            Set<Entity> candidates = index.candidates(bounds);
            if (candidates == null) return null;

            ArrayList<Entity> result = new ArrayList<>(candidates.size());
            for (Entity entity : candidates) {
                if (entity == except || entity.isRemoved()) continue;
                if (!entity.getBoundingBox().intersects(bounds)) continue;
                if (predicate == null || predicate.test(entity)) result.add(entity);
            }
            candidateQueries.incrementAndGet();
            if (result.isEmpty()) emptyQueries.incrementAndGet();

            if (shouldSampleOracle()) {
                List<Entity> vanilla = vanillaRaw(level, except, bounds, predicate);
                oracleChecks.incrementAndGet();
                if (!sameMembership(result, vanilla)) {
                    tripOracle("raw entity query", level, bounds);
                    return vanilla;
                }
            }
            return result;
        } finally {
            NephritisProfiler.record(NephritisProfiler.Bucket.ENTITY_QUERY_INDEX, start);
        }
    }

    public static <T extends Entity> List<T> queryTyped(
        ServerLevel level,
        EntityTypeTest<Entity, T> typeTest,
        AABB bounds,
        Predicate<? super T> predicate
    ) {
        // Cheap guard first: see query().
        if (!enabled || !candidatesEnabled || oracleTripped || typeTest == null || bounds == null) return null;
        long start = System.nanoTime();
        try {
            queries.incrementAndGet();
            WorldIndex index = world(level);
            Set<Entity> candidates = index.candidates(bounds);
            if (candidates == null) return null;

            ArrayList<T> result = new ArrayList<>(candidates.size());
            for (Entity entity : candidates) {
                if (entity.isRemoved()) continue;
                if (!entity.getBoundingBox().intersects(bounds)) continue;
                T cast = typeTest.tryCast(entity);
                if (cast == null) continue;
                if (predicate == null || predicate.test(cast)) result.add(cast);
            }
            candidateQueries.incrementAndGet();
            if (result.isEmpty()) emptyQueries.incrementAndGet();

            if (shouldSampleOracle()) {
                List<T> vanilla = vanillaTyped(level, typeTest, bounds, predicate);
                oracleChecks.incrementAndGet();
                if (!sameMembership(result, vanilla)) {
                    tripOracle("typed entity query", level, bounds);
                    return vanilla;
                }
            }
            return result;
        } finally {
            NephritisProfiler.record(NephritisProfiler.Bucket.ENTITY_QUERY_INDEX, start);
        }
    }

    public static boolean isProvablyEmpty(ServerLevel level, AABB bounds) {
        if (!enabled || bounds == null) return false;
        WorldIndex index = world(level);
        Set<Entity> candidates = index.candidates(bounds);
        if (candidates == null) return false;
        if (!candidates.isEmpty()) return false;
        emptyQueries.incrementAndGet();
        return true;
    }

    /**
     * Conservative emptiness test for the movement collision path
     * (EntityCollideSkipMixin redirects CollisionGetter#getEntityCollisions).
     *
     * Vanilla 1.20.1 filters candidates with
     * EntitySelector.NO_SPECTATORS.and(source::canCollideWith) and queries the
     * box inflated by 1.0E-7; both facts were verified against the official
     * 1.20.1 client mappings/bytecode. We mirror that predicate exactly and use
     * a slightly larger epsilon so rounding can never let the index claim
     * "empty" while vanilla would still find a collision partner.
     *
     * This runs on every Entity#collide call, i.e. thousands of times per tick
     * on busy servers, so it must stay allocation-free and profiler-free:
     * the epsilon is applied manually (no AABB.inflate) and the scan exits at
     * the FIRST collidable neighbor. In dense areas that makes the proof cost
     * a couple of map lookups before handing back to vanilla.
     */
    public static boolean isProvablyEmptyForCollision(ServerLevel level, Entity source, AABB bounds) {
        if (!enabled || source == null || bounds == null) return false;
        WorldIndex index = world(level);
        return index.hasNoCollidable(
            source,
            bounds.minX - COLLISION_EPSILON, bounds.minY - COLLISION_EPSILON, bounds.minZ - COLLISION_EPSILON,
            bounds.maxX + COLLISION_EPSILON, bounds.maxY + COLLISION_EPSILON, bounds.maxZ + COLLISION_EPSILON);
    }

    public static String status() {
        return "cache=" + (enabled ? "ON" : "OFF")
            + " candidates=" + (candidatesEnabled ? "ON" : "OFF")
            + " oracle=" + (oracleEnabled ? "ON" : "OFF")
            + " tripped=" + (oracleTripped ? "YES" : "NO")
            + " queries=" + queries.get()
            + " candidate=" + candidateQueries.get()
            + " empty=" + emptyQueries.get()
            + " oracleChecks=" + oracleChecks.get()
            + " mismatches=" + oracleMismatches.get();
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        WorldIndex index = worldIfInitialized(level);
        if (index != null) index.add(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        WorldIndex index = worldIfInitialized(level);
        if (index != null) index.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (WORLDS) {
            WORLDS.clear();
            anyIndex = false;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        synchronized (WORLDS) {
            if (WORLDS.remove(level) != null && WORLDS.isEmpty()) {
                anyIndex = false;
            }
        }
    }

    public static void onEntityMoved(Entity entity) {
        // anyIndex avoids a synchronized read on every setPosRaw while no
        // world index has ever been built.
        if (!enabled || !anyIndex || entity.level().isClientSide || !(entity.level() instanceof ServerLevel level)) return;
        WorldIndex index = worldIfInitialized(level);
        if (index != null) index.update(entity);
    }


    public static List<Entity> vanillaRaw(ServerLevel level, Entity except, AABB bounds,
                                          Predicate<? super Entity> predicate) {
        boolean previous = BYPASS.get();
        BYPASS.set(true);
        try {
            return level.getEntities(except, bounds, predicate);
        } finally {
            BYPASS.set(previous);
        }
    }


    public static <T extends Entity> List<T> vanillaTyped(ServerLevel level,
                                                           EntityTypeTest<Entity, T> typeTest,
                                                           AABB bounds,
                                                           Predicate<? super T> predicate) {
        boolean previous = BYPASS.get();
        BYPASS.set(true);
        try {
            return level.getEntities(typeTest, bounds, predicate);
        } finally {
            BYPASS.set(previous);
        }
    }

    private static WorldIndex world(ServerLevel level) {
        synchronized (WORLDS) {
            WorldIndex index = WORLDS.get(level);
            if (index == null) {
                index = new WorldIndex();
                for (Entity entity : level.getAllEntities()) index.add(entity);
                WORLDS.put(level, index);
                anyIndex = true;
            }
            return index;
        }
    }

    private static WorldIndex worldIfInitialized(ServerLevel level) {
        synchronized (WORLDS) {
            return WORLDS.get(level);
        }
    }

    private static boolean shouldSampleOracle() {
        return oracleEnabled && !oracleTripped && (queries.get() & ORACLE_MASK) == 0L;
    }

    private static void tripOracle(String kind, ServerLevel level, AABB bounds) {
        oracleMismatches.incrementAndGet();
        oracleTripped = true;
        candidatesEnabled = false;
        Nephritis.LOGGER.error(
            "[entity-query-oracle] {} mismatch in {} at {}. Candidate index disabled; vanilla retained.",
            kind, level.dimension().location(), bounds);
    }

    private static boolean sameMembership(List<? extends Entity> a, List<? extends Entity> b) {
        if (a.size() != b.size()) return false;
        IdentityHashMap<Entity, Boolean> seen = new IdentityHashMap<>(Math.max(16, a.size() * 2));
        for (Entity entity : a) seen.put(entity, Boolean.TRUE);
        for (Entity entity : b) if (!seen.containsKey(entity)) return false;
        return true;
    }

    private static boolean readBoolean(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static final class WorldIndex {
        private final Long2ObjectOpenHashMap<Set<Entity>> cells = new Long2ObjectOpenHashMap<>();
        // Value = {minCellX, maxCellX, minCellZ, maxCellZ}. Storing extents (4 ints)
        // instead of a materialized cell-key array lets update() compare in O(1) and
        // stay allocation-free while an entity remains inside one cell region — the
        // overwhelmingly common case in the setPosRaw hot path.
        private final IdentityHashMap<Entity, int[]> entityCells = new IdentityHashMap<>();
        private final Set<Entity> oversized = Collections.newSetFromMap(new IdentityHashMap<>());

        private void add(Entity entity) {
            if (entity == null || entity.isRemoved() || entityCells.containsKey(entity) || oversized.contains(entity)) return;
            AABB box = entity.getBoundingBox();
            int minX = cellCoordinate(box.minX);
            int maxX = cellCoordinate(box.maxX);
            int minZ = cellCoordinate(box.minZ);
            int maxZ = cellCoordinate(box.maxZ);
            long span = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            if (span > MAX_ENTITY_CELLS) {
                oversized.add(entity);
                return;
            }
            entityCells.put(entity, new int[] {minX, maxX, minZ, maxZ});
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) bucket(chunkKey(x, z)).add(entity);
            }
        }

        private void remove(Entity entity) {
            oversized.remove(entity);
            int[] ext = entityCells.remove(entity);
            if (ext == null) return;
            for (int x = ext[0]; x <= ext[1]; x++) {
                for (int z = ext[2]; z <= ext[3]; z++) {
                    long key = chunkKey(x, z);
                    Set<Entity> bucket = cells.get(key);
                    if (bucket == null) continue;
                    bucket.remove(entity);
                    if (bucket.isEmpty()) cells.remove(key);
                }
            }
        }

        private void update(Entity entity) {
            if (entity == null || entity.isRemoved()) return;
            AABB box = entity.getBoundingBox();
            int minX = cellCoordinate(box.minX);
            int maxX = cellCoordinate(box.maxX);
            int minZ = cellCoordinate(box.minZ);
            int maxZ = cellCoordinate(box.maxZ);
            long span = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            if (oversized.contains(entity)) {
                if (span > MAX_ENTITY_CELLS) return;
                oversized.remove(entity);
            }
            if (span > MAX_ENTITY_CELLS) {
                remove(entity);
                oversized.add(entity);
                return;
            }

            int[] old = entityCells.get(entity);
            // Allocation-free fast path: the entity did not leave its cell region.
            if (old != null && old[0] == minX && old[1] == maxX && old[2] == minZ && old[3] == maxZ) return;
            remove(entity);
            entityCells.put(entity, new int[] {minX, maxX, minZ, maxZ});
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) bucket(chunkKey(x, z)).add(entity);
            }
        }

        /**
         * Allocation-free, profiler-free emptiness proof for the movement
         * collision path (EntityCollideSkipMixin): scans only the overlapped
         * cells and exits at the FIRST collidable neighbor. In dense areas it
         * therefore costs a couple of map lookups before vanilla re-runs; in
         * empty areas it replaces the whole vanilla getEntityCollisions scan.
         */
        private boolean hasNoCollidable(Entity source,
                                        double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ) {
            int cminX = cellCoordinate(minX);
            int cmaxX = cellCoordinate(maxX);
            int cminZ = cellCoordinate(minZ);
            int cmaxZ = cellCoordinate(maxZ);
            long width = (long) cmaxX - cminX + 1L;
            long depth = (long) cmaxZ - cminZ + 1L;
            if (width <= 0L || depth <= 0L || width * depth > MAX_QUERY_CELLS) return false;
            for (int x = cminX; x <= cmaxX; x++) {
                for (int z = cminZ; z <= cmaxZ; z++) {
                    Set<Entity> bucket = cells.get(chunkKey(x, z));
                    if (bucket == null || bucket.isEmpty()) continue;
                    for (Entity entity : bucket) {
                        if (entity == source || entity.isRemoved()) continue;
                        if (!entity.getBoundingBox().intersects(minX, minY, minZ, maxX, maxY, maxZ)) continue;
                        // Exactly vanilla's NO_SPECTATORS.and(source::canCollideWith).
                        if (!entity.isSpectator() && source.canCollideWith(entity)) return false;
                    }
                }
            }
            // Oversized entities are not represented in the normal cell budget;
            // they must still be able to veto the "empty" verdict.
            for (Entity entity : oversized) {
                if (entity == source || entity.isRemoved()) continue;
                if (!entity.getBoundingBox().intersects(minX, minY, minZ, maxX, maxY, maxZ)) continue;
                if (!entity.isSpectator() && source.canCollideWith(entity)) return false;
            }
            return true;
        }

        private Set<Entity> candidates(AABB query) {
            long start = System.nanoTime();
            try {
                int minX = cellCoordinate(query.minX);
                int maxX = cellCoordinate(query.maxX);
                int minZ = cellCoordinate(query.minZ);
                int maxZ = cellCoordinate(query.maxZ);
                long width = (long) maxX - minX + 1L;
                long depth = (long) maxZ - minZ + 1L;
                if (width <= 0 || depth <= 0 || width * depth > MAX_QUERY_CELLS) return null;

                Set<Entity> result = Collections.newSetFromMap(new IdentityHashMap<>());
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Set<Entity> bucket = cells.get(chunkKey(x, z));
                        if (bucket != null) result.addAll(bucket);
                    }
                }
                // An oversized entity cannot be represented in the normal cell budget.
                // Add it to every candidate query and let the exact AABB test decide.
                result.addAll(oversized);
                return result;
            } finally {
                NephritisProfiler.record(NephritisProfiler.Bucket.ENTITY_QUERY, start);
            }
        }

        private Set<Entity> bucket(long key) {
            Set<Entity> bucket = cells.get(key);
            if (bucket == null) {
                bucket = Collections.newSetFromMap(new IdentityHashMap<>());
                cells.put(key, bucket);
            }
            return bucket;
        }
    }

    private static int cellCoordinate(double value) {
        return (int) Math.floor(value / (1 << CELL_SHIFT));
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
