package me.apika.nephritis.mixin;

import me.apika.nephritis.ticker.BlockEntityTickerGate.AbstractFurnaceBlockEntityAccessor;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessorMixin extends AbstractFurnaceBlockEntityAccessor {
    @Override
    @Accessor("litTime")
    int nephritis$getLitTimeRemaining();

    @Override
    @Accessor("cookingProgress")
    int nephritis$getCookingProgress();
}
