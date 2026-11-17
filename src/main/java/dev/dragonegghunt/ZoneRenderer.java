package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws the pickup zone, a sphere around the egg, with particle lines:
 * <ul>
 *   <li>Ground line: where the terrain crosses the sphere. On flat ground it's a circle at the full radius;
 *   on a hill it moves inwards, in a dip outwards. Cave floors that cross the sphere get their own line.</li>
 *   <li>Height rings: horizontal circles of the sphere at a few heights, so players can see how high the zone
 *   reaches, e.g. when pillaring up or flying. Parts inside blocks or right above the ground are left out,
 *   where the ground line already shows the edge.</li>
 * </ul>
 * Both are computed once for all viewers and cached for a few seconds.
 */
public final class ZoneRenderer {

    // ground line
    private static final double ANGLE_SPACING = 1.0;
    private static final double ARC_STEP = 0.7;
    private static final float LINE_SIZE = 1.7f;
    private static final double LIFT = 0.15;

    // height rings
    private static final double RING_DOT_SPACING = 1.0;
    private static final float RING_SIZE = 1.3f;

    private static final int CACHE_TICKS = 60;

    private record Point(double x, double y, double z) {
    }

    private @Nullable EggLocation cachedEgg;
    private int cachedRadius = -1;
    private int cachedRings = -1;
    private int cachedAtTick;
    private List<Point> groundLine = List.of();
    private List<Point> rings = List.of();

    public void render(ServerPlayer viewer, ServerLevel level, EggLocation egg, Settings settings, int now) {
        int radius = settings.pickupRadius();
        if (radius <= 0) {
            return;
        }
        refresh(level, egg, radius, settings.zoneHeightRings(), now);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (settings.zoneOutline()) {
            add(packets, groundLine, new DustParticleOptions(settings.zoneColor(), LINE_SIZE), LIFT);
        }
        add(packets, rings, new DustParticleOptions(settings.zoneColor(), RING_SIZE), 0);
        if (!packets.isEmpty()) {
            // one bundle instead of hundreds of single packets
            viewer.connection.send(new ClientboundBundlePacket(packets));
        }
    }

    public void invalidate() {
        cachedEgg = null;
    }

    private static void add(List<Packet<? super ClientGamePacketListener>> packets, List<Point> points,
                            DustParticleOptions dust, double lift) {
        for (Point point : points) {
            // overrideLimiter: also shown to players more than 32 blocks away (the far side of the zone).
            // alwaysShow: not thinned out by the client's "decreased" particle setting, it's game information.
            packets.add(new ClientboundLevelParticlesPacket(dust, true, true,
                    point.x(), point.y() + lift, point.z(), 0f, 0f, 0f, 0f, 1));
        }
    }

    private void refresh(ServerLevel level, EggLocation egg, int radius, int ringCount, int now) {
        if (egg.equals(cachedEgg) && cachedRadius == radius && cachedRings == ringCount
                && now - cachedAtTick < CACHE_TICKS) {
            return;
        }
        cachedEgg = egg;
        cachedRadius = radius;
        cachedRings = ringCount;
        cachedAtTick = now;
        groundLine = scanGround(level, egg, radius);
        rings = ringCount > 0 ? scanRings(level, egg, radius, ringCount) : List.of();
    }

    /**
     * {@code ringCount} rings above the egg's height, evenly spread over the sphere's surface, and the same below
     * it (only visible in caves or when the egg sits on a cliff). The middle ring is included too, for eggs on a
     * hill or a tower where the widest part of the sphere is in the air.
     */
    private static List<Point> scanRings(ServerLevel level, EggLocation egg, int radius, int ringCount) {
        Vec3 center = egg.center();
        double latitudeStep = (Math.PI / 2) / (ringCount + 1);
        List<Point> points = new ArrayList<>();
        for (int k = -ringCount; k <= ringCount; k++) {
            double latitude = k * latitudeStep;
            double y = center.y + radius * Math.sin(latitude);
            double ring = radius * Math.cos(latitude);
            int dots = (int) Math.ceil(2 * Math.PI * ring / RING_DOT_SPACING);
            for (int i = 0; i < dots; i++) {
                double angle = 2 * Math.PI * i / dots;
                double x = center.x + ring * Math.cos(angle);
                double z = center.z + ring * Math.sin(angle);
                if (isOpenAboveGround(level, x, y, z)) {
                    points.add(new Point(x, y, z));
                }
            }
        }
        return points;
    }

    /** In air (or water, plants) and at least a block above the ground. Never loads chunks. */
    private static boolean isOpenAboveGround(ServerLevel level, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        if (blockY <= level.getMinY() || blockY > level.getMaxY() || !EggLocation.isLoaded(level, blockX, blockZ)) {
            return false;
        }
        return isPassable(level, new BlockPos(blockX, blockY, blockZ))
                && isPassable(level, new BlockPos(blockX, (int) Math.floor(y - 1.0), blockZ));
    }

    /**
     * For every direction, walks down the sphere surface from its top to its bottom and marks each spot where it
     * goes from open air into ground a player could stand on (or onto a water surface). That's exactly where the
     * terrain crosses the sphere: further in on hills, further out in dips, plus cave floors below the egg.
     */
    private static List<Point> scanGround(ServerLevel level, EggLocation egg, int radius) {
        Vec3 center = egg.center();
        int minY = level.getMinY();
        int maxY = level.getMaxY() - 1;
        int directions = (int) Math.ceil(2 * Math.PI * radius / ANGLE_SPACING);
        int steps = (int) Math.ceil(Math.PI * radius / ARC_STEP);
        Set<Long> seen = new HashSet<>();
        List<Point> points = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < directions; i++) {
            double angle = 2 * Math.PI * i / directions;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            boolean previousOpen = false;
            for (int k = 0; k <= steps; k++) {
                double polar = Math.PI * k / steps;
                double x = center.x + radius * Math.sin(polar) * cos;
                double y = center.y + radius * Math.cos(polar);
                double z = center.z + radius * Math.sin(polar) * sin;
                int blockX = (int) Math.floor(x);
                int blockY = (int) Math.floor(y);
                int blockZ = (int) Math.floor(z);
                if (blockY < minY || blockY > maxY || !EggLocation.isLoaded(level, blockX, blockZ)) {
                    // never load chunks just for the outline
                    previousOpen = false;
                    continue;
                }
                cursor.set(blockX, blockY, blockZ);
                boolean open = isPassable(level, cursor) && !isLiquid(level.getBlockState(cursor));
                if (previousOpen && !open) {
                    double top = standableTop(level, cursor.immutable());
                    // one point per half block, the walks of neighbouring directions overlap near the poles
                    long key = (Math.round(x * 2) << 40) ^ (Math.round(z * 2) << 20) ^ (long) (top * 10);
                    if (!Double.isNaN(top) && seen.add(key)) {
                        points.add(new Point(x, top, z));
                    }
                }
                previousOpen = open;
            }
        }
        return points;
    }

    /**
     * The surface height of a block a player could stand on (or a water surface), NaN for walls and ceilings.
     * Grass, flowers and snow layers on top count as surface, so the line isn't hidden inside them.
     */
    private static double standableTop(ServerLevel level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        if (!isPassable(level, abovePos) || isLiquid(above)) {
            return Double.NaN;
        }
        BlockState block = level.getBlockState(pos);
        double top;
        if (isLiquid(block)) {
            top = pos.getY() + 0.9;
        } else {
            double maxY = shapeTop(level, pos, block);
            top = maxY > 0 ? pos.getY() + maxY : pos.getY() + 1;
        }
        if (!above.isAir()) {
            double plantTop = abovePos.getY() + shapeTop(level, abovePos, above);
            if (plantTop > top) {
                top = Math.min(plantTop, abovePos.getY() + 1.0);
            }
        }
        return top;
    }

    /** The top of the block's outline inside its block (0 to 1), or 0 for blocks without one. */
    private static double shapeTop(ServerLevel level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty() ? 0 : Math.max(0, shape.max(Direction.Axis.Y));
    }

    /** No collision, players can walk through it (air, grass, flowers, water ...). */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isLiquid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN);
    }
}
