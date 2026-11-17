package dev.dragonegghunt;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ManualAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes a placed egg glow purple, like the player carrying it, so it can be seen through blocks.
 * <p>
 * Blocks can't glow, only entities can, so a virtual block display of the egg sits inside the real egg, a bit
 * smaller so the real egg covers it, and glows. It has no hitbox, so clicks still reach the egg. It only exists
 * in the packets sent to the players the tracker picks (the same ones who see the zone).
 */
public final class EggGlow {

    /** Same color as the glow of the player carrying the egg. */
    private static final int GLOW_COLOR = 0xFF55FF;
    private static final float INSET = 0.02f;

    private @Nullable ElementHolder holder;
    private @Nullable EggLocation displayEgg;

    /** Shows the glow to exactly {@code viewers}, or removes it if there are none. */
    public void update(ServerLevel level, EggLocation egg, List<ServerPlayer> viewers) {
        if (viewers.isEmpty() || !egg.isLoaded(level)) {
            remove();
            return;
        }
        ElementHolder current = ensureCreated(level, egg);
        Set<ServerGamePacketListenerImpl> wanted = new HashSet<>();
        for (ServerPlayer viewer : viewers) {
            wanted.add(viewer.connection);
            current.startWatching(viewer);
        }
        for (ServerGamePacketListenerImpl watching : List.copyOf(current.getWatchingPlayers())) {
            if (!wanted.contains(watching)) {
                current.stopWatching(watching);
            }
        }
    }

    public void remove() {
        if (holder != null) {
            holder.destroy();
        }
        holder = null;
        displayEgg = null;
    }

    /** The player's client dropped the glow (respawn, other dimension), so it's sent again on the next update. */
    public void forget(ServerPlayer player) {
        if (holder != null) {
            holder.stopWatching(player);
        }
    }

    private ElementHolder ensureCreated(ServerLevel level, EggLocation egg) {
        if (holder != null && egg.equals(displayEgg)) {
            return holder;
        }
        remove();
        // slightly smaller than the real egg and centered in it, so the real egg is drawn on top
        float scale = 1 - 2 * INSET;
        BlockDisplayElement glow = new BlockDisplayElement(Blocks.DRAGON_EGG.defaultBlockState());
        glow.setTranslation(new Vector3f(INSET, INSET, INSET));
        glow.setScale(new Vector3f(scale, scale, scale));
        glow.setGlowColorOverride(GLOW_COLOR);
        glow.setGlowing(true);
        ElementHolder created = new ElementHolder();
        created.addElement(glow);
        Vec3 corner = Vec3.atLowerCornerOf(egg.pos());
        new ManualAttachment(created, level, () -> corner);
        holder = created;
        displayEgg = egg;
        return created;
    }
}
