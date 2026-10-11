package me.apika.nephritis.mixin;

import me.apika.nephritis.ticker.BlockEntityTickerGate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelChunk.class)
public abstract class LevelChunkTickerGateMixin {
    @Redirect(
        method = "updateBlockEntityTicker(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getTicker(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/BlockEntityType;)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;"
        )
    )
    private <T extends BlockEntity> BlockEntityTicker<T> nephritis$gateTicker(
        BlockState state, Level level, BlockEntityType<T> type, BlockEntity blockEntity) {
        if (BlockEntityTickerGate.shouldSuppress(level, blockEntity)) {
            return null;
        }
        return state.getTicker(level, type);
    }
}
