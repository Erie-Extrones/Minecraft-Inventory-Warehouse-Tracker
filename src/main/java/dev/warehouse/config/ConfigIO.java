package dev.warehouse.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.warehouse.WarehouseClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Loads and saves {@link ModConfig} as config/warehouse/config.json. */
public final class ConfigIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static ModConfig config;

    private ConfigIO() {}

    public static Path rootDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(WarehouseClient.MOD_ID);
    }

    public static Path configFile() {
        return rootDir().resolve("config.json");
    }

    public static ModConfig get() {
        if (config == null) load();
        return config;
    }

    public static synchronized void load() {
        Path file = configFile();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                ModConfig loaded = GSON.fromJson(r, ModConfig.class);
                config = loaded != null ? loaded : new ModConfig();
            } catch (Exception e) {
                WarehouseClient.LOGGER.error("Failed to read {}, using defaults", file, e);
                config = new ModConfig();
            }
        } else {
            config = new ModConfig();
        }
        migrate(config);
        // Always rewrite so new fields show up with defaults.
        save();
    }

    /** Apply default additions introduced after the file was first written. */
    private static void migrate(ModConfig c) {
        if (c.configVersion < 2) {
            for (String id : new String[]{"minecraft:container", "minecraft:bundle_contents"}) {
                if (!c.strippedComponents.contains(id)) c.strippedComponents.add(id);
            }
            ModConfig.defaultCategoryColors().forEach((k, v) -> c.categoryColors.putIfAbsent(k, v));
            if (c.categoryPriorShares == null || c.categoryPriorShares.isEmpty()) c.categoryPriorShares = ModConfig.defaultPriorShares();
            c.configVersion = 2;
        }
        if (c.configVersion < 3) {
            ModConfig.defaultCategoryColors().forEach((k, v) -> c.categoryColors.putIfAbsent(k, v));
            if (c.categoryPriorShares == null) c.categoryPriorShares = new LinkedHashMap<>();
            ModConfig.defaultPriorShares().forEach((k, v) -> c.categoryPriorShares.putIfAbsent(k, v));
            c.configVersion = 3;
        }
    }

    public static synchronized void save() {
        if (config == null) return;
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(config, w);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            WarehouseClient.LOGGER.error("Failed to write {}", file, e);
        }
    }
}
