package me.apika.nephritis.mixin;

import me.apika.nephritis.redstone.RedstoneOptimizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {
    @Inject(method = "updatePowerStrength", at = @At("HEAD"), cancellable = true)
    private void nephritis$optimizedUpdate(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (RedstoneOptimizer.isVanillaBypass()) {
            return;
        }
        if (RedstoneOptimizer.isEnabled() && RedstoneOptimizer.handle(serverLevel, pos, state)) {
            ci.cancel();
        }
    }
}
