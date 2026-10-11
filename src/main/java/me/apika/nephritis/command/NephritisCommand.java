package me.apika.nephritis.command;

import com.mojang.brigadier.CommandDispatcher;
import me.apika.nephritis.entity.ColliderSkip;
import me.apika.nephritis.entity.EntityQueryIndex;
import me.apika.nephritis.entity.CrammingDispatcher;
import me.apika.nephritis.pregen.PregenManager;
import me.apika.nephritis.redstone.RedstoneOptimizer;
import me.apika.nephritis.monitor.MonitorManager;
import me.apika.nephritis.monitor.StatusReport;
import me.apika.nephritis.chunkforce.PredictiveChunkForce;
import net.minecraft.server.MinecraftServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import me.apika.nephritis.hopper.HopperHighway;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class NephritisCommand {
    private NephritisCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nephritis")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(ctx -> fullStatus(ctx.getSource())))
            .then(Commands.literal("cramming")
                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("backend")
                    .then(Commands.literal("nalim").executes(ctx -> backend(ctx.getSource(), true)))
                    .then(Commands.literal("java").executes(ctx -> backend(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> backendStatus(ctx.getSource())))))
            .then(Commands.literal("hopper")
                .then(Commands.literal("highway")
                    .then(Commands.literal("on").executes(ctx -> hopperHighway(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> hopperHighway(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> hopperHighwayStatus(ctx.getSource())))))
            .then(Commands.literal("entityquery")
                .then(Commands.literal("cache")
                    .then(Commands.literal("on").executes(ctx -> entityCache(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> entityCache(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> entityCacheStatus(ctx.getSource())))
                    .then(Commands.literal("reset").executes(ctx -> resetEntityOracle(ctx.getSource()))))
                .then(Commands.literal("colliderskip")
                    .then(Commands.literal("on").executes(ctx -> colliderSkip(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> colliderSkip(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> colliderStatus(ctx.getSource())))))
            .then(Commands.literal("redstone")
                .then(Commands.literal("ac")
                    .then(Commands.literal("on").executes(ctx -> redstoneAc(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> redstoneAc(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> redstoneStatus(ctx.getSource()))))
                .then(Commands.literal("bfs")
                    .then(Commands.literal("on").executes(ctx -> redstoneBfs(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> redstoneBfs(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> redstoneStatus(ctx.getSource()))))
                .then(Commands.literal("bfs-min")
                    .then(Commands.argument("value", IntegerArgumentType.integer(1, 32768))
                        .executes(ctx -> setRedstoneBfsMin(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))))
                .then(Commands.literal("oracle")
                    .then(Commands.literal("reset").executes(ctx -> resetRedstoneOracle(ctx.getSource())))
                    .then(Commands.literal("status").executes(ctx -> redstoneStatus(ctx.getSource()))))
            )
            .then(Commands.literal("pregen")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 50))
                    .executes(ctx -> startPregen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))
                .then(Commands.literal("status").executes(ctx -> pregenStatus(ctx.getSource())))
                .then(Commands.literal("cancel").executes(ctx -> cancelPregen(ctx.getSource())))
                .then(Commands.literal("inflight")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                        .executes(ctx -> setPregenInflight(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
            )
            .then(Commands.literal("chunkforce")
                .then(Commands.literal("on").executes(ctx -> chunkForce(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> chunkForce(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> chunkForceStatus(ctx.getSource())))
                .then(Commands.literal("inflight")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> setChunkForceInflight(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("lead")
                    .then(Commands.argument("chunks", IntegerArgumentType.integer(2, 12))
                        .executes(ctx -> setChunkForceLead(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "chunks")))))
            )
            .then(Commands.literal("log")
                .then(Commands.literal("monitors")
                    .then(Commands.literal("on").executes(ctx -> monitorToggle(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> monitorToggle(ctx.getSource(), false)))
                    .then(Commands.literal("status").executes(ctx -> monitorStatus(ctx.getSource())))))
        );
    }

    private static int fullStatus(CommandSourceStack source) {
        for (String line : StatusReport.lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, boolean enabled) {
        CrammingDispatcher.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Nephritis cramming: " + (enabled ? "ON" : "OFF")), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        boolean enabled = CrammingDispatcher.isEnabled();
        source.sendSuccess(() -> Component.literal("Nephritis cramming: " + (enabled ? "ON" : "OFF")), false);
        return enabled ? 1 : 0;
    }

    private static int backend(CommandSourceStack source, boolean nativeBackend) {
        CrammingDispatcher.setNativePreferred(nativeBackend);
        source.sendSuccess(() -> Component.literal("Nephritis cramming backend preference: "
            + (nativeBackend ? "NALIM/RUST" : "JAVA")), true);
        return 1;
    }

    private static int hopperHighway(CommandSourceStack source, boolean enabled) {
        HopperHighway.setEnabled(enabled);
        boolean actual = HopperHighway.isEnabled();
        if (enabled && !actual) {
            source.sendFailure(Component.literal("Nephritis hopper highway is locked on Forge 1.20.1 until the exact vanilla + Forge sided-transfer path is ported."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Nephritis hopper highway: " + (actual ? "ON" : "OFF")), true);
        return 1;
    }

    private static int hopperHighwayStatus(CommandSourceStack source) {
        boolean enabled = HopperHighway.isEnabled();
        source.sendSuccess(() -> Component.literal("Nephritis hopper highway: " + (enabled ? "ON" : "OFF")), false);
        return enabled ? 1 : 0;
    }


    private static int entityCache(CommandSourceStack source, boolean enabled) {
        EntityQueryIndex.setEnabled(enabled);
        if (enabled) {
            // The candidate index is the actual optimization; "cache on" must
            // bring the whole pipeline up, not only the outer gate.
            EntityQueryIndex.setCandidatesEnabled(true);
        }
        boolean actual = EntityQueryIndex.isEnabled() && EntityQueryIndex.isCandidatesEnabled();
        if (enabled && !actual) {
            source.sendFailure(Component.literal("Nephritis entity query cache could not enable the candidate index. "
                + "Reason: oracle tripped or oracle disabled ("
                + EntityQueryIndex.status() + "). Fix the cause or run /nephritis entityquery cache reset first."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Nephritis entity query cache: "
            + (actual ? "ON (candidates enabled)" : "OFF")), true);
        return actual ? 1 : 0;
    }

    private static int entityCacheStatus(CommandSourceStack source) {
        boolean enabled = EntityQueryIndex.isEnabled();
        source.sendSuccess(() -> Component.literal("Nephritis entity query: " + EntityQueryIndex.status()), false);
        return enabled ? 1 : 0;
    }

    private static int resetEntityOracle(CommandSourceStack source) {
        EntityQueryIndex.resetOracle();
        source.sendSuccess(() -> Component.literal("Nephritis entity-query index/oracle reset"), true);
        return 1;
    }

    private static int colliderSkip(CommandSourceStack source, boolean enabled) {
        ColliderSkip.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Nephritis entity collider skip: "
            + (enabled ? "ON (active in Entity#collide)" : "OFF")), true);
        return 1;
    }

    private static int colliderStatus(CommandSourceStack source) {
        boolean enabled = ColliderSkip.isEnabled();
        source.sendSuccess(() -> Component.literal("Nephritis entity collider skip: " + (enabled ? "ON" : "OFF")), false);
        return enabled ? 1 : 0;
    }

    private static int redstoneAc(CommandSourceStack source, boolean enabled) {
        RedstoneOptimizer.setEnabled(enabled);
        boolean actual = RedstoneOptimizer.isEnabled();
        if (enabled && !actual) {
            source.sendFailure(Component.literal("Nephritis redstone AC remains locked on Forge 1.20.1. Start with -Dnephritis.redstone.experimental=true and -Dnephritis.redstone.oracle=true after verifying the shadow-oracle path."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Nephritis redstone AC: " + (actual ? "ON" : "OFF")), true);
        return 1;
    }

    private static int resetRedstoneOracle(CommandSourceStack source) {
        RedstoneOptimizer.resetOracle();
        source.sendSuccess(() -> Component.literal("Nephritis redstone oracle reset"), true);
        return 1;
    }

    private static int redstoneBfs(CommandSourceStack source, boolean enabled) {
        RedstoneOptimizer.setBfsEnabled(enabled);
        if (enabled && !RedstoneOptimizer.isEnabled()) {
            source.sendSuccess(() -> Component.literal("Nephritis redstone native BFS: enabled, but INERT until the redstone AC is enabled "
                + "(BFS is only used inside the AC pipeline)."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Nephritis redstone native BFS: " + (enabled ? "enabled" : "disabled")), true);
        return enabled ? 1 : 0;
    }

    private static int redstoneStatus(CommandSourceStack source) {
        String status = RedstoneOptimizer.status();
        source.sendSuccess(() -> Component.literal("Nephritis redstone: " + status), false);
        return RedstoneOptimizer.isEnabled() ? 1 : 0;
    }

    private static int setRedstoneBfsMin(CommandSourceStack source, int value) {
        RedstoneOptimizer.setBfsMin(value);
        source.sendSuccess(() -> Component.literal("Nephritis redstone BFS minimum network: "
            + RedstoneOptimizer.getBfsMin()), true);
        return 1;
    }

    private static int startPregen(CommandSourceStack source, int radius) {
        MinecraftServer server = source.getServer();
        if (!PregenManager.start(server, radius)) {
            source.sendFailure(Component.literal("Nephritis pregen is already running, or radius is invalid."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Nephritis pregen started: radius=" + radius
            + " around the Overworld spawn."), true);
        return 1;
    }

    private static int pregenStatus(CommandSourceStack source) {
        String status = PregenManager.status();
        source.sendSuccess(() -> Component.literal("Nephritis pregen: " + status), false);
        return PregenManager.isRunning() ? 1 : 0;
    }

    private static int cancelPregen(CommandSourceStack source) {
        boolean wasRunning = PregenManager.isRunning();
        PregenManager.cancel();
        source.sendSuccess(() -> Component.literal(wasRunning ? "Nephritis pregen cancelled." : "Nephritis pregen is not running."), true);
        return wasRunning ? 1 : 0;
    }

    private static int setPregenInflight(CommandSourceStack source, int count) {
        PregenManager.setInflight(count);
        source.sendSuccess(() -> Component.literal("Nephritis pregen inflight: " + PregenManager.getInflight()), true);
        return 1;
    }

    private static int chunkForce(CommandSourceStack source, boolean enabled) {
        PredictiveChunkForce.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Nephritis predictive chunkforce: " + (enabled ? "ON" : "OFF")), true);
        return 1;
    }

    private static int chunkForceStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Nephritis chunkforce: " + PredictiveChunkForce.status()), false);
        return PredictiveChunkForce.isEnabled() ? 1 : 0;
    }

    private static int setChunkForceInflight(CommandSourceStack source, int value) {
        PredictiveChunkForce.setInflight(value);
        source.sendSuccess(() -> Component.literal("Nephritis chunkforce inflight: " + PredictiveChunkForce.getInflight()), true);
        return 1;
    }

    private static int setChunkForceLead(CommandSourceStack source, int value) {
        PredictiveChunkForce.setLead(value);
        source.sendSuccess(() -> Component.literal("Nephritis chunkforce lead: " + PredictiveChunkForce.getLead() + " chunks"), true);
        return 1;
    }

    private static int monitorToggle(CommandSourceStack source, boolean enabled) {
        MonitorManager.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Nephritis log monitors: " + (enabled ? "ON" : "OFF")), true);
        return 1;
    }

    private static int monitorStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Nephritis log monitors: " + MonitorManager.status()), false);
        return MonitorManager.isEnabled() ? 1 : 0;
    }

    private static int backendStatus(CommandSourceStack source) {
        String status = CrammingDispatcher.backendStatus();
        source.sendSuccess(() -> Component.literal("Nephritis cramming backend: " + status), false);
        return status.equals("NALIM/RUST") ? 1 : 0;
    }
}
