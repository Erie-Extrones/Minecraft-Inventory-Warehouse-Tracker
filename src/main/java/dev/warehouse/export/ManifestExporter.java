package dev.warehouse.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.CustomItemClassifier;
import dev.warehouse.items.CustomItemRules;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.ItemSample;
import dev.warehouse.organizer.Categorizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.organizer.Zone;
import dev.warehouse.storage.GsonHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the custom item manifest (groups, samples, how each one currently classifies and where it is stored) to the shared
 * folder, and watches that folder for an updated custom_item_rules.json. This is the hand-off point for external tuning.
 */
public final class ManifestExporter {
    public static final int FORMAT = 1;
    private static final int RULES_CHECK_TICKS = 600;

    private long lastExportMs;
    private long boundAtMs;
    private int ticks;
    private long rulesConfigMtime = -1, rulesSharedMtime = -1;

    public static @Nullable Path sharedDir() {
        String dir = ConfigIO.get().sharedDir;
        if (dir == null || dir.isBlank()) return null;
        Path p = Path.of(dir.trim());
        return p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
    }

    public static Path configRulesFile() {
        return ConfigIO.rootDir().resolve(CustomItemRules.FILE_NAME);
    }

    public static @Nullable Path sharedRulesFile() {
        Path d = sharedDir();
        return d == null ? null : d.resolve(CustomItemRules.FILE_NAME);
    }

    /** (Re)load rule files and remember their timestamps; reports to chat when asked. */
    public int reloadRules(boolean announce) {
        Path cfg = configRulesFile();
        Path shared = sharedRulesFile();
        int n = CustomItemRules.load(shared != null ? List.of(cfg, shared) : List.of(cfg));
        rulesConfigMtime = mtime(cfg);
        rulesSharedMtime = mtime(shared);
        WarehouseClient.categories().invalidate();
        WarehouseClient.organizer().invalidate();
        if (announce) Chat.info("Loaded " + n + " custom item rule(s): " + String.join("; ", CustomItemRules.loadLog()));
        return n;
    }

    public void onBind() {
        boundAtMs = System.currentTimeMillis();
        lastExportMs = 0;
        CustomItemClassifier.setServer(WarehouseClient.storage().serverKey());
        reloadRules(false);
    }

    public void tick(Minecraft mc) {
        if (++ticks % RULES_CHECK_TICKS != 0) return;
        if (mtime(configRulesFile()) != rulesConfigMtime || mtime(sharedRulesFile()) != rulesSharedMtime) {
            int n = reloadRules(false);
            Chat.info("Custom item rules changed on disk: " + n + " rule(s) active.");
        }
        ModConfig cfg = ConfigIO.get();
        if (sharedDir() == null || !WarehouseClient.storage().isBound()) return;
        long now = System.currentTimeMillis();
        long interval = Math.max(5, cfg.manifestExportIntervalMinutes) * 60_000L;
        boolean due = lastExportMs == 0 ? now - boundAtMs >= 120_000L : now - lastExportMs >= interval;
        if (due && !WarehouseClient.itemGroups().all().isEmpty()) {
            lastExportMs = now;
            Path p = export(false);
            if (p != null) WarehouseClient.LOGGER.info("Wrote item manifest to {}", p);
        }
    }

    private static long mtime(@Nullable Path p) {
        try {
            return p != null && Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Write the manifest to the shared folder (or the exports folder when none is configured). Returns the file or null. */
    public @Nullable Path export(boolean announce) {
        Path dir = sharedDir();
        if (dir == null) dir = WarehouseClient.storage().exportsDir();
        if (dir == null) {
            if (announce) Chat.error("Not connected to a world.");
            return null;
        }
        String server = WarehouseClient.storage().serverKey();
        Path file = dir.resolve("warehouse-manifest-" + sanitize(server) + ".json");
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GsonHolder.GSON.toJson(build(server)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (announce) Chat.info("Item manifest written to " + file);
            return file;
        } catch (IOException e) {
            WarehouseClient.LOGGER.warn("Manifest export failed", e);
            if (announce) Chat.error("Manifest export failed: " + e.getMessage());
            return null;
        }
    }

    private static String sanitize(@Nullable String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    JsonObject build(@Nullable String server) {
        CategoryResolver cats = WarehouseClient.categories();
        Plan plan = WarehouseClient.organizer().plan();
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("server", server);
        root.addProperty("modVersion", FabricLoader.getInstance().getModContainer(WarehouseClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        root.addProperty("exportedAt", Instant.now().toString());
        JsonArray cats0 = new JsonArray();
        for (String c : Categorizer.DEFAULT_ORDER) cats0.add(c);
        root.add("categories", cats0);
        JsonArray rules = new JsonArray();
        for (CustomItemRules.Rule r : CustomItemRules.active()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("source", CustomItemRules.sourceOf(r));
            rules.add(o);
        }
        root.add("activeRules", rules);
        JsonObject zones = new JsonObject();
        if (plan.isActive()) for (Zone z : plan.zones.values()) zones.addProperty(z.category, z.containerIds.size());
        root.add("planZones", zones);
        root.add("planCategoryOverrides", GsonHolder.GSON.toJsonTree(plan.categoryOverrides));
        root.add("configCategoryOverrides", GsonHolder.GSON.toJsonTree(ConfigIO.get().categoryOverrides));

        // Where every key is stored, aggregated by group.
        Map<String, Map<String, int[]>> storedByGroup = new LinkedHashMap<>();
        Map<ItemKey, ItemGroup> groupOf = new LinkedHashMap<>();
        for (ItemGroup g : WarehouseClient.itemGroups().all()) for (ItemKey k : g.members) groupOf.put(k, g);
        for (ContainerEntry e : WarehouseClient.index().all()) {
            String zone = plan.zoneOf(e.id);
            String where = (zone != null ? zone : "-") + "|" + e.label();
            for (StackRecord s : e.contents) {
                addStored(storedByGroup, groupOf, s, where);
                if (s.nested != null) for (StackRecord n : s.nested) addStored(storedByGroup, groupOf, n, where);
            }
        }

        JsonArray groups = new JsonArray();
        for (ItemGroup g : WarehouseClient.itemGroups().all()) {
            JsonObject o = new JsonObject();
            o.addProperty("groupId", g.groupId);
            o.addProperty("displayName", g.displayName);
            o.addProperty("loreHint", g.loreHint);
            o.addProperty("categoryOverride", g.categoryOverride);
            JsonArray members = new JsonArray();
            for (ItemKey k : g.members) members.add(k.asString());
            o.add("members", members);
            ItemKey first = g.members.isEmpty() ? null : g.members.get(0);
            if (first != null) {
                ItemStack sample = CategoryResolver.sampleStack(first);
                o.addProperty("baseCategory", sample == null ? Categorizer.MISC : Categorizer.categorize(sample));
                o.addProperty("baseFamily", sample == null ? first.itemId : Categorizer.subFamily(sample));
                CustomItemClassifier.Result r = CustomItemClassifier.classify(g, first, sample == null ? Categorizer.MISC : Categorizer.categorize(sample), sample == null ? first.itemId : Categorizer.subFamily(sample));
                JsonObject res = new JsonObject();
                res.addProperty("category", cats.categoryOf(first));
                res.addProperty("family", cats.subFamilyOf(first));
                res.addProperty("classifierCategory", r.category());
                res.addProperty("classifierFamily", r.family());
                res.addProperty("reason", r.reason());
                o.add("resolved", res);
            }
            JsonArray stored = new JsonArray();
            int total = 0;
            Map<String, int[]> places = storedByGroup.get(g.groupId);
            if (places != null) for (Map.Entry<String, int[]> en : places.entrySet()) {
                JsonObject so = new JsonObject();
                String[] parts = en.getKey().split("\\|", 2);
                so.addProperty("zone", parts[0]);
                so.addProperty("chest", parts.length > 1 ? parts[1] : "");
                so.addProperty("count", en.getValue()[0]);
                total += en.getValue()[0];
                stored.add(so);
            }
            o.add("stored", stored);
            o.addProperty("totalStored", total);
            JsonArray samples = new JsonArray();
            if (g.samples != null) for (ItemSample s : g.samples) samples.add(GsonHolder.GSON.toJsonTree(s));
            o.add("samples", samples);
            groups.add(o);
        }
        root.add("groups", groups);
        return root;
    }

    private static void addStored(Map<String, Map<String, int[]>> acc, Map<ItemKey, ItemGroup> groupOf, StackRecord s, String where) {
        ItemGroup g = groupOf.get(s.key);
        if (g == null) return;
        acc.computeIfAbsent(g.groupId, k -> new LinkedHashMap<>()).computeIfAbsent(where, k -> new int[1])[0] += s.count;
    }
}
