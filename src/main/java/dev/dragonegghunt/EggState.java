package dev.dragonegghunt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Where the egg currently is, plus who turned the pulse or the zone display off.
 * Saved to dragonegghunt.json in the world folder, so a restart doesn't lose it.
 */
public final class EggState {

    public enum Status {
        UNKNOWN,
        CARRIED,
        PLACED
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    private Status status = Status.UNKNOWN;
    private @Nullable UUID holder;
    private @Nullable String holderName;
    private @Nullable EggLocation location;
    private final Set<UUID> pulseHidden = new HashSet<>();
    private final Set<UUID> zoneHidden = new HashSet<>();

    public EggState(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Status status() {
        return status;
    }

    public @Nullable UUID holder() {
        return holder;
    }

    /** The holder's name when they picked the egg up, for /dragonegg where while they're offline. */
    public @Nullable String holderName() {
        return holderName;
    }

    public @Nullable EggLocation location() {
        return location;
    }

    public void setCarried(UUID holder, String holderName) {
        this.status = Status.CARRIED;
        this.holder = holder;
        this.holderName = holderName;
        this.location = null;
        save();
    }

    public void setPlaced(EggLocation location) {
        this.status = Status.PLACED;
        this.holder = null;
        this.holderName = null;
        this.location = location;
        save();
    }

    public void setUnknown() {
        this.status = Status.UNKNOWN;
        this.holder = null;
        this.holderName = null;
        this.location = null;
        save();
    }

    // ---------------------------------------------------------------- per player preference

    public boolean wantsPulse(UUID player) {
        return !pulseHidden.contains(player);
    }

    public void setWantsPulse(UUID player, boolean wants) {
        if (wants ? pulseHidden.remove(player) : pulseHidden.add(player)) {
            save();
        }
    }

    public boolean wantsZone(UUID player) {
        return !zoneHidden.contains(player);
    }

    public void setWantsZone(UUID player, boolean wants) {
        if (wants ? zoneHidden.remove(player) : zoneHidden.add(player)) {
            save();
        }
    }

    // ---------------------------------------------------------------- file

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            try {
                status = Status.valueOf(string(json, "status", Status.UNKNOWN.name()));
            } catch (IllegalArgumentException e) {
                status = Status.UNKNOWN;
            }
            String holderId = string(json, "holder", null);
            holder = holderId == null ? null : UUID.fromString(holderId);
            holderName = string(json, "holder_name", null);
            String dimension = string(json, "dimension", null);
            Identifier dimensionId = dimension == null ? null : Identifier.tryParse(dimension);
            location = dimensionId == null || !json.has("x") ? null : new EggLocation(
                    ResourceKey.create(Registries.DIMENSION, dimensionId),
                    new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()));
            readIds(json, "pulse_hidden", pulseHidden);
            readIds(json, "zone_hidden", zoneHidden);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read {}, the egg's location is unknown", file, e);
            status = Status.UNKNOWN;
        }
        if ((status == Status.CARRIED && holder == null) || (status == Status.PLACED && location == null)) {
            status = Status.UNKNOWN;
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        json.addProperty("status", status.name());
        if (holder != null) {
            json.addProperty("holder", holder.toString());
        }
        if (holderName != null) {
            json.addProperty("holder_name", holderName);
        }
        if (location != null) {
            json.addProperty("dimension", location.dimension().identifier().toString());
            json.addProperty("x", location.pos().getX());
            json.addProperty("y", location.pos().getY());
            json.addProperty("z", location.pos().getZ());
        }
        json.add("pulse_hidden", writeIds(pulseHidden));
        json.add("zone_hidden", writeIds(zoneHidden));
        try {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(json));
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Could not save the egg state to {}", file, e);
        }
    }

    private static @Nullable String string(JsonObject json, String key, @Nullable String fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static void readIds(JsonObject json, String key, Set<UUID> into) {
        into.clear();
        if (json.get(key) instanceof JsonArray array) {
            for (JsonElement element : array) {
                try {
                    into.add(UUID.fromString(element.getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // skips broken entries
                }
            }
        }
    }

    private static JsonArray writeIds(Set<UUID> ids) {
        JsonArray array = new JsonArray();
        ids.stream().map(UUID::toString).sorted().forEach(array::add);
        return array;
    }
}
