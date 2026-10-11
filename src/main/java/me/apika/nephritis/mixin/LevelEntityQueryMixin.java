package me.apika.nephritis.mixin;

import me.apika.nephritis.entity.EntityQueryIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;


@Mixin(Level.class)
public abstract class LevelEntityQueryMixin {
    @Inject(
        method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void nephritis$entityQueryIndex(
        @Nullable Entity except,
        AABB bounds,
        Predicate<? super Entity> predicate,
        CallbackInfoReturnable<List<Entity>> cir
    ) {
        if (EntityQueryIndex.isBypass()) return;
        Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel level)) return;
        List<Entity> result = EntityQueryIndex.query(level, except, bounds, predicate);
        if (result != null) cir.setReturnValue(result);
    }

    @Inject(
        method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private <T extends Entity> void nephritis$typedEntityQueryIndex(
        EntityTypeTest<Entity, T> typeTest,
        AABB bounds,
        Predicate<? super T> predicate,
        CallbackInfoReturnable<List<T>> cir
    ) {
        if (EntityQueryIndex.isBypass()) return;
        Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel level)) return;
        List<T> result = EntityQueryIndex.queryTyped(level, typeTest, bounds, predicate);
        if (result != null) cir.setReturnValue(result);
    }
}
