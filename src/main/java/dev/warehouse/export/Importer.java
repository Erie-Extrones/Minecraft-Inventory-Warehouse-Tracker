package dev.warehouse.export;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.warehouse.WarehouseClient;
import dev.warehouse.claims.ClaimAnchor;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.inventory.LostEntry;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Plan;
import dev.warehouse.region.Region;
import dev.warehouse.storage.GsonHolder;
import dev.warehouse.storage.StorageManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Reads an export bundle and merges or replaces the current server's data. */
public final class Importer {
    public enum Mode { MERGE, REPLACE }

    public record Result(boolean ok, String message) {}

    private final StorageManager storage;

    public Importer(StorageManager storage) {
        this.storage = storage;
    }

    public List<String> availableFiles() {
        List<String> out = new ArrayList<>();
        Path dir = storage.exportsDir();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json") || p.getFileName().toString().endsWith(".json.gz"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException ignored) {
        }
        return out;
    }

    public Result run(String fileName, Mode mode) {
        if (!storage.isBound()) return new Result(false, "Not connected to a server.");
        Path file = resolve(fileName);
        if (file == null) return new Result(false, "No such export: " + fileName + " (looked in " + storage.exportsDir() + ")");
        JsonObject root;
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            WarehouseClient.LOGGER.error("Import failed", e);
            return new Result(false, "Could not read " + fileName + ": " + e.getMessage());
        }
        if (!root.has("format") || root.get("format").getAsInt() > Exporter.FORMAT_VERSION) return new Result(false, "Unsupported export format.");

        List<Region> regions = list(root, "regions", new TypeToken<List<Region>>() {});
        List<ClaimAnchor> claims = list(root, "claims", new TypeToken<List<ClaimAnchor>>() {});
        List<ContainerEntry> index = list(root, "index", new TypeToken<List<ContainerEntry>>() {});
        List<ItemGroup> items = list(root, "items", new TypeToken<List<ItemGroup>>() {});
        List<LostEntry> lost = list(root, "lost", new TypeToken<List<LostEntry>>() {});
        Plan plan = root.has("plan") && !root.get("plan").isJsonNull() ? GsonHolder.GSON.fromJson(root.get("plan"), Plan.class) : null;
        long exportedAt = root.has("exportedAt") ? root.get("exportedAt").getAsLong() : 0;

        if (mode == Mode.REPLACE) {
            storage.regions.set(regions);
            storage.claims.set(claims);
            storage.index.set(index);
            storage.items.set(items);
            storage.plan.set(plan != null ? plan : Plan.empty());
            if (root.has("lost")) storage.lost.set(lost);
        } else {
            mergeRegions(regions);
            mergeClaims(claims);
            mergeIndex(index);
            mergeItems(items);
            Plan local = storage.plan.get();
            if (plan != null && plan.exists() && (!local.exists() || plan.createdEpochMs > local.createdEpochMs)) storage.plan.set(plan);
            if (root.has("lost")) {
                List<LostEntry> cur = storage.lost.get();
                for (LostEntry e : lost) {
                    boolean dup = cur.stream().anyMatch(x -> x.whenEpochMs == e.whenEpochMs && x.key.equals(e.key) && x.count == e.count);
                    if (!dup) cur.add(e);
                }
                storage.lost.markDirty();
            }
        }
        if (root.has("config")) {
            JsonObject cfg = root.getAsJsonObject("config");
            if (cfg.has("categoryOverrides")) {
                Map<String, String> m = GsonHolder.GSON.fromJson(cfg.get("categoryOverrides"), new TypeToken<Map<String, String>>() {}.getType());
                if (mode == Mode.REPLACE) ConfigIO.get().categoryOverrides.clear();
                ConfigIO.get().categoryOverrides.putAll(m);
            }
            if (cfg.has("categoryColors")) {
                Map<String, Integer> m = GsonHolder.GSON.fromJson(cfg.get("categoryColors"), new TypeToken<Map<String, Integer>>() {}.getType());
                ConfigIO.get().categoryColors.putAll(m);
            }
            if (cfg.has("alwaysKeep")) {
                List<String> l = GsonHolder.GSON.fromJson(cfg.get("alwaysKeep"), new TypeToken<List<String>>() {}.getType());
                for (String s : l) if (!ConfigIO.get().alwaysKeep.contains(s)) ConfigIO.get().alwaysKeep.add(s);
            }
            ConfigIO.save();
        }
        storage.flushAll();
        storage.reloadAll();
        return new Result(true, (mode == Mode.REPLACE ? "Replaced" : "Merged") + " data from " + fileName + ": " + regions.size() + " regions, " + claims.size()
                + " claims, " + index.size() + " containers, " + items.size() + " item groups" + (plan != null && plan.exists() ? ", plan" : "") + ".");
    }

    private Path resolve(String fileName) {
        Path dir = storage.exportsDir();
        String[] candidates = {fileName, fileName + ".json", fileName + ".json.gz"};
        for (String c : candidates) {
            Path p = dir.resolve(c);
            if (Files.isRegularFile(p)) return p;
        }
        Path abs = Path.of(fileName);
        if (abs.isAbsolute() && Files.isRegularFile(abs)) return abs;
        return null;
    }

    private static <T> List<T> list(JsonObject root, String key, TypeToken<List<T>> type) {
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull()) return new ArrayList<>();
        List<T> l = GsonHolder.GSON.fromJson(e, type.getType());
        return l != null ? l : new ArrayList<>();
    }

    private void mergeRegions(List<Region> incoming) {
        List<Region> cur = storage.regions.get();
        Map<UUID, Region> byId = new HashMap<>();
        for (Region r : cur) byId.put(r.id, r);
        for (Region r : incoming) {
            Region local = byId.get(r.id);
            if (local == null) cur.add(r);
            else {
                // "newer wins" has no timestamp on regions; the import is explicit user intent, so it wins.
                cur.remove(local);
                cur.add(r);
            }
        }
        storage.regions.markDirty();
    }

    private void mergeClaims(List<ClaimAnchor> incoming) {
        List<ClaimAnchor> cur = storage.claims.get();
        for (ClaimAnchor a : incoming) {
            ClaimAnchor local = cur.stream().filter(x -> x.sameAnchor(a)).findFirst().orElse(null);
            if (local == null) cur.add(a);
            else if (a.importedEpochMs >= local.importedEpochMs) {
                local.area = a.area;
                local.importedEpochMs = a.importedEpochMs;
                if (a.boundRegionId != null) local.boundRegionId = a.boundRegionId;
            }
        }
        storage.claims.markDirty();
    }

    private void mergeIndex(List<ContainerEntry> incoming) {
        List<ContainerEntry> cur = storage.index.get();
        for (ContainerEntry e : incoming) {
            ContainerEntry local = null;
            for (ContainerEntry x : cur) {
                if (x.id.equals(e.id) || (e.entityId != null && e.entityId.equals(x.entityId)) || (!e.kind.isEntity() && !x.kind.isEntity() && x.coversPos(e.dimension, e.pos))) {
                    local = x;
                    break;
                }
            }
            if (local == null) cur.add(e);
            else if (e.lastSeenEpochMs > local.lastSeenEpochMs) {
                cur.remove(local);
                e.manualPin = e.manualPin || local.manualPin;
                cur.add(e);
            } else {
                local.manualPin = local.manualPin || e.manualPin;
            }
        }
        storage.index.markDirty();
    }

    private void mergeItems(List<ItemGroup> incoming) {
        List<ItemGroup> cur = storage.items.get();
        for (ItemGroup g : incoming) {
            ItemGroup local = cur.stream().filter(x -> x.groupId.equals(g.groupId)).findFirst().orElse(null);
            if (local == null) {
                cur.add(g);
                continue;
            }
            for (ItemKey k : g.members) if (!local.members.contains(k)) local.members.add(k);
            if (local.categoryOverride == null) local.categoryOverride = g.categoryOverride;
            if (local.loreHint == null) local.loreHint = g.loreHint;
        }
        storage.items.markDirty();
    }
}
