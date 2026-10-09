package dev.warehouse.storage;

import com.google.gson.reflect.TypeToken;
import dev.warehouse.WarehouseClient;
import dev.warehouse.claims.ClaimAnchor;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.inventory.LostEntry;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.organizer.Plan;
import dev.warehouse.region.Region;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns every per-server JSON store. Bound on join, flushed and unbound on disconnect,
 * debounced save after mutations (call {@link #markDirty(JsonStore)} / store.markDirty()).
 */
public final class StorageManager {
    private static final long DEBOUNCE_MS = 1500;

    public final JsonStore<List<Region>> regions = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<Region>>() {}.getType(), ArrayList::new);
    public final JsonStore<List<ClaimAnchor>> claims = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<ClaimAnchor>>() {}.getType(), ArrayList::new);
    public final JsonStore<List<ContainerEntry>> index = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<ContainerEntry>>() {}.getType(), ArrayList::new);
    public final JsonStore<List<ItemGroup>> items = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<ItemGroup>>() {}.getType(), ArrayList::new);
    public final JsonStore<Plan> plan = new JsonStore<>(GsonHolder.GSON, Plan.class, Plan::empty);
    public final JsonStore<List<LostEntry>> lost = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<LostEntry>>() {}.getType(), ArrayList::new);
    public final JsonStore<List<dev.warehouse.prices.PriceObservation>> prices = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<dev.warehouse.prices.PriceObservation>>() {}.getType(), ArrayList::new);
    public final JsonStore<List<dev.warehouse.shops.Shop>> shops = new JsonStore<>(GsonHolder.GSON, new TypeToken<List<dev.warehouse.shops.Shop>>() {}.getType(), ArrayList::new);

    private final List<JsonStore<?>> all = List.of(regions, claims, index, items, plan, lost, prices, shops);
    private String serverKey;
    private Path serverDir;
    private final List<Runnable> onBindListeners = new ArrayList<>();

    public boolean isBound() {
        return serverDir != null;
    }

    public String serverKey() {
        return serverKey;
    }

    public Path serverDir() {
        return serverDir;
    }

    public Path exportsDir() {
        return ConfigIO.rootDir().resolve("exports");
    }

    public void onBind(Runnable r) {
        onBindListeners.add(r);
    }

    public void bind(Minecraft mc) {
        String key = ServerKey.current(mc);
        if (key.equals(serverKey) && serverDir != null) return;
        if (serverDir != null) unbind();
        serverKey = key;
        serverDir = ConfigIO.rootDir().resolve(key);
        regions.bind(serverDir.resolve("regions.json"));
        claims.bind(serverDir.resolve("claims.json"));
        index.bind(serverDir.resolve("index.json"));
        items.bind(serverDir.resolve("items.json"));
        plan.bind(serverDir.resolve("plan.json"));
        lost.bind(serverDir.resolve("lost.json"));
        prices.bind(serverDir.resolve("prices.json"));
        shops.bind(serverDir.resolve("shops.json"));
        try {
            java.nio.file.Files.createDirectories(serverDir);
        } catch (Exception e) {
            WarehouseClient.LOGGER.error("Could not create {}", serverDir, e);
        }
        WarehouseClient.LOGGER.info("Warehouse storage bound to {}", serverDir);
        for (Runnable r : onBindListeners) {
            try {
                r.run();
            } catch (Exception e) {
                WarehouseClient.LOGGER.error("onBind listener failed", e);
            }
        }
    }

    public void unbind() {
        if (serverDir == null) return;
        for (JsonStore<?> s : all) s.unbind();
        WarehouseClient.LOGGER.info("Warehouse storage unbound from {}", serverDir);
        serverKey = null;
        serverDir = null;
    }

    public void tick() {
        if (serverDir == null) return;
        for (JsonStore<?> s : all) s.tick(DEBOUNCE_MS);
    }

    public void flushAll() {
        for (JsonStore<?> s : all) s.flush();
    }

    public void markDirty(JsonStore<?> store) {
        store.markDirty();
    }

    /** Reload every store from disk (used after import). */
    public void reloadAll() {
        if (serverDir == null) return;
        Path dir = serverDir;
        regions.bind(dir.resolve("regions.json"));
        claims.bind(dir.resolve("claims.json"));
        index.bind(dir.resolve("index.json"));
        items.bind(dir.resolve("items.json"));
        plan.bind(dir.resolve("plan.json"));
        lost.bind(dir.resolve("lost.json"));
        for (Runnable r : onBindListeners) r.run();
    }
}
