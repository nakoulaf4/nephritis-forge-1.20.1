package me.apika.nephritis.mixin;

import me.apika.nephritis.entity.EntityQueryIndex;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Entity.class)
public abstract class EntitySpatialIndexMovementMixin {
    @Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"))
    private void nephritis$updateQueryIndex(double x, double y, double z, CallbackInfo ci) {
        EntityQueryIndex.onEntityMoved((Entity) (Object) this);
    }

    @Inject(method = "refreshDimensions()V", at = @At("TAIL"))
    private void nephritis$updateQueryIndexDimensions(CallbackInfo ci) {
        EntityQueryIndex.onEntityMoved((Entity) (Object) this);
    }
}
