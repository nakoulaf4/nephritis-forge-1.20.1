package me.apika.nephritis.monitor;

import me.apika.nephritis.Nephritis;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;


public final class MonitorManager {
    private static final int REPORT_TICKS = 100;
    private static final AtomicBoolean ENABLED = new AtomicBoolean(readInitialState());

    private static long windowStartNanos;
    private static long windowTickNanos;
    private static long windowMaxTickNanos;
    private static int windowTicks;
    private static int windowSlowTicks;
    private static long currentTickStartNanos;
    private static boolean armed;
    private static volatile String lastReport = "not yet measured";

    private MonitorManager() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        if (!enabled) {
            armed = false;
            resetWindow();
            NephritisProfiler.reset();
        }
    }

    public static String status() {
        return (isEnabled() ? "ON" : "OFF") + ", last=" + lastReport;
    }

    public static void logHardware() {
        Nephritis.LOGGER.info("[hw] os={} arch={} java={} processors={} heapMaxMiB={}",
            System.getProperty("os.name", "unknown"),
            System.getProperty("os.arch", "unknown"),
            System.getProperty("java.version", "unknown"),
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() / 1048576L);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!isEnabled()) return;
        if (event.phase == TickEvent.Phase.START) {
            currentTickStartNanos = System.nanoTime();
            if (!armed) {
                armed = true;
                windowStartNanos = currentTickStartNanos;
            }
            return;
        }

        long elapsed = System.nanoTime() - currentTickStartNanos;
        NephritisProfiler.record(NephritisProfiler.Bucket.SERVER_TICK, currentTickStartNanos);
        windowTickNanos += elapsed;
        windowMaxTickNanos = Math.max(windowMaxTickNanos, elapsed);
        windowTicks++;
        if (elapsed > 50_000_000L) windowSlowTicks++;

        if (windowTicks >= REPORT_TICKS) {
            report(event.getServer());
            resetWindow();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        armed = false;
        resetWindow();
        NephritisProfiler.reset();
    }

    private static void report(MinecraftServer server) {
        long totalNanos = windowTickNanos;
        double avgMs = windowTicks == 0 ? 0.0 : (totalNanos / 1_000_000.0) / windowTicks;
        double windowSec = (System.nanoTime() - windowStartNanos) / 1_000_000_000.0;
        double tpsCeiling = windowSec <= 0 ? 20.0 : Math.min(20.0, windowTicks / windowSec);
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long heapMax = Runtime.getRuntime().maxMemory();

        int entities = 0;
        int loadedChunks = 0;
        int levels = 0;
        for (ServerLevel level : server.getAllLevels()) {
            levels++;
            for (var ignoredEntity : level.getAllEntities()) entities++;
            loadedChunks += level.getChunkSource().getLoadedChunksCount();
        }

        double maxMs = windowMaxTickNanos / 1_000_000.0;
        String report = String.format(Locale.ROOT,
            "avgTick=%.2fms maxTick=%.2fms >50ms=%d/%d tpsCap=%.2f entities=%d chunks=%d levels=%d heap=%.0f/%.0fMiB",
            avgMs, maxMs, windowSlowTicks, windowTicks, tpsCeiling,
            entities, loadedChunks, levels,
            heapUsed / 1048576.0, heapMax / 1048576.0);
        lastReport = report;
        Nephritis.LOGGER.info("[monitor] {}", report);
        StringBuilder buckets = new StringBuilder();
        for (NephritisProfiler.BucketSnapshot snapshot : NephritisProfiler.snapshotAndReset()) {
            if (snapshot.calls() == 0L) continue;
            if (buckets.length() > 0) buckets.append(' ');
            buckets.append(snapshot.compact());
        }
        if (buckets.length() > 0) Nephritis.LOGGER.info("[monitor/buckets] {}", buckets);
    }

    private static void resetWindow() {
        windowStartNanos = 0L;
        windowTickNanos = 0L;
        windowMaxTickNanos = 0L;
        windowTicks = 0;
        windowSlowTicks = 0;
    }

    private static boolean readInitialState() {
        return Boolean.parseBoolean(System.getProperty("nephritis.log.monitors", "true"));
    }
}
