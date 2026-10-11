package me.apika.nephritis.hopper;

import me.apika.nephritis.Nephritis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;


public final class HopperHighway {
    private static final String PROPERTY = "nephritis.hopper.highway";
    private static final boolean DEFAULT_ENABLED = false;
    private static final boolean IMPLEMENTED = false;
    private static final int SLOT_COUNT = 5;
    private static final int COOLDOWN = 8;

    private static volatile boolean enabled = readInitialState();
    private static final Map<HopperBlockEntity, State> STATES = new WeakHashMap<>();

    private HopperHighway() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (value && !IMPLEMENTED) {
            enabled = false;
            Nephritis.LOGGER.warn("Nephritis hopper highway is disabled on Forge 1.20.1 until the exact vanilla + Forge sided-transfer path is ported.");
            return;
        }
        enabled = value;
        Nephritis.LOGGER.info("Nephritis hopper highway {}", value ? "enabled" : "disabled");
    }


    public static boolean tick(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper) {
        if (!IMPLEMENTED || !enabled || level.isClientSide || !state.is(net.minecraft.world.level.block.Blocks.HOPPER)) {
            return false;
        }
        if (!state.getValue(HopperBlock.ENABLED)) {
            return true;
        }

        State highwayState = stateFor(hopper);
        highwayState.tickCooldowns();

        // Exactly one successful transfer is permitted per game tick.
        if (tryEject(level, pos, state, hopper, highwayState)) {
            highwayState.markTransfer();
            return true;
        }

        // Fall back to Mojang's own source-item code for pickup semantics.
        // suckInItems handles item entities, sided inventories, Forge hooks,
        // double chests and the normal one-item extraction contract.
        if (!inventoryFull(hopper) && HopperBlockEntity.suckInItems(level, hopper)) {
            highwayState.markTransfer();
        }

        return true;
    }

    private static boolean tryEject(Level level, BlockPos pos, BlockState state,
                                     HopperBlockEntity hopper, State highwayState) {
        Direction direction = state.getValue(HopperBlock.FACING);
        BlockPos destinationPos = pos.relative(direction);
        Container destination = HopperBlockEntity.getContainerAt(level, destinationPos);
        if (destination == null) {
            return false;
        }

        for (int pass = 0; pass < SLOT_COUNT; pass++) {
            int slot = (highwayState.cursor + pass) % SLOT_COUNT;
            if (highwayState.cooldowns[slot] > 0) {
                continue;
            }

            ItemStack original = hopper.getItem(slot);
            if (original.isEmpty()) {
                continue;
            }

            // Pass exactly one item through the same public transfer primitive
            // vanilla uses internally. This keeps sided-inventory behavior and
            // Forge's inventory integration on the vanilla path.
            ItemStack one = original.copy();
            one.setCount(1);
            ItemStack remainder = HopperBlockEntity.addItem(hopper, destination, one, direction);
            if (remainder.isEmpty()) {
                hopper.removeItem(slot, 1);
                highwayState.cooldowns[slot] = COOLDOWN;
                highwayState.cursor = (slot + 1) % SLOT_COUNT;
                return true;
            }
        }
        return false;
    }

    private static boolean inventoryFull(HopperBlockEntity hopper) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || stack.getCount() < Math.min(hopper.getMaxStackSize(), stack.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    private static State stateFor(HopperBlockEntity hopper) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(hopper, ignored -> new State());
        }
    }

    private static boolean readInitialState() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, Boolean.toString(DEFAULT_ENABLED)));
    }

    private static final class State {
        private final int[] cooldowns = new int[SLOT_COUNT];
        private int cursor;

        private void tickCooldowns() {
            for (int i = 0; i < cooldowns.length; i++) {
                if (cooldowns[i] > 0) {
                    cooldowns[i]--;
                }
            }
        }

        private void markTransfer() {
            // No shared cooldown: individual slots retain their own 8-tick cadence.
        }
    }
}
