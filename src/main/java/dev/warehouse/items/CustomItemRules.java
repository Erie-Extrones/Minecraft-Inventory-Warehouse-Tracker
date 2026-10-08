package dev.warehouse.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Data-driven classification rules for plugin items, so sorting can be tuned without a new jar.
 * Pure Java (no Minecraft classes) so the same code runs in the offline dry-run tool.
 * <p>
 * Sources, later ones override earlier ones by rule id: the jar's default rules, {@code config/warehouse/custom_item_rules.json},
 * and {@code <sharedDir>/custom_item_rules.json}. A rule matches when every given field matches (regexes, case-insensitive).
 * Family may be a literal, {@code $base} (the base item's family, e.g. the colour) or {@code $pack} (the plugin pack prefix).
 */
public final class CustomItemRules {
    public static final String FILE_NAME = "custom_item_rules.json";
    public static final String RESOURCE = "/assets/warehouse/" + FILE_NAME;

    /** What the classifier knows about an item when rules are evaluated. */
    public record Context(String itemId, String name, String lore, @Nullable String pluginId, @Nullable String crateKeyId,
                          Set<String> customDataKeys, boolean pluginMarked, String baseCategory, String baseFamily, @Nullable String server) {}

    public static final class Rule {
        public String id = "";
        public int priority;
        public @Nullable String server;
        public @Nullable String note;
        public Match match = new Match();
        public String category = "";
        public String family = "$base";
        transient String source = "";

        public boolean matches(Context c) {
            if (server != null && !server.isBlank() && (c.server() == null || !server.equalsIgnoreCase(c.server()))) return false;
            return match.matches(c);
        }
    }

    public static final class Match {
        public @Nullable String itemId;
        public @Nullable String name;
        public @Nullable String lore;
        public @Nullable String pluginId;
        public @Nullable String customDataKey;
        public @Nullable Boolean crateKey;
        public @Nullable Boolean pluginMarked;
        private transient Map<String, Pattern> compiled;

        boolean matches(Context c) {
            if (compiled == null) compile();
            if (itemId != null && !find("itemId", c.itemId())) return false;
            if (name != null && !find("name", c.name())) return false;
            if (lore != null && !find("lore", c.lore())) return false;
            if (pluginId != null && (c.pluginId() == null || !find("pluginId", c.pluginId()))) return false;
            if (customDataKey != null) {
                boolean any = false;
                for (String k : c.customDataKeys()) if (find("customDataKey", k)) {
                    any = true;
                    break;
                }
                if (!any) return false;
            }
            if (crateKey != null && crateKey != (c.crateKeyId() != null)) return false;
            if (pluginMarked != null && pluginMarked != c.pluginMarked()) return false;
            return true;
        }

        private boolean find(String field, String value) {
            Pattern p = compiled.get(field);
            return p != null && p.matcher(value == null ? "" : value).find();
        }

        private void compile() {
            compiled = new LinkedHashMap<>();
            put("itemId", itemId);
            put("name", name);
            put("lore", lore);
            put("pluginId", pluginId);
            put("customDataKey", customDataKey);
        }

        private void put(String field, @Nullable String regex) {
            if (regex == null) return;
            try {
                compiled.put(field, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                compiled.put(field, Pattern.compile("(?!)")); // never matches
            }
        }
    }

    public static final class RuleFile {
        public int version = 1;
        public @Nullable String updatedAt;
        public @Nullable String note;
        public List<Rule> rules = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile List<Rule> active = List.of();
    private static final List<String> loadLog = new ArrayList<>();

    private CustomItemRules() {}

    public static List<Rule> active() {
        return active;
    }

    public static List<String> loadLog() {
        return List.copyOf(loadLog);
    }

    /** First matching rule by descending priority, or null. */
    public static @Nullable Rule match(Context c) {
        for (Rule r : active) if (r.matches(c)) return r;
        return null;
    }

    /** Resolve a rule's family spec against the context. */
    public static String family(Rule r, Context c, @Nullable String packPrefix) {
        String f = r.family == null || r.family.isBlank() ? "$base" : r.family;
        return switch (f) {
            case "$base" -> c.baseFamily();
            case "$pack" -> packPrefix != null ? packPrefix : c.baseFamily();
            default -> f;
        };
    }

    /**
     * Load defaults from the jar, then the given files in order (missing files are skipped). Later rules replace earlier ones with the same id.
     * Returns the number of active rules.
     */
    public static synchronized int load(List<Path> files) {
        Map<String, Rule> byId = new LinkedHashMap<>();
        loadLog.clear();
        try (InputStream in = CustomItemRules.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                RuleFile rf = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), RuleFile.class);
                int n = merge(byId, rf, "jar");
                loadLog.add("jar defaults: " + n + " rule(s)");
            }
        } catch (Exception e) {
            loadLog.add("jar defaults: failed (" + e.getMessage() + ")");
        }
        for (Path p : files) {
            if (p == null || !Files.isRegularFile(p)) continue;
            try {
                RuleFile rf = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), RuleFile.class);
                int n = merge(byId, rf, p.toString());
                loadLog.add(p + ": " + n + " rule(s)" + (rf != null && rf.updatedAt != null ? ", updated " + rf.updatedAt : ""));
            } catch (Exception e) {
                loadLog.add(p + ": failed (" + e.getMessage() + ")");
            }
        }
        List<Rule> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparingInt((Rule r) -> r.priority).reversed());
        active = List.copyOf(list);
        return active.size();
    }

    private static int merge(Map<String, Rule> into, @Nullable RuleFile rf, String source) {
        if (rf == null || rf.rules == null) return 0;
        int n = 0;
        for (Rule r : rf.rules) {
            if (r == null || r.id == null || r.id.isBlank() || r.category == null || r.category.isBlank()) continue;
            if (r.match == null) r.match = new Match();
            r.source = source;
            into.put(r.id, r);
            n++;
        }
        return n;
    }

    public static String describe(Rule r) {
        return r.id + " (p" + r.priority + ") -> " + r.category + " / " + r.family + (r.note != null ? "  " + r.note : "");
    }

    public static String sourceOf(Rule r) {
        return r.source;
    }

    /** Read a rule file without activating it; used by the dry-run tool. */
    public static RuleFile read(Path p) throws IOException {
        RuleFile rf = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), RuleFile.class);
        return rf != null ? rf : new RuleFile();
    }

    /** Collect every key path inside a custom_data JSON object, e.g. "PublicBukkitValues/executableitems:ei-id". */
    public static Set<String> keyPaths(@Nullable JsonObject customData) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (customData != null) walk(customData, "", out);
        return out;
    }

    private static void walk(JsonObject o, String prefix, Set<String> out) {
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            String path = prefix.isEmpty() ? en.getKey() : prefix + "/" + en.getKey();
            out.add(path);
            if (en.getValue().isJsonObject()) walk(en.getValue().getAsJsonObject(), path, out);
        }
    }

    /** Parse lenient JSON into an array of rules for tooling. */
    public static JsonArray toJson(List<Rule> rules) {
        JsonArray a = new JsonArray();
        for (Rule r : rules) a.add(JsonParser.parseString(GSON.toJson(r)));
        return a;
    }
}
