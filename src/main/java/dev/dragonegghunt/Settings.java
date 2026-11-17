package dev.dragonegghunt;

import eu.pb4.placeholders.api.ParserContext;
import eu.pb4.placeholders.api.parsers.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot of config/dragonegghunt.yml.
 */
public record Settings(
        int pickupRadius,
        int pickupClicks,
        int pickupResetTicks,
        int pulseIntervalSeconds,
        int pulseDurationSeconds,
        boolean pulseSound,
        boolean pulseMessage,
        int helpIntervalMinutes,
        int infoRadius,
        boolean zoneOutline,
        int zoneHeightRings,
        int zoneAlertSeconds,
        boolean eggGlow,
        int zoneColor,
        boolean hologram,
        boolean hologramBackground,
        boolean hologramThroughBlocks,
        boolean holderGlow,
        boolean holderGlowPurple,
        int color,
        boolean blink,
        int blinkColor,
        boolean packEnabled,
        String packMode,
        String packBindAddress,
        int packPort,
        String packPublicAddress,
        String packUrl,
        boolean packRequired,
        String packPrompt,
        ConfigFile config
) {

    /** Allowed range of a whole-number setting. */
    public record Range(int min, int max) {

        public int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final Range ANY_POSITIVE = new Range(0, Integer.MAX_VALUE);

    /**
     * Ranges for whole-number settings. Values outside are clamped when loading the file and refused by
     * {@code /dragonegg config}. Settings that aren't listed accept any value from 0 up.
     */
    private static final Map<String, Range> RANGES = Map.ofEntries(
            Map.entry("pickup.radius", new Range(0, 64)),
            Map.entry("pickup.clicks", new Range(1, 200)),
            Map.entry("pickup.reset-after-seconds", new Range(1, 60)),
            Map.entry("pulse.interval-seconds", new Range(10, 86400)),
            Map.entry("pulse.duration-seconds", new Range(1, 600)),
            Map.entry("help.interval-minutes", new Range(0, 1440)),
            Map.entry("display.info-radius", new Range(0, 256)),
            Map.entry("display.zone-height-rings", new Range(0, 8)),
            Map.entry("display.zone-alert-seconds", new Range(0, 3600)),
            Map.entry("resource-pack.embedded.port", new Range(1, 65535)));

    /** Text settings that only accept a few values. */
    private static final Map<String, List<String>> CHOICES = Map.of(
            "resource-pack.mode", List.of("embedded", "url"));

    public static Range range(String path) {
        return RANGES.getOrDefault(path, ANY_POSITIVE);
    }

    public static List<String> choices(String path) {
        return CHOICES.getOrDefault(path, List.of());
    }

    public static Settings load(ConfigFile config) {
        int pickupRadius = intValue(config, "pickup.radius", 10);
        return new Settings(
                pickupRadius,
                intValue(config, "pickup.clicks", 20),
                intValue(config, "pickup.reset-after-seconds", 3) * 20,
                intValue(config, "pulse.interval-seconds", 900),
                intValue(config, "pulse.duration-seconds", 5),
                config.getBoolean("pulse.sound", true),
                config.getBoolean("pulse.message", true),
                intValue(config, "help.interval-minutes", 120),
                Math.max(pickupRadius, intValue(config, "display.info-radius", 25)),
                config.getBoolean("display.zone-outline", true),
                intValue(config, "display.zone-height-rings", 3),
                intValue(config, "display.zone-alert-seconds", 300),
                config.getBoolean("display.egg-glow", true),
                parseColor(config.getString("display.zone-color"), 0xA23BFF),
                config.getBoolean("display.hologram", true),
                config.getBoolean("display.hologram-background", false),
                config.getBoolean("display.hologram-through-blocks", true),
                config.getBoolean("display.holder-glow", true),
                config.getBoolean("display.holder-glow-purple", true),
                parseColor(config.getString("display.color"), 0x9B59D9),
                config.getBoolean("display.blink", true),
                parseColor(config.getString("display.blink-color"), 0xFFFFFF),
                config.getBoolean("resource-pack.enabled", true),
                config.getString("resource-pack.mode", "embedded").trim().toLowerCase(Locale.ROOT),
                config.getString("resource-pack.embedded.bind-address", "0.0.0.0").trim(),
                intValue(config, "resource-pack.embedded.port", 8164),
                config.getString("resource-pack.embedded.public-address", "").trim(),
                config.getString("resource-pack.url", "").trim(),
                config.getBoolean("resource-pack.required", false),
                config.getString("resource-pack.prompt", ""),
                config
        );
    }

    private static int intValue(ConfigFile config, String path, int fallback) {
        return range(path).clamp(config.getInt(path, fallback));
    }

    /**
     * A message from the messages section, in the QuickText format of Text Placeholder API.
     * {@code placeholders} are pairs of name and value: {@code message("help", "minutes", "15")} fills in
     * {@code <minutes>}. Values are inserted as they are, so they may contain formatting tags themselves.
     */
    public Component message(String key, String... placeholders) {
        String raw = config.getString("messages." + key);
        String text = raw == null ? key : raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("<" + placeholders[i] + ">", placeholders[i + 1]);
        }
        return format(text);
    }

    /** Parses text in the QuickText format, e.g. {@code <gray>Hello <white>world}. */
    public static Component format(String text) {
        return TagParser.QUICK_TEXT_WITH_STF.parseComponent(text.replace("<newline>", "\n"), ParserContext.of());
    }

    public String dimensionName(ServerLevel level) {
        String key;
        if (level.dimension().equals(Level.OVERWORLD)) {
            key = "normal";
        } else if (level.dimension().equals(Level.NETHER)) {
            key = "nether";
        } else if (level.dimension().equals(Level.END)) {
            key = "the_end";
        } else {
            key = "custom";
        }
        String name = config.getString("messages.dimensions." + key);
        return name == null ? level.dimension().identifier().toString() : name;
    }

    static int parseColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String hex = value.trim().replace("#", "");
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
