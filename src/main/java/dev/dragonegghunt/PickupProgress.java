package dev.dragonegghunt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Taking the egg works like in CraftAttack: only a player who is alone in the pickup zone can take it,
 * and only by clicking it a number of times. Leaving the clicking for a few seconds or someone else walking
 * into the zone resets the progress.
 */
public final class PickupProgress {

    /** Ignores a second click within this many ticks (e.g. a left and a right click event for the same click). */
    private static final int MIN_TICKS_BETWEEN_CLICKS = 2;

    private record Attempt(EggLocation egg, int clicks, int lastClickTick) {
    }

    private final DragonEggHunt mod;
    private final Map<UUID, Attempt> attempts = new HashMap<>();
    private @Nullable EggLocation alertEgg;
    private int alertTick;

    public PickupProgress(DragonEggHunt mod) {
        this.mod = mod;
    }

    public void click(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player.isSpectator()) {
            return;
        }
        Settings settings = mod.settings();
        EggLocation egg = EggLocation.of(level, pos);
        alert(egg);
        int playersInZone = playersInZone(level, egg);
        if (playersInZone > 1) {
            attempts.remove(player.getUUID());
            DragonEggHunt.actionBar(player, settings.message("pickup-blocked",
                    "radius", Integer.toString(settings.pickupRadius()),
                    "players", Integer.toString(playersInZone)));
            return;
        }

        int now = mod.currentTick();
        Attempt previous = active(player.getUUID(), egg);
        if (previous != null && now - previous.lastClickTick() < MIN_TICKS_BETWEEN_CLICKS) {
            return;
        }
        int clicks = previous == null ? 1 : previous.clicks() + 1;
        Vec3 center = egg.center();
        if (clicks >= settings.pickupClicks()) {
            attempts.remove(player.getUUID());
            if (mod.takeEgg(player, level, pos)) {
                level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS,
                        1f, 1f);
            }
            return;
        }
        attempts.put(player.getUUID(), new Attempt(egg, clicks, now));

        float pitch = 0.6f + 1.4f * clicks / settings.pickupClicks();
        // only the clicking player hears the chime
        player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_CHIME, SoundSource.BLOCKS,
                center.x, center.y, center.z, 0.7f, pitch, level.getRandom().nextLong()));
        level.sendParticles(ParticleTypes.PORTAL, center.x, center.y, center.z, 12, 0.25, 0.25, 0.25, 0.4);
        mod.hologram().refreshText(egg);
    }

    /** The player's current progress on this egg, or -1. Drops progress that timed out. */
    public int clicks(ServerPlayer player, EggLocation egg) {
        Attempt attempt = active(player.getUUID(), egg);
        return attempt == null ? -1 : attempt.clicks();
    }

    /**
     * Something is going on at the egg: someone clicked it (also when they weren't allowed to take it) or players
     * fight in its zone. For display.zone-alert-seconds after that, everyone near it sees the zone and the text,
     * also players who turned the zone off, so nobody misses that the egg is being taken or fought over.
     */
    public void alert(EggLocation egg) {
        alertEgg = egg;
        alertTick = mod.currentTick();
    }

    public boolean isAlert(EggLocation egg) {
        return egg.equals(alertEgg) && mod.currentTick() - alertTick <= mod.settings().zoneAlertSeconds() * 20L;
    }

    public void clear() {
        attempts.clear();
    }

    public void forget(UUID playerId) {
        attempts.remove(playerId);
    }

    /** Players (not spectators) inside the pickup zone. */
    public int playersInZone(ServerLevel level, EggLocation egg) {
        int count = 0;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && inZone(egg, player)) {
                count++;
            }
        }
        return count;
    }

    /** The zone is a sphere around the egg with the pickup radius, measured from the player's feet. */
    public boolean inZone(EggLocation egg, ServerPlayer player) {
        if (!egg.isIn(player.level())) {
            return false;
        }
        double radius = mod.settings().pickupRadius();
        return player.position().distanceToSqr(egg.center()) <= radius * radius;
    }

    private @Nullable Attempt active(UUID playerId, EggLocation egg) {
        Attempt attempt = attempts.get(playerId);
        if (attempt == null) {
            return null;
        }
        boolean sameEgg = attempt.egg().equals(egg);
        boolean timedOut = mod.currentTick() - attempt.lastClickTick() > mod.settings().pickupResetTicks();
        if (!sameEgg || timedOut) {
            attempts.remove(playerId);
            return null;
        }
        return attempt;
    }
}
