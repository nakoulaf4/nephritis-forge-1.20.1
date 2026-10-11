package me.apika.nephritis.monitor;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;


public final class NephritisProfiler {
    public enum Bucket {
        SERVER_TICK,
        CRAMMING,
        ENTITY_QUERY,
        ENTITY_QUERY_INDEX,
        COLLIDER_QUERY,
        REDSTONE,
        REDSTONE_BFS,
        HOPPER_HINT,
        HOPPER_HIGHWAY,
        IDLE_TICKERS,
        PREGEN,
        CHUNKFORCE,
        ENTITY_SCAN,
        CHUNK_LOAD,
        CHUNK_SAVE,
        WORLDGEN,
        LIGHTING,
        AI,
        PATHFINDING,
        BLOCK_TICKS,
        FLUID_TICKS,
        NETWORK,
        COMMANDS,
        OTHER
    }

    private static final LongAdder[] NANOS = new LongAdder[Bucket.values().length];
    private static final LongAdder[] CALLS = new LongAdder[Bucket.values().length];
    private static final boolean JFR_ENABLED = Boolean.parseBoolean(
        System.getProperty("nephritis.profiler.jfr", "false"));

    static {
        for (int i = 0; i < NANOS.length; i++) {
            NANOS[i] = new LongAdder();
            CALLS[i] = new LongAdder();
        }
    }

    private NephritisProfiler() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void record(Bucket bucket, long startNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
        int index = bucket.ordinal();
        NANOS[index].add(elapsed);
        CALLS[index].increment();
        if (JFR_ENABLED) NephritisProfileEvent.commit(bucket, elapsed);
    }

    public static void reset() {
        for (int i = 0; i < NANOS.length; i++) {
            NANOS[i].reset();
            CALLS[i].reset();
        }
    }

    public static BucketSnapshot[] snapshotAndReset() {
        BucketSnapshot[] snapshot = new BucketSnapshot[Bucket.values().length];
        for (Bucket bucket : Bucket.values()) {
            int i = bucket.ordinal();
            snapshot[i] = new BucketSnapshot(bucket, NANOS[i].sumThenReset(), CALLS[i].sumThenReset());
        }
        return snapshot;
    }

    public record BucketSnapshot(Bucket bucket, long nanos, long calls) {
        public double millis() {
            return nanos / 1_000_000.0;
        }

        public String compact() {
            return String.format(Locale.ROOT, "%s=%.2fms/%d", bucket.name().toLowerCase(Locale.ROOT), millis(), calls);
        }
    }

    @Name("nephritis.Profile")
    @Label("Nephritis Profile Bucket")
    @Category("Nephritis")
    private static final class NephritisProfileEvent extends Event {
        @Label("Bucket")
        String bucket;

        @Label("Elapsed nanos")
        long elapsedNanos;

        private static void commit(Bucket bucket, long elapsedNanos) {
            NephritisProfileEvent event = new NephritisProfileEvent();
            if (!event.isEnabled()) return;
            event.bucket = bucket.name();
            event.elapsedNanos = elapsedNanos;
            event.commit();
        }
    }
}
