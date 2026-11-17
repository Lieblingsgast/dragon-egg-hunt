package dev.dragonegghunt;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * /dragonegg config [section | setting [value | default]]
 * <p>
 * Shows and changes the settings in config/dragonegghunt.yml from inside the game. Changes are saved to the file
 * (comments stay) and applied right away. Settings can be named by their full path
 * ({@code display.zone-height-rings}) or just their last part ({@code zone-height-rings}).
 * Messages are left out; edit them in the file.
 */
final class ConfigCommand {

    private static final List<String> SECTIONS = List.of("pickup", "pulse", "help", "display", "resource-pack");
    private static final String COMMAND = "/dragonegg config ";

    private final DragonEggHunt mod;

    ConfigCommand(DragonEggHunt mod) {
        this.mod = mod;
    }

    /** {@code input} is everything after "config". */
    int execute(CommandSourceStack source, String input) {
        String[] args = input.isBlank() ? new String[0] : input.trim().split(" ", 2);
        if (args.length == 0) {
            list(source, SECTIONS);
            return 1;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (SECTIONS.contains(first)) {
            list(source, List.of(first));
            return 1;
        }
        String key = resolve(first);
        if (key == null) {
            source.sendSystemMessage(Component.literal("Unknown setting \"" + args[0] + "\". Use ").withStyle(ChatFormatting.RED)
                    .append(Component.literal("/dragonegg config").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" to see all of them.").withStyle(ChatFormatting.RED)));
            return 0;
        }
        if (args.length == 1) {
            show(source, key);
            return 1;
        }
        return set(source, key, args[1].trim());
    }

    /** Suggestions for the last word of {@code input} (everything typed after "config"). */
    List<String> suggest(String input) {
        String[] args = input.split(" ", -1);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String section : SECTIONS) {
                if (section.startsWith(typed)) {
                    options.add(section);
                }
            }
            for (String key : keys()) {
                if (key.startsWith(typed) || leaf(key).startsWith(typed)) {
                    options.add(key);
                }
            }
            return options;
        }
        if (args.length == 2) {
            String key = resolve(args[0].toLowerCase(Locale.ROOT));
            if (key == null) {
                return options;
            }
            Object fallback = config().getDefault(key);
            if (fallback instanceof Boolean) {
                options.addAll(List.of("true", "false"));
            } else if (!Settings.choices(key).isEmpty()) {
                options.addAll(Settings.choices(key));
            } else {
                options.add(format(config().get(key)));
            }
            options.add("default");
            String typed = args[1].toLowerCase(Locale.ROOT);
            options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
            return options.stream().distinct().toList();
        }
        return options;
    }

    // ---------------------------------------------------------------- output

    private void list(CommandSourceStack source, List<String> sections) {
        source.sendSystemMessage(Component.literal("Dragon Egg Hunt settings").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("  click one to change it, ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("yellow").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" = changed").withStyle(ChatFormatting.DARK_GRAY)));
        for (String section : sections) {
            source.sendSystemMessage(Component.literal(section).withStyle(ChatFormatting.GRAY));
            for (String key : keys()) {
                if (key.startsWith(section + ".")) {
                    source.sendSystemMessage(line(key, key.substring(section.length() + 1)));
                }
            }
        }
    }

    /** "  zone-height-rings: 3", with the setting's comment on hover. Clicking toggles or fills in the command. */
    private Component line(String key, String label) {
        Object value = config().get(key);
        Object fallback = config().getDefault(key);
        boolean changed = !Objects.equals(format(value), format(fallback));

        MutableComponent hover = Component.empty();
        for (String comment : config().comments(key)) {
            hover.append(Component.literal(comment).withStyle(ChatFormatting.GRAY)).append("\n");
        }
        hover.append(Component.literal("Default: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(format(fallback)).withStyle(ChatFormatting.WHITE))
                .append("\n")
                .append(Component.literal(value instanceof Boolean ? "Click to switch" : "Click to change")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));

        ClickEvent click = value instanceof Boolean on
                ? new ClickEvent.RunCommand(COMMAND + key + " " + !on)
                : new ClickEvent.SuggestCommand(COMMAND + key + " ");

        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(format(value)).withStyle(changed ? ChatFormatting.YELLOW : valueColor(value)))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)).withClickEvent(click));
    }

    private void show(CommandSourceStack source, String key) {
        source.sendSystemMessage(line(key, key));
        for (String comment : config().comments(key)) {
            source.sendSystemMessage(Component.literal("  " + comment).withStyle(ChatFormatting.DARK_GRAY));
        }
        source.sendSystemMessage(Component.literal("  Default: " + format(config().getDefault(key)) + ". Change it with ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(COMMAND + key + " <value>").withStyle(style -> style
                        .withColor(ChatFormatting.WHITE)
                        .withClickEvent(new ClickEvent.SuggestCommand(COMMAND + key + " ")))));
    }

    // ---------------------------------------------------------------- changing

    private int set(CommandSourceStack source, String key, String input) {
        Object fallback = config().getDefault(key);
        Object value;
        try {
            value = input.equalsIgnoreCase("default") ? fallback : parse(key, fallback, input);
        } catch (IllegalArgumentException e) {
            source.sendSystemMessage(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            config().set(key, value);
        } catch (IOException e) {
            DragonEggHunt.LOGGER.warn("Could not save config/dragonegghunt.yml", e);
            source.sendSystemMessage(Component.literal("Could not save the config file, see the server log.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        mod.applySettings();
        source.sendSystemMessage(Component.literal("Set ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(key).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" to ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(format(value)).withStyle(valueColor(value)))
                .append(Component.literal(".").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static Object parse(String key, @Nullable Object fallback, String input) {
        if (fallback instanceof Boolean) {
            return switch (input.toLowerCase(Locale.ROOT)) {
                case "true", "on", "yes" -> true;
                case "false", "off", "no" -> false;
                default -> throw new IllegalArgumentException(key + " is true or false.");
            };
        }
        if (fallback instanceof Integer || fallback instanceof Long) {
            Settings.Range range = Settings.range(key);
            try {
                int number = Integer.parseInt(input);
                if (number >= range.min() && number <= range.max()) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
            throw new IllegalArgumentException(range.max() == Integer.MAX_VALUE
                    ? key + " is a whole number from " + range.min() + " up."
                    : key + " is a whole number from " + range.min() + " to " + range.max() + ".");
        }
        if (fallback instanceof Number) {
            try {
                double number = Double.parseDouble(input);
                if (number >= 0) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
            throw new IllegalArgumentException(key + " is a number from 0 up.");
        }
        if (fallback instanceof String text && text.startsWith("#")) {
            if (!input.replace("#", "").matches("[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException(key + " is a color like #A23BFF.");
            }
            return String.format("#%06X", Settings.parseColor(input, 0));
        }
        List<String> choices = Settings.choices(key);
        if (!choices.isEmpty()) {
            String choice = input.toLowerCase(Locale.ROOT);
            if (!choices.contains(choice)) {
                throw new IllegalArgumentException(key + " is one of: " + String.join(", ", choices) + ".");
            }
            return choice;
        }
        // "" sets an empty text, e.g. to clear the pack URL
        return input.equals("\"\"") || input.equals("''") ? "" : input;
    }

    // ---------------------------------------------------------------- settings

    private ConfigFile config() {
        return mod.settings().config();
    }

    /** Every setting in the default config, in file order, without messages. */
    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (String key : config().keys()) {
            String section = key.substring(0, Math.max(0, key.indexOf('.')));
            if (SECTIONS.contains(section)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** The full path for a full path or a last part, e.g. "zone-height-rings" -> "display.zone-height-rings". */
    private @Nullable String resolve(String input) {
        String match = null;
        for (String key : keys()) {
            if (key.equals(input)) {
                return key;
            }
            if (leaf(key).equals(input)) {
                if (match != null) {
                    // ambiguous, the full path is needed
                    return null;
                }
                match = key;
            }
        }
        return match;
    }

    private static String leaf(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    private static String format(@Nullable Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof String text && text.isEmpty()) {
            return "\"\"";
        }
        return value.toString();
    }

    private static ChatFormatting valueColor(@Nullable Object value) {
        if (value instanceof Boolean on) {
            return on ? ChatFormatting.GREEN : ChatFormatting.RED;
        }
        return ChatFormatting.WHITE;
    }
}
