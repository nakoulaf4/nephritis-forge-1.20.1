package me.apika.nephritis.mixin;

import me.apika.nephritis.hopper.HopperHighway;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(HopperBlockEntity.class)
public abstract class HopperHighwayMixin {
    @Inject(
        method = "pushItemsTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void nephritis$highway(Level level, BlockPos pos, BlockState state,
                                         HopperBlockEntity hopper, CallbackInfo ci) {
        if (HopperHighway.isEnabled()) {
            HopperHighway.tick(level, pos, state, hopper);
            ci.cancel();
        }
    }
}
