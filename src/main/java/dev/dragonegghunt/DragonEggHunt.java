package dev.dragonegghunt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Dragon Egg Hunt: the dragon egg lives either in a player's own inventory or as a block in the world, and the
 * locator bar shows where it is.
 * <p>
 * This class wires everything to Fabric's events and owns the egg's lifecycle (taken, placed, lost).
 * The few things Fabric has no event for are small mixins in {@code dev.dragonegghunt.mixin}.
 */
public final class DragonEggHunt implements ModInitializer {

    public static final String MOD_ID = "dragonegghunt";
    public static final Logger LOGGER = LoggerFactory.getLogger("DragonEggHunt");

    private static final int NEW_EGG_SEARCH_RADIUS = 4;
    private static final int NEW_EGG_SEARCH_SECONDS = 40;

    private static @Nullable DragonEggHunt instance;

    private @Nullable MinecraftServer server;
    private ConfigFile config;
    private Settings settings;
    private EggState state;
    private LocatorBar locatorBar;
    private PickupProgress pickupProgress;
    private EggHologram hologram;
    private EggGlow eggGlow;
    private HolderGlow holderGlow;
    private ResourcePackService resourcePacks;
    private Tracker tracker;
    private EggCommand command;

    /** Dimensions where a dragon just died, with the seconds left to look for the new egg. */
    private final List<NewEggSearch> newEggSearches = new ArrayList<>();
    /** Work for the next tick, e.g. placing an egg found as an item while entities were loading. */
    private final List<Runnable> nextTick = new ArrayList<>();

    private record NewEggSearch(ResourceKey<Level> dimension, int[] secondsLeft) {
    }

    /** The running instance while a server is running, otherwise {@code null} (e.g. on a client's title screen). */
    public static @Nullable DragonEggHunt active() {
        DragonEggHunt mod = instance;
        return mod != null && mod.server != null ? mod : null;
    }

    @Override
    public void onInitialize() {
        instance = this;
        command = new EggCommand(this);

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> command.register(dispatcher));

        UseBlockCallback.EVENT.register(this::onUseBlock);
        AttackBlockCallback.EVENT.register(this::onAttackBlock);
        PlayerBlockBreakEvents.BEFORE.register(this::beforeBreak);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onUseEntity(player, level, hand));

        ServerPlayerEvents.JOIN.register(this::onJoin);
        ServerPlayerEvents.LEAVE.register(this::onLeave);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onClientReset(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> onClientReset(player));
        ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> {
            if (active() != null) {
                resourcePacks.configure(listener);
            }
        });

        ServerEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnload);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::afterDamage);
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, fromDisk) -> allowLoad(entity, level));
    }

    // ---------------------------------------------------------------- server lifecycle

    private void onServerStarting(MinecraftServer server) {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("dragonegghunt.yml");
        try {
            config = ConfigFile.load(configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + configFile, e);
        }
        settings = Settings.load(config);
        ResourcePackService.export(FabricLoader.getInstance().getConfigDir().resolve("dragonegghunt-pack.zip"));

        state = new EggState(server.getWorldPath(LevelResource.ROOT).resolve("dragonegghunt.json"), LOGGER);
        state.load();

        locatorBar = new LocatorBar();
        pickupProgress = new PickupProgress(this);
        hologram = new EggHologram(this);
        eggGlow = new EggGlow();
        holderGlow = new HolderGlow(this);
        resourcePacks = new ResourcePackService(this);
        this.server = server;
        tracker = new Tracker(this);
    }

    private void onServerStarted(MinecraftServer server) {
        resourcePacks.start();
    }

    private void onServerStopping(MinecraftServer server) {
        if (active() == null) {
            return;
        }
        if (server.getPlayerList() == null) {
            // The server failed before it finished starting (e.g. the port was taken): nobody joined,
            // nothing was shown, so there is nothing to clean up in the world
            resourcePacks.stop();
            this.server = null;
            return;
        }
        // Players still online during shutdown leave the egg behind like on a normal logout
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            placeFromPlayer(player);
        }
        locatorBar.hideAll(server);
        hologram.remove();
        eggGlow.remove();
        holderGlow.clear();
        resourcePacks.stop();
        this.server = null;
    }

    /** Reads the config file again and applies it. Returns false if the file couldn't be read. */
    public boolean reload() {
        try {
            config.reload();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read config/dragonegghunt.yml", e);
            return false;
        }
        applySettings();
        return true;
    }

    /** Applies changed settings, e.g. after /dragonegg config. */
    public void applySettings() {
        settings = Settings.load(config);
        resourcePacks.start();
        // players who joined while the pack wasn't available get it now
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!resourcePacks.hasPack(player.getUUID())) {
                resourcePacks.send(player);
            }
        }
        tracker.reset();
    }

    private void onTick(MinecraftServer server) {
        if (active() == null) {
            return;
        }
        if (!nextTick.isEmpty()) {
            List<Runnable> tasks = List.copyOf(nextTick);
            nextTick.clear();
            tasks.forEach(Runnable::run);
        }
        int tick = server.getTickCount();
        if (tick % Tracker.PERIOD_TICKS == 0) {
            tracker.run();
        }
        if (tick % 20 == 0 && !newEggSearches.isEmpty()) {
            searchNewEggs();
        }
    }

    // ---------------------------------------------------------------- egg lifecycle

    /** Moves the egg block into the player's inventory. Returns false if it didn't fit. */
    public boolean takeEgg(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!player.getInventory().add(new ItemStack(Items.DRAGON_EGG))) {
            actionBar(player, settings.message("inventory-full"));
            return false;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        onEggCarried(player);

        server.getPlayerList().broadcastSystemMessage(
                settings.message("pickup-broadcast", "player", player.getName().getString()), false);
        player.sendSystemMessage(settings.message("pickup-holder"));
        return true;
    }

    public void onEggCarried(ServerPlayer player) {
        state.setCarried(player.getUUID(), player.getName().getString());
        tracker.reset();
    }

    public void onEggPlaced(ServerLevel level, BlockPos pos) {
        if (!EggRules.isEgg(level.getBlockState(pos))) {
            return;
        }
        state.setPlaced(EggLocation.of(level, pos));
        tracker.reset();
    }

    /** Takes every egg off the player (inventory and cursor) and places it where they are. */
    public void placeFromPlayer(ServerPlayer player) {
        if (EggRules.removeEggs(player) > 0) {
            placeEgg(player.level(), player.position());
        }
    }

    /** Places the egg as a block at the nearest safe spot. */
    public void placeEgg(ServerLevel level, Vec3 where) {
        BlockPos spot = EggRules.findSafeSpot(level, where);
        // only tells clients, no neighbor updates; the egg has no gravity anyway
        level.setBlock(spot, Blocks.DRAGON_EGG.defaultBlockState(), Block.UPDATE_CLIENTS);
        state.setPlaced(EggLocation.of(level, spot));
        tracker.reset();
    }

    /** The world spawn of this dimension, or 0 0 if it has none (e.g. the End). */
    static BlockPos spawnPos(ServerLevel level) {
        BlockPos spawn = level.getRespawnData().pos();
        return level.dimension().equals(level.getRespawnData().dimension()) ? spawn : BlockPos.ZERO;
    }

    // ---------------------------------------------------------------- hooks from the mixins

    /**
     * The player dies: the egg is placed where they died, also with keepInventory. It's taken from the inventory
     * and the cursor before anything drops, so it never becomes an item.
     */
    public void onPlayerDeath(ServerPlayer player) {
        placeFromPlayer(player);
    }

    /**
     * The player drops an item. Returns true if it's the egg: then it's put back into the inventory instead,
     * or placed as a block if there's no room or the player is gone.
     */
    public boolean onDrop(ServerPlayer player, ItemStack stack) {
        if (!EggRules.isEgg(stack)) {
            return false;
        }
        ItemStack egg = stack.copy();
        // the original stack may still be referenced, e.g. as the cursor item
        stack.setCount(0);
        boolean gone = player.isRemoved() || player.hasDisconnected() || player.isDeadOrDying();
        if (!gone) {
            Inventory inventory = player.getInventory();
            int selected = inventory.getSelectedSlot();
            if (inventory.getItem(selected).isEmpty()) {
                inventory.setItem(selected, egg);
                egg = ItemStack.EMPTY;
            } else {
                inventory.add(egg);
            }
            // the client already removed the item from the slot, so it has to be told it's back
            player.containerMenu.sendAllDataToRemote();
        }
        if (!egg.isEmpty()) {
            // e.g. closing the inventory with the egg on the cursor after filling every slot
            placeEgg(player.level(), player.position());
        } else {
            actionBar(player, settings.message("no-drop"));
        }
        return true;
    }

    /** A click in an inventory screen was refused because it would put the egg into a container. */
    public void onStorageBlocked(ServerPlayer player) {
        actionBar(player, settings.message("no-containers"));
    }

    // ---------------------------------------------------------------- events

    private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (active() == null || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState block = serverLevel.getBlockState(pos);
        if (EggRules.isEgg(block)) {
            if (player.isShiftKeyDown()) {
                // Sneak + right click works like on any other block (e.g. placing a block against the egg)
                // and doesn't count. The vanilla teleport it could trigger with empty hands is blocked by a mixin.
                return InteractionResult.PASS;
            }
            if (hand == InteractionHand.MAIN_HAND) {
                pickupProgress.click(serverPlayer, serverLevel, pos);
            }
            // also stops the vanilla teleport
            return InteractionResult.SUCCESS;
        }
        if (EggRules.storesItemsOnUse(block)) {
            // vanilla skips using the block when sneaking with something in hand
            boolean blockUsed = !player.isSecondaryUseActive()
                    || (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty());
            boolean eggInvolved = EggRules.isEgg(player.getItemInHand(hand))
                    || (EggRules.isShelf(block) && EggRules.hotbarHasEgg(serverPlayer));
            if (blockUsed && eggInvolved) {
                actionBar(serverPlayer, settings.message("no-containers"));
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.PASS;
    }

    private InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand, BlockPos pos,
                                            net.minecraft.core.Direction direction) {
        if (active() == null || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!EggRules.isEgg(serverLevel.getBlockState(pos))) {
            return InteractionResult.PASS;
        }
        // left clicks count too, and the egg is never broken or teleported
        pickupProgress.click(serverPlayer, serverLevel, pos);
        return InteractionResult.SUCCESS;
    }

    /** Only reachable if breaking started some other way, e.g. by another mod. */
    private boolean beforeBreak(Level level, Player player, BlockPos pos, BlockState block,
                                @Nullable net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (active() == null || !EggRules.isEgg(block)
                || !(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        pickupProgress.click(serverPlayer, serverLevel, pos);
        return false;
    }

    /** Item frames, armor stands, allays, chest boats ... never get the egg. */
    private InteractionResult onUseEntity(Player player, Level level, InteractionHand hand) {
        if (active() == null || !(player instanceof ServerPlayer serverPlayer) || level.isClientSide()) {
            return InteractionResult.PASS;
        }
        if (EggRules.isEgg(player.getItemInHand(hand))) {
            actionBar(serverPlayer, settings.message("no-containers"));
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    private void onJoin(ServerPlayer player) {
        if (active() == null) {
            return;
        }
        holderGlow.onJoin(player);
        if (EggRules.hasEgg(player)) {
            // e.g. after a crash, when the egg could not be placed on logout
            onEggCarried(player);
        }
    }

    /** Called before the player is saved, so the egg is never saved in their inventory. */
    private void onLeave(ServerPlayer player) {
        if (active() == null) {
            return;
        }
        placeFromPlayer(player);
        holderGlow.onLeave(player);
        locatorBar.forget(player.getUUID());
        pickupProgress.forget(player.getUUID());
        resourcePacks.forget(player.getUUID());
    }

    /**
     * After respawning or changing dimensions the client has dropped the virtual entities, so they're sent again.
     * The locator bar is different: the client keeps its waypoints for the whole connection, so the next tracker
     * run either updates the egg's waypoint or removes it for players in another dimension.
     */
    private void onClientReset(ServerPlayer player) {
        if (active() == null) {
            return;
        }
        hologram.forget(player);
        eggGlow.forget(player);
    }

    /**
     * The dragon is removed at the end of its death animation (or right away with /kill), and the end fight places
     * the egg on the exit portal at the same moment, but only the first time.
     */
    private void onEntityUnload(Entity entity, ServerLevel level) {
        if (active() != null && entity instanceof EnderDragon && entity.getRemovalReason() == Entity.RemovalReason.KILLED) {
            watchForNewEgg(level);
        }
    }

    /** Players hitting each other in the egg's zone (also with arrows, tridents ...) make the zone visible to everyone. */
    private void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damage, boolean blocked) {
        if (active() == null || !(entity instanceof ServerPlayer victim)
                || !(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
            return;
        }
        EggLocation egg = state.location();
        if (state.status() != EggState.Status.PLACED || egg == null) {
            return;
        }
        if (pickupProgress.inZone(egg, victim) || pickupProgress.inZone(egg, attacker)) {
            pickupProgress.alert(egg);
        }
    }

    /**
     * The egg never exists as an item or a falling block in the world. If one shows up anyway (e.g. /give with a
     * full inventory, or an egg item from before the mod was installed), it's placed as a block instead.
     */
    private boolean allowLoad(Entity entity, ServerLevel level) {
        if (active() == null) {
            return true;
        }
        boolean egg = (entity instanceof ItemEntity item && EggRules.isEgg(item.getItem()))
                || (entity instanceof FallingBlockEntity falling && EggRules.isEgg(falling.getBlockState()));
        if (!egg) {
            return true;
        }
        Vec3 position = entity.position();
        nextTick.add(() -> placeEgg(level, position));
        return false;
    }

    // ---------------------------------------------------------------- first appearance

    /** Vanilla places the egg without an event, so we look for it for a while. */
    private void watchForNewEgg(ServerLevel level) {
        newEggSearches.add(new NewEggSearch(level.dimension(), new int[]{NEW_EGG_SEARCH_SECONDS}));
    }

    private void searchNewEggs() {
        Iterator<NewEggSearch> searches = newEggSearches.iterator();
        while (searches.hasNext()) {
            NewEggSearch search = searches.next();
            ServerLevel level = server.getLevel(search.dimension());
            BlockPos egg = level == null ? null : findEggNearOrigin(level);
            if (egg != null && state.status() != EggState.Status.CARRIED) {
                onEggPlaced(level, egg);
                searches.remove();
                continue;
            }
            if (--search.secondsLeft()[0] <= 0) {
                searches.remove();
            }
        }
    }

    private static @Nullable BlockPos findEggNearOrigin(ServerLevel level) {
        for (int x = -NEW_EGG_SEARCH_RADIUS; x <= NEW_EGG_SEARCH_RADIUS; x++) {
            for (int z = -NEW_EGG_SEARCH_RADIUS; z <= NEW_EGG_SEARCH_RADIUS; z++) {
                if (!EggLocation.isLoaded(level, x, z)) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                for (int y = top; y >= Math.max(level.getMinY(), top - 8); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (EggRules.isEgg(level.getBlockState(pos))) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    public static void actionBar(ServerPlayer player, Component message) {
        player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }

    /** Ticks since the server started. */
    public int currentTick() {
        return server == null ? 0 : server.getTickCount();
    }

    // ---------------------------------------------------------------- accessors

    public MinecraftServer server() {
        return server;
    }

    public Settings settings() {
        return settings;
    }

    public EggState state() {
        return state;
    }

    public LocatorBar locatorBar() {
        return locatorBar;
    }

    public PickupProgress pickupProgress() {
        return pickupProgress;
    }

    public EggHologram hologram() {
        return hologram;
    }

    public EggGlow eggGlow() {
        return eggGlow;
    }

    public HolderGlow holderGlow() {
        return holderGlow;
    }

    public Tracker tracker() {
        return tracker;
    }

    public ResourcePackService resourcePacks() {
        return resourcePacks;
    }
}
