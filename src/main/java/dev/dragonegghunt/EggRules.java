package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Small helpers around the egg item and where a dropped egg can safely be placed.
 */
public final class EggRules {

    private static final int NEARBY_RADIUS = 8;
    private static final int HOTBAR_SIZE = 9;

    private EggRules() {
    }

    public static boolean isEgg(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.is(Items.DRAGON_EGG);
    }

    public static boolean isEgg(BlockState state) {
        return state.is(Blocks.DRAGON_EGG);
    }

    public static boolean isBundle(@Nullable ItemStack item) {
        return item != null && !item.isEmpty()
                && BuiltInRegistries.ITEM.getKey(item.getItem()).getPath().endsWith("bundle");
    }

    /** Blocks that take the held item on right-click: decorated pots and shelves. */
    public static boolean storesItemsOnUse(BlockState state) {
        return state.is(Blocks.DECORATED_POT) || isShelf(state);
    }

    /** Shelves can also swap the items of the whole hotbar row. */
    public static boolean isShelf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_shelf");
    }

    public static boolean hasEgg(ServerPlayer player) {
        if (isEgg(player.containerMenu.getCarried()) || isEgg(player.inventoryMenu.getCarried())) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isEgg(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hotbarHasEgg(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (isEgg(inventory.getItem(slot))) {
                return true;
            }
        }
        return isEgg(player.getOffhandItem());
    }

    /** Removes every egg from the player's inventory and cursor. Returns how many were removed. */
    public static int removeEggs(ServerPlayer player) {
        int removed = 0;
        if (isEgg(player.containerMenu.getCarried())) {
            removed += player.containerMenu.getCarried().getCount();
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        if (player.inventoryMenu != player.containerMenu && isEgg(player.inventoryMenu.getCarried())) {
            removed += player.inventoryMenu.getCarried().getCount();
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isEgg(item)) {
                removed += item.getCount();
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        return removed;
    }

    /**
     * Finds a block near {@code origin} where the egg can stand: free space with solid ground below.
     * Tries straight down first (players flying or falling), then up (players inside blocks or lava),
     * then the surface of that column, then the surface of the columns around it, and only then the world spawn.
     */
    public static BlockPos findSafeSpot(ServerLevel level, Vec3 origin) {
        int min = level.getMinY();
        int max = level.getMaxY();
        int x = Mth.floor(origin.x);
        int z = Mth.floor(origin.z);
        int y = Math.max(min + 1, Math.min(max, Mth.floor(origin.y)));

        BlockPos spot = scanDown(level, new BlockPos(x, y, z), min);
        if (spot == null) {
            spot = scanUp(level, x, y, z, max);
        }
        if (spot == null) {
            spot = onSurface(level, x, z, max);
        }
        if (spot == null) {
            // e.g. a torch or a sign on top of this column
            spot = nearbySurface(level, x, z, max);
        }
        if (spot == null) {
            BlockPos spawn = DragonEggHunt.spawnPos(level);
            spot = onSurface(level, spawn.getX(), spawn.getZ(), max);
            if (spot == null) {
                spot = spawn;
            }
        }
        return spot;
    }

    private static @Nullable BlockPos scanDown(ServerLevel level, BlockPos start, int min) {
        if (!isFree(level, start)) {
            return null;
        }
        BlockPos current = start;
        while (current.getY() > min) {
            BlockPos below = current.below();
            if (isFree(level, below)) {
                current = below;
                continue;
            }
            return isSupport(level, below) ? current : null;
        }
        return null;
    }

    private static @Nullable BlockPos scanUp(ServerLevel level, int x, int y, int z, int max) {
        for (int current = y; current < max; current++) {
            BlockPos pos = new BlockPos(x, current, z);
            if (isFree(level, pos) && isSupport(level, pos.below())) {
                return pos;
            }
        }
        return null;
    }

    private static @Nullable BlockPos onSurface(ServerLevel level, int x, int z, int max) {
        // the heightmap gives the first free block above the highest block that blocks motion
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        if (top.getY() < level.getMinY() || !isSupport(level, top) || top.getY() >= max) {
            return null;
        }
        BlockPos above = top.above();
        return isFree(level, above) ? above : null;
    }

    /** The surface of the closest column around (x, z) that has room for the egg, ring by ring. */
    private static @Nullable BlockPos nearbySurface(ServerLevel level, int x, int z, int max) {
        for (int ring = 1; ring <= NEARBY_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos spot = onSurface(level, x + dx, z + dz, max);
                    if (spot != null) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.canBeReplaced() && !state.is(Blocks.LAVA));
    }

    /** The egg can stand on its top face (full blocks, top slabs, upside-down stairs ...). */
    private static boolean isSupport(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }
}
