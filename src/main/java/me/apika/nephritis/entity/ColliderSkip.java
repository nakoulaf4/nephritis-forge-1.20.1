package me.apika.nephritis.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.AABB;


public final class ColliderSkip {
    private static final String PROPERTY = "nephritis.entityquery.colliderskip";
    private static final boolean DEFAULT_ENABLED = true;
    private static volatile boolean enabled = readInitialState();

    private ColliderSkip() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean shouldSkip(Entity source, AABB collisionBox) {
        if (!enabled || source == null || collisionBox == null || source.level().isClientSide) return false;
        if (!(source.level() instanceof ServerLevel level)) return false;
        // Vanilla/Forge have extra collision behavior around these vehicle types;
        // keep their path conservative until a dedicated vehicle oracle exists.
        if (source instanceof Boat || source instanceof AbstractMinecart) return false;
        return EntityQueryIndex.isProvablyEmptyForCollision(level, source, collisionBox);
    }

    private static boolean readInitialState() {
        String value = System.getProperty(PROPERTY);
        return value == null ? DEFAULT_ENABLED : Boolean.parseBoolean(value);
    }
}
