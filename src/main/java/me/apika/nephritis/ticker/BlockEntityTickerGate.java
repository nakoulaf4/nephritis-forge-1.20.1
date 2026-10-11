package me.apika.nephritis.ticker;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;


public final class BlockEntityTickerGate {
    private BlockEntityTickerGate() {}

    public static boolean shouldSuppress(Level level, BlockEntity blockEntity) {
        if (level.isClientSide) return false;
        Class<?> type = blockEntity.getClass();

        if (type == SignBlockEntity.class || type == HangingSignBlockEntity.class) {
            return ((SignBlockEntity) blockEntity).getPlayerWhoMayEdit() == null;
        }

        if (type == FurnaceBlockEntity.class
            || type == BlastFurnaceBlockEntity.class
            || type == SmokerBlockEntity.class) {
            AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
            AbstractFurnaceBlockEntityAccessor accessor = (AbstractFurnaceBlockEntityAccessor) furnace;
            return accessor.nephritis$getLitTimeRemaining() == 0
                && accessor.nephritis$getCookingProgress() == 0
                && furnace.getItem(0).isEmpty()
                && furnace.getItem(1).isEmpty();
        }

        return false;
    }

    public interface AbstractFurnaceBlockEntityAccessor {
        int nephritis$getLitTimeRemaining();
        int nephritis$getCookingProgress();
    }
}
