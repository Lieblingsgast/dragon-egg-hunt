package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A block position in a dimension, e.g. where the egg is placed. */
public record EggLocation(ResourceKey<Level> dimension, BlockPos pos) {

    public EggLocation {
        pos = pos.immutable();
    }

    public static EggLocation of(ServerLevel level, BlockPos pos) {
        return new EggLocation(level.dimension(), pos);
    }

    public @Nullable ServerLevel level(MinecraftServer server) {
        return server.getLevel(dimension);
    }

    /** The middle of the block. */
    public Vec3 center() {
        return Vec3.atCenterOf(pos);
    }

    public boolean isIn(ServerLevel level) {
        return level.dimension().equals(dimension);
    }

    /** Whether the chunk with this block is loaded. Never loads it. */
    public static boolean isLoaded(ServerLevel level, int blockX, int blockZ) {
        return level.hasChunk(blockX >> 4, blockZ >> 4);
    }

    public boolean isLoaded(ServerLevel level) {
        return isLoaded(level, pos.getX(), pos.getZ());
    }
}
