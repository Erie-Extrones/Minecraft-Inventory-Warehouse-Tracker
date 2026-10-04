package dev.warehouse.export;

import com.google.gson.JsonObject;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.storage.GsonHolder;
import dev.warehouse.storage.StorageManager;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.GZIPOutputStream;

/** Writes a single bundle file with everything for the current server. */
public final class Exporter {
    public static final int FORMAT_VERSION = 1;
    private final StorageManager storage;

    public Exporter(StorageManager storage) {
        this.storage = storage;
    }

    /** @return the written file name, or null on failure. */
    public @Nullable String export(@Nullable String name, boolean includeLost) {
        if (!storage.isBound()) return null;
        storage.flushAll();
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("mod", WarehouseClient.MOD_ID);
        root.addProperty("exportedAt", System.currentTimeMillis());
        root.addProperty("serverKey", storage.serverKey());
        root.add("regions", GsonHolder.GSON.toJsonTree(storage.regions.get()));
        root.add("claims", GsonHolder.GSON.toJsonTree(storage.claims.get()));
        root.add("index", GsonHolder.GSON.toJsonTree(storage.index.get()));
        root.add("items", GsonHolder.GSON.toJsonTree(storage.items.get()));
        root.add("plan", GsonHolder.GSON.toJsonTree(storage.plan.get()));
        if (includeLost) root.add("lost", GsonHolder.GSON.toJsonTree(storage.lost.get()));
        JsonObject cfg = new JsonObject();
        cfg.add("categoryOverrides", GsonHolder.GSON.toJsonTree(ConfigIO.get().categoryOverrides));
        cfg.add("categoryColors", GsonHolder.GSON.toJsonTree(ConfigIO.get().categoryColors));
        cfg.add("alwaysKeep", GsonHolder.GSON.toJsonTree(ConfigIO.get().alwaysKeep));
        cfg.add("strippedComponents", GsonHolder.GSON.toJsonTree(ConfigIO.get().strippedComponents));
        cfg.add("strippedLorePatterns", GsonHolder.GSON.toJsonTree(ConfigIO.get().strippedLorePatterns));
        root.add("config", cfg);

        String json = GsonHolder.COMPACT.toJson(root);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        boolean gzip = bytes.length > 5 * 1024 * 1024;
        String base = name != null && !name.isBlank() ? sanitize(name) : storage.serverKey() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String fileName = base + (gzip ? ".json.gz" : ".json");
        Path out = storage.exportsDir().resolve(fileName);
        try {
            Files.createDirectories(out.getParent());
            if (gzip) {
                try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(out)); Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                    w.write(json);
                }
            } else {
                Files.writeString(out, json, StandardCharsets.UTF_8);
            }
            return fileName;
        } catch (IOException e) {
            WarehouseClient.LOGGER.error("Export failed", e);
            return null;
        }
    }

    static String sanitize(String s) {
        String t = s.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (t.endsWith(".json")) t = t.substring(0, t.length() - 5);
        if (t.endsWith(".json.gz")) t = t.substring(0, t.length() - 8);
        return t.isEmpty() ? "export" : t;
    }
}
