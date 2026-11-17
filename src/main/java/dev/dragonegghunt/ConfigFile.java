package dev.dragonegghunt;

import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config/dragonegghunt.yml.
 * <p>
 * The default config.yml inside the mod jar is the template: its comments and order are kept, and every setting
 * gets the value from the server's file (or the default if the file doesn't have it). Writing the file that way
 * adds settings from newer mod versions with their comments, and changes made with /dragonegg config keep the
 * comments too. Settings are addressed by their full path, e.g. {@code display.zone-height-rings}.
 */
public final class ConfigFile {

    private static final String TEMPLATE_RESOURCE = "/config.yml";
    private static final Pattern KEY_LINE = Pattern.compile("^( *)([A-Za-z0-9_-]+):(?: (.*))?$");
    private static final Pattern PLAIN_TEXT = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern YAML_SPECIAL = Pattern.compile("(?i)true|false|yes|no|on|off|y|n|null|~|[-+0-9.eE]+");

    private final Path path;
    private final List<String> template;
    /** Every setting of the template with its default value, in file order. */
    private final Map<String, Object> defaults;
    /** The comment lines right above each setting in the template, without "#". */
    private final Map<String, List<String>> comments;
    private Map<String, Object> values = new LinkedHashMap<>();

    private ConfigFile(Path path, String template) {
        this.path = path;
        this.template = template.lines().toList();
        this.defaults = flatten(new Yaml().load(template));
        this.comments = readComments(this.template);
    }

    /** Reads the file (writes it first if it's missing) and adds settings it doesn't have yet. */
    public static ConfigFile load(Path path) throws IOException {
        ConfigFile config = new ConfigFile(path, readTemplate());
        config.reload();
        return config;
    }

    public void reload() throws IOException {
        if (Files.exists(path)) {
            Object parsed = new Yaml().load(Files.readString(path));
            values = parsed instanceof Map<?, ?> ? flatten(parsed) : new LinkedHashMap<>();
        } else {
            values = new LinkedHashMap<>();
        }
        save();
    }

    public static String readTemplate() throws IOException {
        try (InputStream in = ConfigFile.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("config.yml is missing from the mod jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- values

    public @Nullable Object get(String key) {
        Object value = values.get(key);
        return value != null ? value : defaults.get(key);
    }

    public @Nullable Object getDefault(String key) {
        return defaults.get(key);
    }

    /** All settings, in file order. */
    public List<String> keys() {
        return List.copyOf(defaults.keySet());
    }

    public List<String> comments(String key) {
        return comments.getOrDefault(key, List.of());
    }

    public int getInt(String key, int fallback) {
        Object value = get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // falls through
            }
        }
        return fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = get(key);
        if (value instanceof Boolean on) {
            return on;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "on", "yes" -> true;
                case "false", "off", "no" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    public @Nullable String getString(String key) {
        Object value = get(key);
        return value == null ? null : value.toString();
    }

    public String getString(String key, String fallback) {
        String value = getString(key);
        return value == null ? fallback : value;
    }

    /** Changes a setting and writes the file. */
    public void set(String key, Object value) throws IOException {
        values.put(key, value);
        save();
    }

    // ---------------------------------------------------------------- writing

    /** Writes the template with the current values. Only touches the file if something changed. */
    private void save() throws IOException {
        List<String> lines = new ArrayList<>(template.size());
        Deque<String[]> parents = new ArrayDeque<>();
        for (String line : template) {
            Matcher matcher = KEY_LINE.matcher(line);
            if (line.isBlank() || line.trim().startsWith("#") || !matcher.matches()) {
                lines.add(line);
                continue;
            }
            int indent = matcher.group(1).length();
            String name = matcher.group(2);
            String raw = matcher.group(3);
            String key = pathOf(parents, indent, name);
            if (raw == null || raw.isBlank()) {
                parents.push(new String[]{Integer.toString(indent), name});
                lines.add(line);
                continue;
            }
            Object value = get(key);
            lines.add(matcher.group(1) + name + ": " + (value == null ? raw : format(value, raw)));
        }
        String text = String.join("\n", lines) + "\n";
        if (Files.exists(path) && Files.readString(path).equals(text)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static String format(Object value, String original) {
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        boolean originalQuoted = original.startsWith("\"") || original.startsWith("'");
        if (!originalQuoted && PLAIN_TEXT.matcher(text).matches() && !YAML_SPECIAL.matcher(text).matches()) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ---------------------------------------------------------------- reading

    /** "a.b.c" for the line {@code name} at {@code indent}, after dropping parents that are indented as far or further. */
    private static String pathOf(Deque<String[]> parents, int indent, String name) {
        while (!parents.isEmpty() && Integer.parseInt(parents.peek()[0]) >= indent) {
            parents.pop();
        }
        StringBuilder key = new StringBuilder();
        parents.descendingIterator().forEachRemaining(parent -> key.append(parent[1]).append('.'));
        return key.append(name).toString();
    }

    private static Map<String, List<String>> readComments(List<String> lines) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Deque<String[]> parents = new ArrayDeque<>();
        List<String> pending = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                pending.clear();
                continue;
            }
            if (trimmed.startsWith("#")) {
                pending.add(trimmed.substring(1).trim());
                continue;
            }
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.matches()) {
                pending.clear();
                continue;
            }
            int indent = matcher.group(1).length();
            String key = pathOf(parents, indent, matcher.group(2));
            String raw = matcher.group(3);
            if (raw == null || raw.isBlank()) {
                parents.push(new String[]{Integer.toString(indent), matcher.group(2)});
            } else if (!pending.isEmpty()) {
                result.put(key, List.copyOf(pending));
            }
            pending.clear();
        }
        return result;
    }

    private static Map<String, Object> flatten(Object yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        flatten("", yaml, result);
        return result;
    }

    private static void flatten(String prefix, Object node, Map<String, Object> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), into);
            }
        } else if (!prefix.isEmpty() && node != null) {
            into.put(prefix, node);
        }
    }
}
