package me.apika.nephritis.mixin;

import me.apika.nephritis.entity.CrammingDispatcher;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "pushEntities()V", at = @At("HEAD"), cancellable = true)
    private void nephritis$batchCramming(CallbackInfo ci) {
        Object self = this;
        if (self instanceof Mob mob && CrammingDispatcher.handle(mob)) {
            ci.cancel();
        }
    }
}
