package me.apika.nephritis.hopper;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.stream.IntStream;


public final class HopperExtractHint {
    private HopperExtractHint() {}

    public static IntStream reorder(Container container, Direction direction, IntStream vanillaSlots) {
        if (!(container instanceof ExtractHint hintAccess)) {
            return vanillaSlots;
        }

        int[] slots = vanillaSlots.toArray();
        if (slots.length <= 1) {
            return IntStream.of(slots);
        }

        int hint = hintAccess.nephritis$getExtractHint();
        int hintPosition = indexOf(slots, hint);
        if (hintPosition >= 0 && !container.getItem(hint).isEmpty()) {
            if (hintPosition == 0) {
                return IntStream.of(slots);
            }
            return rotate(slots, hintPosition);
        }

        // Hint is stale or uninitialized. Find the first non-empty slot in the
        // exact vanilla order and make it the next starting point.
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                hintAccess.nephritis$setExtractHint(slot);
                return i == 0 ? IntStream.of(slots) : rotate(slots, i);
            }
        }

        hintAccess.nephritis$setExtractHint(0);
        return IntStream.of(slots);
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return -1;
    }

    private static IntStream rotate(int[] values, int offset) {
        int[] rotated = new int[values.length];
        System.arraycopy(values, offset, rotated, 0, values.length - offset);
        System.arraycopy(values, 0, rotated, values.length - offset, offset);
        return IntStream.of(rotated);
    }
}
