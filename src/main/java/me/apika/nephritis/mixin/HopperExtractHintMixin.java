package me.apika.nephritis.mixin;

import me.apika.nephritis.hopper.HopperExtractHint;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.stream.IntStream;


@Mixin(HopperBlockEntity.class)
public abstract class HopperExtractHintMixin {
    @Shadow
    private static IntStream getSlots(Container container, Direction direction) {
        throw new AssertionError();
    }

    @Redirect(
        method = "suckInItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getSlots(Lnet/minecraft/world/Container;Lnet/minecraft/core/Direction;)Ljava/util/stream/IntStream;"
        )
    )
    private static IntStream nephritis$reorderSourceSlots(Container container, Direction direction) {
        return HopperExtractHint.reorder(
            container,
            direction,
            getSlots(container, direction)
        );
    }
}
