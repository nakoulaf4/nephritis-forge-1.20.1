package me.apika.nephritis.mixin;

import me.apika.nephritis.entity.ColliderSkip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Wires {@link ColliderSkip} into the movement collision path.
 *
 * In Minecraft 1.20.1 the entity collision query is the default method
 * EntityGetter#getEntityCollisions(Entity, AABB) (Level does not override it),
 * so a plain @Inject into Level is not possible. Instead we redirect the
 * single call site inside Entity#collide(Vec3) and return an empty shape list
 * when the spatial index proves there is no collidable entity nearby.
 *
 * ColliderSkip only ever returns true when it can prove emptiness; otherwise
 * the vanilla call is executed unchanged.
 */
@Mixin(Entity.class)
public abstract class EntityCollideSkipMixin {

    @Redirect(
        method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
        )
    )
    private List<VoxelShape> nephritis$skipEmptyEntityCollisions(
        Level level, Entity source, AABB box
    ) {
        if (ColliderSkip.shouldSkip(source, box)) {
            return List.of();
        }
        return level.getEntityCollisions(source, box);
    }
}
