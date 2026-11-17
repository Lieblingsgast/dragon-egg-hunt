package dev.dragonegghunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The player carrying the egg glows and can be seen through blocks by everyone, like with the glowing effect.
 * <p>
 * It's the vanilla glowing flag, so every client that can see the holder draws the outline. A tag on the player
 * marks the glow as ours, so it's removed again when they lose the egg, also after a crash. The purple color
 * comes from a small scoreboard team; a player who is already in another team keeps it and glows in its color.
 */
public final class HolderGlow {

    private static final String TEAM_NAME = "dragonegghunt";
    private static final String MARKER_TAG = "dragonegghunt.glowing";

    private final DragonEggHunt mod;
    private @Nullable UUID holder;
    private @Nullable String holderName;

    public HolderGlow(DragonEggHunt mod) {
        this.mod = mod;
    }

    /** Called every tracker run with the current holder, or {@code null} when nobody carries the egg. */
    public void update(@Nullable ServerPlayer player) {
        Settings settings = mod.settings();
        if (player == null || !settings.holderGlow()) {
            clear();
            return;
        }
        if (!player.getUUID().equals(holder)) {
            clear();
            holder = player.getUUID();
            holderName = player.getScoreboardName();
        }
        // applied every run: a respawned holder is a new player entity
        player.addTag(MARKER_TAG);
        if (!player.hasGlowingTag()) {
            player.setGlowingTag(true);
        }
        if (settings.holderGlowPurple()) {
            joinTeam(mod.server(), player.getScoreboardName());
        } else {
            leaveTeam(mod.server(), player.getScoreboardName());
        }
    }

    public void clear() {
        if (holder != null) {
            ServerPlayer player = mod.server().getPlayerList().getPlayer(holder);
            if (player != null) {
                removeGlow(player);
            }
        }
        if (holderName != null) {
            leaveTeam(mod.server(), holderName);
        }
        holder = null;
        holderName = null;
    }

    /** Before the player is saved, so the glow isn't stored with them. */
    public void onLeave(ServerPlayer player) {
        if (player.getUUID().equals(holder)) {
            clear();
        }
    }

    /** Removes a glow left over from a crash. */
    public void onJoin(ServerPlayer player) {
        if (!player.getUUID().equals(holder)) {
            removeGlow(player);
            leaveTeam(mod.server(), player.getScoreboardName());
        }
    }

    private static void removeGlow(ServerPlayer player) {
        if (player.entityTags().contains(MARKER_TAG)) {
            player.removeTag(MARKER_TAG);
            player.setGlowingTag(false);
        }
    }

    private static void joinTeam(MinecraftServer server, String name) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(name);
        if (current != null) {
            // already ours, or another team the player keeps
            return;
        }
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM_NAME);
        }
        team.setColor(Optional.of(TeamColor.LIGHT_PURPLE));
        scoreboard.addPlayerToTeam(name, team);
    }

    private static void leaveTeam(MinecraftServer server, String name) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        if (team != null && team.getPlayers().contains(name)) {
            scoreboard.removePlayerFromTeam(name, team);
        }
    }
}
