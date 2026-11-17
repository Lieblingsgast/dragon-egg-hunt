package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointStyleAsset;
import net.minecraft.world.waypoints.WaypointStyleAssets;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends a custom waypoint for the egg to individual players.
 * The server's own waypoint system never knows about it, it only exists on the clients that received it.
 */
public final class LocatorBar {

    private static final UUID WAYPOINT_ID = UUID.fromString("5d3c1e0a-7b9f-4f43-9a7e-0e2f5b6c8d41");
    private static final ResourceKey<WaypointStyleAsset> EGG_STYLE =
            ResourceKey.create(WaypointStyleAssets.ROOT_ID, Identifier.fromNamespaceAndPath("dragonegghunt", "egg"));
    private static final int NO_TINT = 0xFFFFFF;

    private final Set<UUID> tracking = new HashSet<>();

    /**
     * Icon for a viewer: the egg sprite for players with the resource pack, a colored dot otherwise.
     * The sprite is drawn in its own colors, so it gets white (no tint).
     */
    public Waypoint.Icon icon(boolean hasPack, int rgb) {
        Waypoint.Icon icon = new Waypoint.Icon();
        if (hasPack) {
            icon.style = EGG_STYLE;
            icon.color = Optional.of(NO_TINT);
        } else {
            icon.color = Optional.of(rgb);
        }
        return icon;
    }

    /** Shows the exact position, used while someone carries the egg. */
    public void showPosition(ServerPlayer viewer, Vec3 target, Waypoint.Icon icon) {
        BlockPos position = BlockPos.containing(target);
        viewer.connection.send(tracking.add(viewer.getUUID())
                ? ClientboundTrackedWaypointPacket.addWaypointPosition(WAYPOINT_ID, icon, position)
                : ClientboundTrackedWaypointPacket.updateWaypointPosition(WAYPOINT_ID, icon, position));
    }

    /** Shows only the direction from the viewer to the target, used for pulses. */
    public void showDirection(ServerPlayer viewer, BlockPos target, Waypoint.Icon icon) {
        double dx = target.getX() + 0.5 - viewer.getX();
        double dz = target.getZ() + 0.5 - viewer.getZ();
        // The client turns the angle back into a direction with (-sin(angle), cos(angle)),
        // the same convention as Minecraft's yaw: 0 = south (+Z), 90 degrees = west (-X).
        float angle = (float) Math.atan2(-dx, dz);
        viewer.connection.send(tracking.add(viewer.getUUID())
                ? ClientboundTrackedWaypointPacket.addWaypointAzimuth(WAYPOINT_ID, icon, angle)
                : ClientboundTrackedWaypointPacket.updateWaypointAzimuth(WAYPOINT_ID, icon, angle));
    }

    public void hide(ServerPlayer viewer) {
        if (tracking.remove(viewer.getUUID())) {
            viewer.connection.send(ClientboundTrackedWaypointPacket.removeWaypoint(WAYPOINT_ID));
        }
    }

    public void hideAll(MinecraftServer server) {
        for (UUID id : Set.copyOf(tracking)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                hide(player);
            } else {
                tracking.remove(id);
            }
        }
    }

    /** Called on leaving: the client's waypoints end with the connection. */
    public void forget(UUID playerId) {
        tracking.remove(playerId);
    }
}
