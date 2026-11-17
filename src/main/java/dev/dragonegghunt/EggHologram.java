package dev.dragonegghunt;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ManualAttachment;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Floating text above a placed egg: how many players are in the zone, whether the egg can be taken,
 * and the click progress of the player who is taking it.
 * <p>
 * It's a virtual text display: it only exists in the packets sent to players near the egg, never in the world.
 * By default it's drawn in front of blocks (display.hologram-through-blocks), so it's never cut off.
 * Players who turned the zone off with /dragonegg show-zone off don't see it, except during an alert
 * (someone clicked the egg or players fought in its zone).
 */
public final class EggHologram {

    /** Bottom of the text above the bottom of the egg block. */
    private static final double HEIGHT = 1.6;
    private static final int BAR_SEGMENTS = 10;

    private final DragonEggHunt mod;
    private final Layer inFront = new Layer(true);
    private final Layer behindBlocks = new Layer(false);

    public EggHologram(DragonEggHunt mod) {
        this.mod = mod;
    }

    /**
     * Shows the text above {@code egg} to the right players among {@code nearby}, or removes it if nobody
     * is near. {@code everyone} shows it to all of them, e.g. while someone tries to take the egg.
     */
    public void update(ServerLevel level, EggLocation egg, List<ServerPlayer> nearby, boolean everyone) {
        if (nearby.isEmpty() || !egg.isLoaded(level)) {
            remove();
            return;
        }
        boolean throughBlocks = mod.settings().hologramThroughBlocks();
        List<ServerPlayer> inFrontViewers = new ArrayList<>();
        List<ServerPlayer> behindBlocksViewers = new ArrayList<>();
        for (ServerPlayer viewer : nearby) {
            if (everyone || mod.state().wantsZone(viewer.getUUID())) {
                (throughBlocks ? inFrontViewers : behindBlocksViewers).add(viewer);
            }
        }
        Component text = buildText(level, egg);
        inFront.show(level, egg, inFrontViewers, text);
        behindBlocks.show(level, egg, behindBlocksViewers, text);
    }

    /** Updates the text right away, e.g. after a click. */
    public void refreshText(EggLocation egg) {
        ServerLevel level = egg.level(mod.server());
        if (level == null) {
            return;
        }
        Component text = buildText(level, egg);
        inFront.setText(egg, text);
        behindBlocks.setText(egg, text);
    }

    public void remove() {
        inFront.remove();
        behindBlocks.remove();
    }

    /** The player's client dropped the text (respawn, other dimension), so it's sent again on the next update. */
    public void forget(ServerPlayer player) {
        inFront.forget(player);
        behindBlocks.forget(player);
    }

    /** One text display and the players it's shown to. */
    private final class Layer {

        private final boolean seeThrough;
        private @Nullable ElementHolder holder;
        private @Nullable TextDisplayElement display;
        private @Nullable EggLocation displayEgg;
        private @Nullable Component lastText;

        Layer(boolean seeThrough) {
            this.seeThrough = seeThrough;
        }

        void show(ServerLevel level, EggLocation egg, List<ServerPlayer> viewers, Component text) {
            if (viewers.isEmpty()) {
                remove();
                return;
            }
            ElementHolder current = ensureCreated(level, egg);
            setText(egg, text);
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

        void setText(EggLocation egg, Component text) {
            if (holder == null || display == null || !egg.equals(displayEgg) || text.equals(lastText)) {
                return;
            }
            display.setText(text);
            lastText = text;
            // sends the changed text to everyone watching
            holder.tick();
        }

        void forget(ServerPlayer player) {
            if (holder != null) {
                holder.stopWatching(player);
            }
        }

        void remove() {
            if (holder != null) {
                holder.destroy();
            }
            holder = null;
            display = null;
            displayEgg = null;
            lastText = null;
        }

        private ElementHolder ensureCreated(ServerLevel level, EggLocation egg) {
            if (holder != null && egg.equals(displayEgg)) {
                return holder;
            }
            remove();
            boolean background = mod.settings().hologramBackground();
            TextDisplayElement text = new TextDisplayElement();
            text.setBillboardMode(Display.BillboardConstraints.CENTER);
            text.setTextAlignment(Display.TextDisplay.Align.CENTER);
            text.setSeeThrough(seeThrough);
            // always fully lit, also at night or in a cave
            text.setBrightness(new Brightness(15, 15));
            text.setShadow(true);
            text.setDefaultBackground(background);
            if (!background) {
                // Fully transparent: the client then skips the box entirely. Any visible box would hide
                // water and glass behind it, because it's drawn before them and covers them in the depth buffer.
                text.setBackground(0);
            }
            ElementHolder created = new ElementHolder();
            created.addElement(text);
            Vec3 position = Vec3.atBottomCenterOf(egg.pos()).add(0, HEIGHT, 0);
            new ManualAttachment(created, level, () -> position);
            holder = created;
            display = text;
            displayEgg = egg;
            return created;
        }
    }

    private Component buildText(ServerLevel level, EggLocation egg) {
        Settings settings = mod.settings();
        PickupProgress pickup = mod.pickupProgress();
        ServerPlayer alone = null;
        int inZone = 0;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && pickup.inZone(egg, player)) {
                inZone++;
                alone = player;
            }
        }

        Component status;
        if (inZone == 0) {
            status = settings.message("hologram-empty");
        } else if (inZone > 1) {
            status = settings.message("hologram-crowded", "players", Integer.toString(inZone));
        } else {
            int clicks = pickup.clicks(alone, egg);
            if (clicks > 0) {
                int filled = Math.min(BAR_SEGMENTS, clicks * BAR_SEGMENTS / settings.pickupClicks());
                String bar = "<light_purple>" + "■".repeat(filled) + "</light_purple><dark_gray>"
                        + "■".repeat(BAR_SEGMENTS - filled) + "</dark_gray>";
                status = settings.message("hologram-progress",
                        "player", alone.getName().getString(),
                        "bar", bar,
                        "clicks", Integer.toString(clicks),
                        "required", Integer.toString(settings.pickupClicks()));
            } else {
                status = settings.message("hologram-alone",
                        "player", alone.getName().getString(),
                        "clicks", Integer.toString(settings.pickupClicks()));
            }
        }
        // As siblings, not children of the title, so the status doesn't inherit the title's bold style
        return Component.empty().append(settings.message("hologram-title")).append("\n").append(status);
    }
}
