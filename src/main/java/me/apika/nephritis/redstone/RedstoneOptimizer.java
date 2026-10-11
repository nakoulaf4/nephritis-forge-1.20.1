package me.apika.nephritis.redstone;

import com.mojang.logging.LogUtils;
import me.apika.nephritis.Nephritis;
import me.apika.nephritis.mixin.RedStoneWireBlockAccessorMixin;
import me.apika.nephritis.nativebridge.NalimBridge;
import me.apika.nephritis.monitor.NephritisProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


public final class RedstoneOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NETWORK = clampPositive(
        Integer.getInteger("nephritis.redstone.maxNetwork", 32768), 32768);
    private static final int DEFAULT_BFS_MIN = clampPositive(
        Integer.getInteger("nephritis.redstone.bfs-min", 8), MAX_NETWORK);
    private static final boolean EXPERIMENTAL_ENABLED = Boolean.parseBoolean(
        System.getProperty("nephritis.redstone.experimental", "false"));
    private static final boolean DEFAULT_ENABLED = EXPERIMENTAL_ENABLED && Boolean.parseBoolean(
        System.getProperty("nephritis.redstone.ac", "false"));
    private static final boolean DEFAULT_BFS = Boolean.parseBoolean(
        System.getProperty("nephritis.redstone.bfs", "true"));
    private static final boolean ORACLE_ENABLED = EXPERIMENTAL_ENABLED && Boolean.parseBoolean(
        System.getProperty("nephritis.redstone.oracle", "false"));
    private static final int ORACLE_MASK = 0x3F; // 1/64 of AC cascades when explicitly enabled.

    private static final int MAX_CONNECTIONS = 8;
    private static final int NATIVE_STRIDE = 1 + MAX_CONNECTIONS;

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static volatile boolean bfsEnabled = DEFAULT_BFS;
    private static volatile int bfsMin = DEFAULT_BFS_MIN;
    private static volatile boolean oracleTripped;
    private static long oracleChecks;
    private static long oracleMismatches;
    private static final ThreadLocal<Boolean> VANILLA_BYPASS = ThreadLocal.withInitial(() -> false);
    private static final Map<ServerLevel, WorldState> WORLD_STATES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private RedstoneOptimizer() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (value && (!EXPERIMENTAL_ENABLED || !ORACLE_ENABLED)) {
            LOGGER.warn("Nephritis redstone AC is locked on Forge 1.20.1 until the shadow oracle is explicitly enabled with -Dnephritis.redstone.experimental=true -Dnephritis.redstone.oracle=true.");
            enabled = false;
            return;
        }
        if (value && oracleTripped) {
            LOGGER.warn("Nephritis redstone AC remains disabled because its oracle tripped. Use /nephritis redstone oracle reset before re-enabling it.");
            enabled = false;
            return;
        }
        enabled = value;
        if (!value) {
            synchronized (WORLD_STATES) {
                for (WorldState state : WORLD_STATES.values()) {
                    state.clearPending();
                }
            }
        }
        LOGGER.info("Nephritis redstone optimizer {}", value ? "enabled" : "disabled");
    }

    public static void resetOracle() {
        oracleTripped = false;
        LOGGER.warn("Nephritis redstone oracle state reset; AC can be enabled again.");
    }

    public static boolean isBfsEnabled() {
        return bfsEnabled;
    }

    public static void setBfsEnabled(boolean value) {
        bfsEnabled = value;
        LOGGER.info("Nephritis redstone native BFS preference {}", value ? "enabled" : "disabled");
    }

    public static int getBfsMin() {
        return bfsMin;
    }

    public static void setBfsMin(int value) {
        bfsMin = clampPositive(value, MAX_NETWORK);
        LOGGER.info("Nephritis redstone BFS minimum network size: {}", bfsMin);
    }

    public static String status() {
        return "AC=" + (enabled ? "ON" : "OFF")
            + ", BFS=" + (bfsEnabled ? "ON" : "OFF")
            + ", bfs-min=" + bfsMin
            + ", experimental=" + (EXPERIMENTAL_ENABLED ? "ON" : "OFF")
            + ", NalimBFS=" + NalimBridge.peekStatus()
            + ", oracle=" + (ORACLE_ENABLED ? "ON" : "OFF")
            + ", oracleTripped=" + (oracleTripped ? "YES" : "NO")
            + ", oracleChecks=" + oracleChecks
            + ", oracleMismatches=" + oracleMismatches;
    }

    public static boolean isVanillaBypass() {
        return VANILLA_BYPASS.get();
    }


    public static boolean handle(ServerLevel level, BlockPos rootPos, BlockState rootState) {
        final long profileStart = System.nanoTime();
        try {
            return handleImpl(level, rootPos, rootState);
        } finally {
            NephritisProfiler.record(NephritisProfiler.Bucket.REDSTONE, profileStart);
        }
    }

    private static boolean handleImpl(ServerLevel level, BlockPos rootPos, BlockState rootState) {
        if (!enabled || !(rootState.getBlock() instanceof RedStoneWireBlock)) {
            return false;
        }

        WorldState worldState = WORLD_STATES.computeIfAbsent(level, ignored -> new WorldState());
        BlockPos immutableRoot = rootPos.immutable();
        if (worldState.processing) {
            worldState.enqueue(immutableRoot);
            return true;
        }

        worldState.processing = true;
        try {
            if (!processRoot(level, immutableRoot)) {
                return false;
            }

            while (!worldState.pending.isEmpty() && enabled) {
                BlockPos pendingRoot = worldState.pending.removeFirst();
                worldState.pendingKeys.remove(pendingRoot.asLong());
                if (!processRoot(level, pendingRoot)) {
                    callVanillaUpdate(level, pendingRoot, level.getBlockState(pendingRoot));
                }
            }
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("Nephritis redstone optimizer failed; disabling it and falling back to vanilla", throwable);
            enabled = false;
            worldState.clearPending();
            callVanillaUpdate(level, immutableRoot, level.getBlockState(immutableRoot));
            return true;
        } finally {
            worldState.processing = false;
        }
    }

    private static boolean processRoot(ServerLevel level, BlockPos rootPos) {
        BlockState rootState = level.getBlockState(rootPos);
        if (!(rootState.getBlock() instanceof RedStoneWireBlock)) {
            return true;
        }

        Network network = buildNetwork(level, rootPos);
        if (network == null) {
            return false;
        }

        try {
            // Remove all wire contribution while we ask the vanilla evaluator for
            // external power. This avoids recursive wire-chain recalculation.
            for (Node node : network.nodes) {
                writeWireState(level, node.pos,
                    node.state.setValue(RedStoneWireBlock.POWER, 0), false);
            }

            for (int i = 0; i < network.nodes.size(); i++) {
                Node node = network.nodes.get(i);
                int target = ((RedStoneWireBlockAccessorMixin) (Object) Blocks.REDSTONE_WIRE)
                    .nephritis$calculateTargetStrength(level, node.pos);
                network.externalPower[i] = clampPower(target);
            }

            int[] powers = spreadPower(network);
            if (shouldSampleOracle()) {
                oracleChecks++;
                if (!RedstoneOracle.compare(level, rootPos, network, powers)) {
                    oracleMismatches++;
                    oracleTripped = true;
                    enabled = false;
                    LOGGER.error(
                        "[redstone-oracle] mismatch at {} (nodes={}); AC disabled and vanilla restored for this cascade",
                        rootPos, network.nodes.size());
                    restoreOriginalPowers(level, network);
                    return false;
                }
            }
            applyPowers(level, network, powers);
            return true;
        } catch (Throwable throwable) {
            // Never leave the network powered down if a modded block or native
            // backend throws while the optimizer owns the update.
            restoreOriginalPowers(level, network);
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(throwable);
        }
    }

    private static Network buildNetwork(ServerLevel level, BlockPos rootPos) {
        ArrayList<Node> nodes = new ArrayList<>();
        Map<Long, Integer> discovered = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(rootPos.immutable());
        discovered.put(rootPos.asLong(), 0);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof RedStoneWireBlock)) {
                continue;
            }

            int nodeIndex = nodes.size();
            Node node = new Node(pos, state);
            nodes.add(node);
            discovered.put(pos.asLong(), nodeIndex);

            if (nodes.size() > MAX_NETWORK) {
                LOGGER.warn("Redstone network at {} exceeded MAX_NETWORK={}; using vanilla for this update",
                    rootPos, MAX_NETWORK);
                return null;
            }

            for (Direction dir : HORIZONTAL) {
                boolean belowIsConductor = level.getBlockState(pos.below())
                    .isRedstoneConductor(level, pos.below());
                boolean aboveIsConductor = level.getBlockState(pos.above())
                    .isRedstoneConductor(level, pos.above());

                BlockPos sidePos = pos.relative(dir);
                BlockState sideState = level.getBlockState(sidePos);
                if (sideState.getBlock() instanceof RedStoneWireBlock) {
                    addConnection(node, sidePos, true, true);
                    discoverWire(sidePos, discovered, queue);
                } else {
                    boolean sideIsConductor = sideState.isRedstoneConductor(level, sidePos);

                    if (!sideIsConductor) {
                        BlockPos below = sidePos.below();
                        BlockState belowState = level.getBlockState(below);
                        if (belowState.getBlock() instanceof RedStoneWireBlock) {
                            addConnection(node, below, belowIsConductor, true);
                            discoverWire(below, discovered, queue);
                        }
                    }

                    if (!aboveIsConductor) {
                        BlockPos above = sidePos.above();
                        BlockState aboveState = level.getBlockState(above);
                        if (aboveState.getBlock() instanceof RedStoneWireBlock) {
                            addConnection(node, above, true, sideIsConductor);
                            discoverWire(above, discovered, queue);
                        }
                    }
                }
            }
        }

        // Convert encoded positions to stable node indices after discovery.
        for (Node node : nodes) {
            for (int i = 0; i < node.targetPositions.length; i++) {
                long target = node.targetPositions[i];
                if (target == Long.MIN_VALUE) {
                    node.targets[i] = -1;
                } else {
                    node.targets[i] = discovered.getOrDefault(target, -1);
                }
            }
        }

        // Alternate Current uses directional offer/accept semantics. A graph edge
        // is traversable only when the source offers power and the target accepts it.
        for (int sourceIndex = 0; sourceIndex < nodes.size(); sourceIndex++) {
            Node node = nodes.get(sourceIndex);
            Arrays.fill(node.allowed, false);
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                int targetIndex = node.targets[i];
                if (!node.offer[i] || targetIndex < 0 || targetIndex >= nodes.size()) continue;
                Node target = nodes.get(targetIndex);
                for (int j = 0; j < MAX_CONNECTIONS; j++) {
                    if (target.targets[j] == sourceIndex && target.accept[j]) {
                        node.allowed[i] = true;
                        break;
                    }
                }
            }
        }
        return new Network(nodes);
    }

    private static void discoverWire(BlockPos pos, Map<Long, Integer> discovered, ArrayDeque<BlockPos> queue) {
        long encoded = pos.asLong();
        if (discovered.containsKey(encoded)) {
            return;
        }
        discovered.put(encoded, -1);
        queue.addLast(pos.immutable());
    }

    private static void addConnection(Node node, BlockPos pos, boolean offer, boolean accept) {
        long encoded = pos.asLong();
        for (int i = 0; i < node.targetPositions.length; i++) {
            if (node.targetPositions[i] == encoded) {
                node.offer[i] |= offer;
                node.accept[i] |= accept;
                return;
            }
        }
        for (int i = 0; i < node.targetPositions.length; i++) {
            if (node.targetPositions[i] == Long.MIN_VALUE) {
                node.targetPositions[i] = encoded;
                node.offer[i] = offer;
                node.accept[i] = accept;
                return;
            }
        }
    }

    private static int[] spreadPower(Network network) {
        final long profileStart = System.nanoTime();
        try {
            return spreadPowerImpl(network);
        } finally {
            NephritisProfiler.record(NephritisProfiler.Bucket.REDSTONE_BFS, profileStart);
        }
    }

    private static int[] spreadPowerImpl(Network network) {
        int n = network.nodes.size();
        if (n >= bfsMin && bfsEnabled && NalimBridge.isAvailable()) {
            double[] request = new double[n * NATIVE_STRIDE];
            double[] result = new double[n];
            for (int i = 0; i < n; i++) {
                request[i * NATIVE_STRIDE] = network.externalPower[i];
                Node node = network.nodes.get(i);
                for (int j = 0; j < MAX_CONNECTIONS; j++) {
                    request[i * NATIVE_STRIDE + 1 + j] = node.allowed[j] && node.targets[j] >= 0
                        ? node.targets[j] : -1;
                }
            }
            try {
                NalimBridge.computeRedstoneBfs(request, result, n);
                int[] powers = new int[n];
                for (int i = 0; i < n; i++) {
                    powers[i] = clampPower((int) Math.round(result[i]));
                }
                return powers;
            } catch (Throwable throwable) {
                NalimBridge.disable("native redstone BFS failed; falling back to Java", throwable);
            }
        }

        int[] powers = Arrays.copyOf(network.externalPower, n);
        int[] queue = new int[Math.max(16, n * 2)];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (powers[i] > 0) {
                if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int nextPower = powers[i] - 1;
            if (nextPower <= 0) continue;
            Node node = network.nodes.get(i);
            for (int j = 0; j < MAX_CONNECTIONS; j++) {
                int neighbor = node.targets[j];
                if (!node.allowed[j] || neighbor < 0 || nextPower <= powers[neighbor]) continue;
                powers[neighbor] = nextPower;
                if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
                queue[tail++] = neighbor;
            }
        }
        return powers;
    }

    private static void applyPowers(ServerLevel level, Network network, int[] powers) {
        ArrayList<BlockPos> changed = new ArrayList<>();
        for (int i = 0; i < network.nodes.size(); i++) {
            Node node = network.nodes.get(i);
            BlockState current = level.getBlockState(node.pos);
            if (!(current.getBlock() instanceof RedStoneWireBlock)) {
                continue;
            }
            int originalPower = node.state.getValue(RedStoneWireBlock.POWER);
            int newPower = powers[i];
            BlockState target = current.setValue(RedStoneWireBlock.POWER, newPower);
            boolean changedPower = originalPower != newPower;
            writeWireState(level, node.pos, target, false);
            if (changedPower) {
                // The section was temporarily zeroed before evaluation, so the
                // notification decision must use the original power value rather
                // than comparing against the already-zeroed section.
                level.getChunkSource().blockChanged(node.pos);
                LevelChunk chunk = level.getChunkAt(node.pos);
                chunk.setUnsaved(true);
                changed.add(node.pos);
            }
        }

        for (BlockPos pos : changed) {
            level.updateNeighborsAt(pos, Blocks.REDSTONE_WIRE);
        }
    }

    private static void restoreOriginalPowers(ServerLevel level, Network network) {
        for (Node node : network.nodes) {
            BlockState current = level.getBlockState(node.pos);
            if (current.getBlock() instanceof RedStoneWireBlock) {
                writeWireState(level, node.pos, node.state, false);
            }
        }
    }

    private static void writeWireState(ServerLevel level, BlockPos pos, BlockState state, boolean notifyClients) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(pos);
        LevelChunkSection[] sections = chunk.getSections();
        int sectionIndex = level.getSectionIndex(pos.getY());
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return;
        }
        LevelChunkSection section = sections[sectionIndex];
        BlockState old = section.setBlockState(
            pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        if (notifyClients && !old.equals(state)) {
            level.getChunkSource().blockChanged(pos);
            chunk.setUnsaved(true);
        }
    }

    private static boolean shouldSampleOracle() {
        return ORACLE_ENABLED && !oracleTripped && (oracleChecks & ORACLE_MASK) == 0L;
    }

    static void runVanillaUpdateForOracle(ServerLevel level, BlockPos pos, BlockState state) {
        boolean previous = VANILLA_BYPASS.get();
        VANILLA_BYPASS.set(true);
        try {
            ((RedStoneWireBlockAccessorMixin) (Object) Blocks.REDSTONE_WIRE)
                .nephritis$updatePowerStrength(level, pos, state);
        } finally {
            VANILLA_BYPASS.set(previous);
        }
    }

    private static int clampPower(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static int clampPositive(int value, int max) {
        return Math.max(1, Math.min(max, value));
    }

    private static void callVanillaUpdate(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof RedStoneWireBlock)) {
            return;
        }
        boolean previous = VANILLA_BYPASS.get();
        VANILLA_BYPASS.set(true);
        try {
            ((RedStoneWireBlockAccessorMixin) (Object) Blocks.REDSTONE_WIRE)
                .nephritis$updatePowerStrength(level, pos, state);
        } finally {
            VANILLA_BYPASS.set(previous);
        }
    }

    private static final Direction[] HORIZONTAL = {
        Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH
    };

    private static final class RedstoneOracle {
        private static boolean compare(ServerLevel level, BlockPos rootPos, Network network, int[] optimized) {
            try {
                restoreOriginalPowers(level, network);
                BlockState rootState = level.getBlockState(rootPos);
                if (!(rootState.getBlock() instanceof RedStoneWireBlock)) {
                    restoreOriginalPowers(level, network);
                    return false;
                }
                runVanillaUpdateForOracle(level, rootPos, rootState);

                for (int i = 0; i < network.nodes.size(); i++) {
                    BlockState state = level.getBlockState(network.nodes.get(i).pos);
                    if (!(state.getBlock() instanceof RedStoneWireBlock)) return false;
                    if (state.getValue(RedStoneWireBlock.POWER) != optimized[i]) {
                        restoreOriginalPowers(level, network);
                        return false;
                    }
                }
                restoreOriginalPowers(level, network);
                return true;
            } catch (Throwable throwable) {
                restoreOriginalPowers(level, network);
                LOGGER.warn("[redstone-oracle] vanilla shadow computation failed; treating oracle as mismatch", throwable);
                return false;
            }
        }
    }

    private static final class WorldState {
        private boolean processing;
        private final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        private final Set<Long> pendingKeys = new HashSet<>();

        private void enqueue(BlockPos pos) {
            if (pendingKeys.add(pos.asLong())) pending.addLast(pos);
        }

        private void clearPending() {
            pending.clear();
            pendingKeys.clear();
        }
    }

    private static final class Node {
        private final BlockPos pos;
        private final BlockState state;
        private final int[] targets = {-1, -1, -1, -1, -1, -1, -1, -1};
        private final long[] targetPositions = {
            Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
            Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE
        };
        private final boolean[] offer = new boolean[MAX_CONNECTIONS];
        private final boolean[] accept = new boolean[MAX_CONNECTIONS];
        private final boolean[] allowed = new boolean[MAX_CONNECTIONS];

        private Node(BlockPos pos, BlockState state) {
            this.pos = pos.immutable();
            this.state = state;
        }
    }

    private static final class Network {
        private final ArrayList<Node> nodes;
        private final int[] externalPower;

        private Network(ArrayList<Node> nodes) {
            this.nodes = nodes;
            this.externalPower = new int[nodes.size()];
        }
    }
}
