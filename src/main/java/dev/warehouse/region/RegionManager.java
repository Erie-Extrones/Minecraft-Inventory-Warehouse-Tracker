package dev.warehouse.region;

import dev.warehouse.storage.JsonStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** CRUD + spatial queries over the persisted region list. */
public final class RegionManager {
    private final JsonStore<List<Region>> store;

    public RegionManager(JsonStore<List<Region>> store) {
        this.store = store;
    }

    public List<Region> all() {
        return store.get();
    }

    public static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }

    public Region create(String name, RegionType type, String dimension, BlockPos a, BlockPos b) {
        Region r = new Region(UUID.randomUUID(), name, type, dimension, a, b);
        store.get().add(r);
        store.markDirty();
        return r;
    }

    public void add(Region r) {
        store.get().removeIf(x -> x.id.equals(r.id));
        store.get().add(r);
        store.markDirty();
    }

    public boolean delete(String name) {
        boolean removed = store.get().removeIf(r -> r.name.equalsIgnoreCase(name));
        if (removed) store.markDirty();
        return removed;
    }

    public boolean deleteById(UUID id) {
        boolean removed = store.get().removeIf(r -> r.id.equals(id));
        if (removed) store.markDirty();
        return removed;
    }

    public boolean rename(String oldName, String newName) {
        Optional<Region> r = byName(oldName);
        if (r.isEmpty()) return false;
        r.get().name = newName;
        store.markDirty();
        return true;
    }

    public Optional<Region> byName(String name) {
        return store.get().stream().filter(r -> r.name.equalsIgnoreCase(name)).findFirst();
    }

    public @Nullable Region byId(@Nullable UUID id) {
        if (id == null) return null;
        for (Region r : store.get()) if (r.id.equals(id)) return r;
        return null;
    }

    public String nextAutoName(RegionType type) {
        String base = type == RegionType.WAREHOUSE ? "Warehouse " : "Claim ";
        int n = 1;
        while (true) {
            String candidate = base + n;
            if (byName(candidate).isEmpty()) return candidate;
            n++;
        }
    }

    /** Smallest-volume region containing the position; warehouse preferred over claim on ties. */
    public @Nullable Region regionAt(String dimension, BlockPos pos) {
        Region best = null;
        for (Region r : store.get()) {
            if (!r.contains(dimension, pos)) continue;
            if (best == null) {
                best = r;
                continue;
            }
            if (r.type == RegionType.WAREHOUSE && best.type != RegionType.WAREHOUSE) best = r;
            else if (r.type == best.type && r.volume() < best.volume()) best = r;
        }
        return best;
    }

    public @Nullable Region warehouseAt(String dimension, BlockPos pos) {
        Region best = null;
        for (Region r : store.get()) {
            if (r.type != RegionType.WAREHOUSE || !r.contains(dimension, pos)) continue;
            if (best == null || r.volume() < best.volume()) best = r;
        }
        return best;
    }

    public boolean isInsideAny(String dimension, BlockPos pos) {
        return regionAt(dimension, pos) != null;
    }

    public List<Region> ofType(RegionType type) {
        List<Region> out = new ArrayList<>();
        for (Region r : store.get()) if (r.type == type) out.add(r);
        return out;
    }

    public List<Region> inDimension(String dimension) {
        List<Region> out = new ArrayList<>();
        for (Region r : store.get()) if (r.dimension.equals(dimension)) out.add(r);
        return out;
    }

    public void markDirty() {
        store.markDirty();
    }

    public static String sanitizeName(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).equals(s.trim()) ? s.trim() : s.trim();
    }
}
