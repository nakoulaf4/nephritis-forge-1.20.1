package me.apika.nephritis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RedStoneWireBlock.class)
public interface RedStoneWireBlockAccessorMixin {
    @Invoker("calculateTargetStrength")
    int nephritis$calculateTargetStrength(Level level, BlockPos pos);

    @Invoker("updatePowerStrength")
    void nephritis$updatePowerStrength(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state);
}
