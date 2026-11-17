package dev.dragonegghunt;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delivers the optional resource pack with the egg icon, either from a tiny built-in web server
 * or from a URL the server owner provides, and remembers who actually loaded it.
 * <p>
 * The pack is sent while the player is still on the join loading screen (the configuration phase), so applying
 * it is part of joining instead of a second loading screen right after spawning. The client keeps downloaded
 * packs by their hash, so it only downloads the pack again when it changes.
 */
public final class ResourcePackService {

    public static final String PACK_RESOURCE = "/pack/dragonegghunt-pack.zip";
    public static final UUID PACK_ID = UUID.fromString("9e6a3f2c-41b8-4d0e-8f7a-2c5b1d9e4a63");
    private static final String PACK_PATH = "/dragonegghunt-pack.zip";

    private final DragonEggHunt mod;
    /** Players whose client loaded the pack. Written from network threads. */
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    private byte @Nullable [] packBytes;
    private volatile @Nullable String packHash;
    private volatile @Nullable String url;
    private @Nullable HttpServer server;
    private @Nullable ExecutorService executor;

    public ResourcePackService(DragonEggHunt mod) {
        this.mod = mod;
    }

    public void start() {
        stop();
        Settings settings = mod.settings();
        if (!settings.packEnabled()) {
            return;
        }
        try (InputStream in = ResourcePackService.class.getResourceAsStream(PACK_RESOURCE)) {
            if (in == null) {
                DragonEggHunt.LOGGER.warn("The resource pack is missing from the mod jar.");
                return;
            }
            packBytes = in.readAllBytes();
            packHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(packBytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            DragonEggHunt.LOGGER.warn("Could not read the resource pack", e);
            return;
        }

        if (settings.packMode().equals("url")) {
            if (settings.packUrl().isEmpty()) {
                DragonEggHunt.LOGGER.warn("resource-pack.mode is 'url' but resource-pack.url is empty. "
                        + "Players will see a colored dot.");
                return;
            }
            url = settings.packUrl();
            return;
        }

        if (settings.packPublicAddress().isEmpty()) {
            DragonEggHunt.LOGGER.warn("Set resource-pack.embedded.public-address in config/dragonegghunt.yml so "
                    + "players can download the egg icon. Until then they will see a colored dot.");
            return;
        }
        try {
            byte[] body = packBytes;
            HttpServer http = HttpServer.create(new InetSocketAddress(settings.packBindAddress(), settings.packPort()), 0);
            http.createContext(PACK_PATH, exchange -> {
                try {
                    if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals(PACK_PATH)) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } finally {
                    exchange.close();
                }
            });
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DragonEggHunt-ResourcePack");
                thread.setDaemon(true);
                return thread;
            });
            http.setExecutor(executor);
            http.start();
            server = http;
            url = "http://" + settings.packPublicAddress() + ":" + settings.packPort() + PACK_PATH;
            DragonEggHunt.LOGGER.info("Serving the egg icon resource pack at {}", url);
        } catch (IOException e) {
            DragonEggHunt.LOGGER.warn("Could not start the resource pack web server on port {}", settings.packPort(), e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        url = null;
    }

    /** Copies the pack next to the config for server owners who host it themselves (resource-pack.mode: url). */
    public static void export(Path target) {
        try (InputStream in = ResourcePackService.class.getResourceAsStream(PACK_RESOURCE)) {
            if (in != null) {
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            DragonEggHunt.LOGGER.warn("Could not export the resource pack: {}", e.getMessage());
        }
    }

    /**
     * Called while the player is on the join loading screen. Joining continues once the client has applied or
     * declined the pack. It's vanilla's own resource pack step, so it also works next to a server resource pack
     * from server.properties.
     */
    public void configure(ServerConfigurationPacketListenerImpl listener) {
        MinecraftServer.ServerResourcePackInfo info = info();
        if (info != null) {
            listener.addTask(new ServerResourcePackConfigurationTask(info));
        }
    }

    /** Sends the pack to a player who is already playing, e.g. after the pack was set up with a reload. */
    public void send(ServerPlayer player) {
        MinecraftServer.ServerResourcePackInfo info = info();
        if (info != null) {
            player.connection.send(new ClientboundResourcePackPushPacket(info.id(), info.url(), info.hash(),
                    info.isRequired(), Optional.ofNullable(info.prompt())));
        }
    }

    private MinecraftServer.@Nullable ServerResourcePackInfo info() {
        String packUrl = url;
        String hash = packHash;
        if (packUrl == null || hash == null) {
            return null;
        }
        try {
            URI.create(packUrl);
        } catch (IllegalArgumentException e) {
            DragonEggHunt.LOGGER.warn("The resource pack URL is not valid: {}", packUrl);
            return null;
        }
        Settings settings = mod.settings();
        Component prompt = settings.packPrompt().isEmpty() ? null : Component.literal(settings.packPrompt());
        return new MinecraftServer.ServerResourcePackInfo(PACK_ID, packUrl, hash, settings.packRequired(), prompt);
    }

    /** Called for every answer of a client to a resource pack, also from network threads. */
    public void onStatus(UUID playerId, UUID packId, ServerboundResourcePackPacket.Action action) {
        if (!PACK_ID.equals(packId)) {
            return;
        }
        switch (action) {
            case SUCCESSFULLY_LOADED -> loaded.add(playerId);
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> loaded.remove(playerId);
            default -> {
                // ACCEPTED and DOWNLOADED are intermediate steps
            }
        }
    }

    public boolean hasPack(UUID playerId) {
        return loaded.contains(playerId);
    }

    public void forget(UUID playerId) {
        loaded.remove(playerId);
    }
}
