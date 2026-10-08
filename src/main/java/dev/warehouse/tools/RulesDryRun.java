package dev.warehouse.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.warehouse.items.CustomItemClassifier;
import dev.warehouse.items.CustomItemRules;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.ItemSample;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline check: classify every group of a manifest with a given rules file and print what changes.
 * No Minecraft classes are loaded, so it runs anywhere the mod classes and Gson are on the classpath:
 * <pre>./gradlew rulesDryRun --args="path/to/warehouse-manifest-server.json path/to/custom_item_rules.json [--all]"</pre>
 * Exit code 0; prints one line per group: {@code CHANGED|SAME  groupId  name  before -> after  (reason)}.
 */
public final class RulesDryRun {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RulesDryRun <manifest.json> <custom_item_rules.json> [--all]");
            System.exit(2);
        }
        boolean all = args.length > 2 && args[2].equals("--all");
        JsonObject manifest = JsonParser.parseString(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        String server = manifest.has("server") && !manifest.get("server").isJsonNull() ? manifest.get("server").getAsString() : null;
        // Jar defaults come from the classpath resource; the given file overrides them like the config file would.
        int n = CustomItemRules.load(List.of(Path.of(args[1])));
        CustomItemClassifier.setServer(server);
        System.out.println("# " + n + " rule(s) active: " + String.join("; ", CustomItemRules.loadLog()));
        int changed = 0, total = 0;
        for (JsonElement ge : manifest.getAsJsonArray("groups")) {
            JsonObject g = ge.getAsJsonObject();
            total++;
            ItemGroup group = new ItemGroup(g.get("groupId").getAsString(), g.get("displayName").getAsString(), str(g, "loreHint"));
            group.samples = new ArrayList<>();
            if (g.has("samples")) for (JsonElement se : g.getAsJsonArray("samples")) {
                JsonObject so = se.getAsJsonObject();
                ItemSample s = new ItemSample();
                s.componentHash = str(so, "componentHash");
                s.itemId = str(so, "itemId");
                s.displayName = str(so, "displayName");
                s.componentsJson = str(so, "componentsJson");
                if (so.has("lore")) for (JsonElement le : so.getAsJsonArray("lore")) s.lore.add(le.getAsString());
                group.samples.add(s);
            }
            JsonArray members = g.getAsJsonArray("members");
            if (members == null || members.isEmpty()) continue;
            ItemKey key = ItemKey.parse(members.get(0).getAsString());
            String baseCat = str(g, "baseCategory");
            String baseFam = str(g, "baseFamily");
            JsonObject resolved = g.has("resolved") ? g.getAsJsonObject("resolved") : null;
            String before = resolved != null ? str(resolved, "classifierCategory") + " / " + str(resolved, "classifierFamily") : "?";
            CustomItemClassifier.Result r = CustomItemClassifier.classify(group, key, baseCat == null ? "Misc" : baseCat, baseFam == null ? key.itemId : baseFam);
            String after = r.category() + " / " + r.family();
            boolean diff = !before.equals(after);
            if (diff) changed++;
            if (diff || all) System.out.println((diff ? "CHANGED" : "SAME   ") + "  " + group.groupId + "  \"" + group.displayName + "\"  " + before + " -> " + after + "  (" + r.reason() + ")");
        }
        System.out.println("# " + changed + " of " + total + " group(s) would change");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
