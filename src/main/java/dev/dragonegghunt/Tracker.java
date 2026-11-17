package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs every {@link #PERIOD_TICKS} ticks and decides who sees what.
 * <ul>
 *   <li>Carried: everyone except the holder sees the holder's exact position, and the holder glows.</li>
 *   <li>Placed: every few minutes everyone sees the direction for a few seconds,
 *   and players near the egg see the zone (a line on the ground, rings for its height) plus a status text.</li>
 * </ul>
 */
public final class Tracker {

    public static final int PERIOD_TICKS = 10;
    private static final int MISSING_EGG_GRACE_RUNS = 10;
    /** The pulse sound is played this far from the player towards the egg, so it comes from the egg's direction. */
    private static final double SOUND_DISTANCE = 4.0;

    private final DragonEggHunt mod;
    private final ZoneRenderer zoneRenderer = new ZoneRenderer();

    private long ticksUntilPulse;
    private long pulseTicksLeft;
    private boolean blinkPhase;
    private int missingEggRuns;
    private long ticksUntilHelp;

    public Tracker(DragonEggHunt mod) {
        this.mod = mod;
        restartPulseTimer();
        restartHelpTimer();
    }

    /** Called whenever the egg changes hands or is placed somewhere new. */
    public void reset() {
        mod.locatorBar().hideAll(mod.server());
        mod.pickupProgress().clear();
        mod.hologram().remove();
        mod.eggGlow().remove();
        zoneRenderer.invalidate();
        pulseTicksLeft = 0;
        missingEggRuns = 0;
        restartPulseTimer();
    }

    /** Starts a pulse on the next run. Returns false if the egg isn't placed. */
    public boolean pulseNow() {
        if (mod.state().status() != EggState.Status.PLACED) {
            return false;
        }
        pulseTicksLeft = 0;
        ticksUntilPulse = 0;
        return true;
    }

    private void restartPulseTimer() {
        ticksUntilPulse = mod.settings().pulseIntervalSeconds() * 20L;
    }

    private void restartHelpTimer() {
        ticksUntilHelp = mod.settings().helpIntervalMinutes() * 60L * 20L;
    }

    public void run() {
        EggState state = mod.state();
        runHelp(state);
        switch (state.status()) {
            case CARRIED -> {
                mod.hologram().remove();
                mod.eggGlow().remove();
                runCarried(state.holder());
            }
            case PLACED -> {
                mod.holderGlow().clear();
                runPlaced(state.location());
            }
            case UNKNOWN -> {
                mod.holderGlow().clear();
                mod.hologram().remove();
                mod.eggGlow().remove();
                mod.locatorBar().hideAll(mod.server());
                findLostEgg();
            }
        }
    }

    private void runCarried(UUID holderId) {
        MinecraftServer server = mod.server();
        ServerPlayer holder = holderId == null ? null : server.getPlayerList().getPlayer(holderId);
        if (holder == null) {
            mod.holderGlow().clear();
            mod.locatorBar().hideAll(server);
            return;
        }
        if (!EggRules.hasEgg(holder)) {
            DragonEggHunt.LOGGER.warn("{} no longer has the dragon egg, its location is now unknown.",
                    holder.getName().getString());
            mod.holderGlow().clear();
            mod.state().setUnknown();
            reset();
            return;
        }
        mod.holderGlow().update(holder);
        Vec3 position = holder.position();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer == holder || !mod.state().wantsPulse(viewer.getUUID()) || viewer.level() != holder.level()) {
                mod.locatorBar().hide(viewer);
                continue;
            }
            mod.locatorBar().showPosition(viewer, position, iconFor(viewer, false));
        }
    }

    private void runPlaced(EggLocation egg) {
        if (egg == null) {
            return;
        }
        ServerLevel level = egg.level(mod.server());
        if (level == null) {
            return;
        }
        if (!checkEggStillThere(level, egg)) {
            return;
        }
        showZone(level, egg);

        if (pulseTicksLeft > 0) {
            pulseTicksLeft -= PERIOD_TICKS;
            if (pulseTicksLeft <= 0) {
                mod.locatorBar().hideAll(mod.server());
                return;
            }
            blinkPhase = !blinkPhase;
            showPulse(level, egg, false);
            return;
        }

        ticksUntilPulse -= PERIOD_TICKS;
        if (ticksUntilPulse <= 0) {
            restartPulseTimer();
            pulseTicksLeft = mod.settings().pulseDurationSeconds() * 20L;
            blinkPhase = false;
            showPulse(level, egg, true);
        }
    }

    /**
     * Players near a placed egg see the zone as particle lines (on the ground and as rings showing its height),
     * the egg glowing and a floating text above it with the number of players in the zone and the progress.
     */
    private void showZone(ServerLevel level, EggLocation egg) {
        Settings settings = mod.settings();
        PickupProgress pickup = mod.pickupProgress();
        Vec3 center = egg.center();
        double infoSquared = (double) settings.infoRadius() * settings.infoRadius();
        if (pickup.playersInZone(level, egg) > 1) {
            pickup.clear();
        }

        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer viewer : level.players()) {
            if (viewer.position().distanceToSqr(center) <= infoSquared) {
                nearby.add(viewer);
            }
        }
        // after a click on the egg or a fight in its zone, everyone near it sees the zone, also players who turned it off
        boolean alert = pickup.isAlert(egg);
        List<ServerPlayer> zoneViewers = new ArrayList<>();
        for (ServerPlayer viewer : nearby) {
            if (alert || mod.state().wantsZone(viewer.getUUID())) {
                zoneViewers.add(viewer);
                zoneRenderer.render(viewer, level, egg, settings, mod.currentTick());
            }
        }
        if (settings.eggGlow()) {
            mod.eggGlow().update(level, egg, zoneViewers);
        } else {
            mod.eggGlow().remove();
        }
        if (settings.hologram()) {
            mod.hologram().update(level, egg, nearby, alert);
        } else {
            mod.hologram().remove();
        }
    }

    private void showPulse(ServerLevel level, EggLocation egg, boolean firstFrame) {
        Settings settings = mod.settings();
        for (ServerPlayer viewer : mod.server().getPlayerList().getPlayers()) {
            if (!mod.state().wantsPulse(viewer.getUUID())) {
                mod.locatorBar().hide(viewer);
                continue;
            }
            if (viewer.level() != level) {
                // e.g. went through a portal during the pulse
                mod.locatorBar().hide(viewer);
                if (firstFrame) {
                    DragonEggHunt.actionBar(viewer, settings.message("egg-in-dimension",
                            "dimension", settings.dimensionName(level)));
                }
                continue;
            }
            mod.locatorBar().showDirection(viewer, egg.pos(), iconFor(viewer, blinkPhase));
            if (firstFrame && settings.pulseSound()) {
                playPulseSound(viewer, egg.pos());
            }
            if (settings.pulseMessage()) {
                // sent every run, so it stays up for the whole pulse
                DragonEggHunt.actionBar(viewer, settings.message("pulse-start"));
            }
        }
    }

    /** The sound of an eye of ender shattering, a few blocks away in the egg's direction. Only this player hears it. */
    private static void playPulseSound(ServerPlayer viewer, BlockPos egg) {
        Vec3 eye = viewer.getEyePosition();
        Vec3 towardsEgg = Vec3.atCenterOf(egg).subtract(eye);
        Vec3 from = towardsEgg.lengthSqr() < SOUND_DISTANCE * SOUND_DISTANCE
                ? eye
                : eye.add(towardsEgg.normalize().scale(SOUND_DISTANCE));
        viewer.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ENDER_EYE_DEATH), SoundSource.MASTER,
                from.x, from.y, from.z, 1.0f, 0.8f, viewer.getRandom().nextLong()));
    }

    /**
     * Every few hours, while the egg is in play, a short chat message explains it to everyone who hasn't turned
     * it off with /dragonegg off.
     */
    private void runHelp(EggState state) {
        Settings settings = mod.settings();
        if (settings.helpIntervalMinutes() <= 0) {
            return;
        }
        // a shorter interval set with /dragonegg config or reload counts from now, not from the old one
        ticksUntilHelp = Math.min(ticksUntilHelp, settings.helpIntervalMinutes() * 60L * 20L);
        ticksUntilHelp -= PERIOD_TICKS;
        if (ticksUntilHelp > 0) {
            return;
        }
        restartHelpTimer();
        if (state.status() == EggState.Status.UNKNOWN) {
            return;
        }
        String minutes = Long.toString(Math.max(1, Math.round(settings.pulseIntervalSeconds() / 60.0)));
        for (ServerPlayer player : mod.server().getPlayerList().getPlayers()) {
            if (mod.state().wantsPulse(player.getUUID())) {
                player.sendSystemMessage(settings.message("help",
                        "minutes", minutes,
                        "clicks", Integer.toString(settings.pickupClicks())));
            }
        }
    }

    /**
     * Only checked while the chunk is loaded, so we never load chunks just for this.
     * A few runs of grace avoid false alarms, e.g. while the block is being changed.
     */
    private boolean checkEggStillThere(ServerLevel level, EggLocation egg) {
        if (!egg.isLoaded(level)) {
            return true;
        }
        if (EggRules.isEgg(level.getBlockState(egg.pos()))) {
            missingEggRuns = 0;
            return true;
        }
        if (++missingEggRuns < MISSING_EGG_GRACE_RUNS) {
            return true;
        }
        DragonEggHunt.LOGGER.warn("The dragon egg is no longer at {}, its location is now unknown.",
                egg.pos().toShortString());
        mod.state().setUnknown();
        reset();
        return false;
    }

    /**
     * While nobody knows where the egg is, an egg showing up in someone's inventory (e.g. from /give) makes them
     * the holder.
     */
    private void findLostEgg() {
        for (ServerPlayer player : mod.server().getPlayerList().getPlayers()) {
            if (EggRules.hasEgg(player)) {
                mod.onEggCarried(player);
                return;
            }
        }
    }

    private Waypoint.Icon iconFor(ServerPlayer viewer, boolean blink) {
        Settings settings = mod.settings();
        int color = blink && settings.blink() ? settings.blinkColor() : settings.color();
        return mod.locatorBar().icon(mod.resourcePacks().hasPack(viewer.getUUID()), color);
    }
}
