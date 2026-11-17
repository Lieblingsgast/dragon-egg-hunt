package dev.dragonegghunt;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * /dragonegg on | off | show-zone [on | off] | where | tp | pulse | reload | config
 */
public final class EggCommand {

    public static final String ADMIN_PERMISSION = "dragonegghunt.admin";
    /** Operators (level 2, like /tp) may use the admin commands without a permissions mod. */
    private static final PermissionLevel ADMIN_LEVEL = PermissionLevel.GAMEMASTERS;

    private final DragonEggHunt mod;
    private final ConfigCommand config;

    public EggCommand(DragonEggHunt mod) {
        this.mod = mod;
        this.config = new ConfigCommand(mod);
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dragonegg")
                .executes(context -> usage(context.getSource()))
                .then(Commands.literal("on").executes(context -> setPulse(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setPulse(context.getSource(), false)))
                .then(Commands.literal("show-zone")
                        .executes(context -> setZone(context.getSource(), null))
                        .then(Commands.literal("on").executes(context -> setZone(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> setZone(context.getSource(), false))))
                .then(admin("where").executes(context -> where(context.getSource())))
                .then(admin("tp").executes(context -> teleport(context.getSource())))
                .then(admin("pulse").executes(context -> pulse(context.getSource())))
                .then(admin("reload").executes(context -> reload(context.getSource())))
                .then(admin("config")
                        .executes(context -> config.execute(context.getSource(), ""))
                        .then(Commands.argument("setting", StringArgumentType.greedyString())
                                .suggests(configSuggestions())
                                .executes(context -> config.execute(context.getSource(),
                                        StringArgumentType.getString(context, "setting")))));
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(Permissions.require(ADMIN_PERMISSION, ADMIN_LEVEL));
    }

    /** Suggests for the last word of the greedy "setting value" argument. */
    private SuggestionProvider<CommandSourceStack> configSuggestions() {
        return (context, builder) -> {
            String typed = builder.getRemaining();
            int lastSpace = typed.lastIndexOf(' ');
            SuggestionsBuilder word = builder.createOffset(builder.getStart() + lastSpace + 1);
            for (String option : config.suggest(typed)) {
                word.suggest(option);
            }
            return word.buildFuture();
        };
    }

    // ---------------------------------------------------------------- everyone

    private int usage(CommandSourceStack source) {
        source.sendSystemMessage(mod.settings().message("usage"));
        return 1;
    }

    private int setPulse(CommandSourceStack source, boolean enable) {
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        mod.state().setWantsPulse(player.getUUID(), enable);
        if (!enable) {
            mod.locatorBar().hide(player);
        }
        source.sendSystemMessage(mod.settings().message(enable ? "pulse-on" : "pulse-off"));
        return 1;
    }

    /** {@code null} switches. */
    private int setZone(CommandSourceStack source, @Nullable Boolean show) {
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        boolean enable = show != null ? show : !mod.state().wantsZone(player.getUUID());
        mod.state().setWantsZone(player.getUUID(), enable);
        source.sendSystemMessage(mod.settings().message(enable ? "zone-on" : "zone-off"));
        return 1;
    }

    // ---------------------------------------------------------------- admins

    private int where(CommandSourceStack source) {
        Settings settings = mod.settings();
        EggState state = mod.state();
        switch (state.status()) {
            case CARRIED -> {
                UUID holderId = state.holder();
                ServerPlayer holder = holderId == null ? null : mod.server().getPlayerList().getPlayer(holderId);
                if (holder == null) {
                    String name = state.holderName() == null ? "?" : state.holderName();
                    source.sendSystemMessage(settings.message("where-carried", "player", name,
                            "world", "?", "x", "?", "y", "?", "z", "?"));
                    return 1;
                }
                source.sendSystemMessage(settings.message("where-carried",
                        "player", holder.getName().getString(),
                        "world", holder.level().dimension().identifier().toString(),
                        "x", Integer.toString(holder.getBlockX()),
                        "y", Integer.toString(holder.getBlockY()),
                        "z", Integer.toString(holder.getBlockZ())));
            }
            case PLACED -> {
                EggLocation location = state.location();
                if (location == null) {
                    source.sendSystemMessage(settings.message("where-unknown"));
                    return 1;
                }
                source.sendSystemMessage(settings.message("where-placed",
                        "world", location.dimension().identifier().toString(),
                        "x", Integer.toString(location.pos().getX()),
                        "y", Integer.toString(location.pos().getY()),
                        "z", Integer.toString(location.pos().getZ())));
            }
            case UNKNOWN -> source.sendSystemMessage(settings.message("where-unknown"));
        }
        return 1;
    }

    /** On top of the placed egg, or at the player carrying it. */
    private int teleport(CommandSourceStack source) {
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        EggState state = mod.state();
        ServerLevel level = null;
        Vec3 target = null;
        if (state.status() == EggState.Status.PLACED && state.location() != null) {
            level = state.location().level(mod.server());
            target = Vec3.atBottomCenterOf(state.location().pos()).add(0, 1, 0);
        } else if (state.status() == EggState.Status.CARRIED && state.holder() != null) {
            ServerPlayer holder = mod.server().getPlayerList().getPlayer(state.holder());
            if (holder != null) {
                level = holder.level();
                target = holder.position();
            }
        }
        if (level == null || target == null) {
            source.sendSystemMessage(mod.settings().message("where-unknown"));
            return 0;
        }
        player.teleportTo(level, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSystemMessage(mod.settings().message("tp-done"));
        return 1;
    }

    private int pulse(CommandSourceStack source) {
        source.sendSystemMessage(mod.settings().message(mod.tracker().pulseNow() ? "pulse-forced" : "pulse-not-placed"));
        return 1;
    }

    private int reload(CommandSourceStack source) {
        if (!mod.reload()) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "Could not read config/dragonegghunt.yml, see the server log."));
            return 0;
        }
        source.sendSystemMessage(mod.settings().message("reloaded"));
        return 1;
    }

    private @Nullable ServerPlayer player(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendSystemMessage(mod.settings().message("players-only"));
        }
        return player;
    }
}
