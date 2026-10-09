package dev.warehouse.shops;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.ItemKey;
import dev.warehouse.prices.PriceKeys;
import dev.warehouse.prices.Valuation;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import dev.warehouse.storage.JsonStore;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shops live in Shop regions; this keeps their price lists, thresholds and inventory history and builds the stock report. */
public final class ShopManager {
    public enum Status { OK, LOW, OUT }

    /** One item of a shop's stock report. {@code soldSinceLast} is the drop since the previous inventory, never negative. */
    public record ItemReport(ItemKey key, String name, int count, int threshold, Status status, @Nullable Double sellPrice, boolean marketPrice,
                             double value, int soldSinceLast, int maxSeen) {}

    public record Report(Shop shop, Region region, List<ItemReport> items, int containers, double value, int low, int out, double revenueSinceLast,
                         long sinceEpochMs) {}

    private static final int MAX_HISTORY = 60;

    private final JsonStore<List<Shop>> store;
    private final RegionManager regions;

    public ShopManager(JsonStore<List<Shop>> store, RegionManager regions) {
        this.store = store;
        this.regions = regions;
    }

    public List<Shop> all() {
        List<Shop> out = new ArrayList<>();
        for (Shop s : store.get()) if (regions.byId(s.regionId) != null) out.add(s);
        return out;
    }

    public @Nullable Shop byId(@Nullable UUID regionId) {
        if (regionId == null) return null;
        for (Shop s : store.get()) if (s.regionId.equals(regionId)) return s;
        return null;
    }

    /** The shop record for a Shop region, created on first use. */
    public @Nullable Shop forRegion(@Nullable Region r) {
        if (r == null || r.type != RegionType.SHOP) return null;
        Shop s = byId(r.id);
        if (s == null) {
            s = new Shop(r.id);
            store.get().add(s);
            store.markDirty();
        }
        return s;
    }

    public @Nullable Shop byName(String name) {
        return regions.byName(name).map(this::forRegion).orElse(null);
    }

    public @Nullable Region region(Shop s) {
        return regions.byId(s.regionId);
    }

    public String name(Shop s) {
        Region r = region(s);
        return r != null ? r.name : "Shop";
    }

    public @Nullable Shop shopAt(String dim, BlockPos pos) {
        Region best = null;
        for (Region r : regions.ofType(RegionType.SHOP)) {
            if (!r.contains(dim, pos)) continue;
            if (best == null || r.volume() < best.volume()) best = r;
        }
        return forRegion(best);
    }

    /** Nearest shop region whose box is within {@code maxDist} blocks of the position. */
    public @Nullable Shop nearest(String dim, BlockPos pos, int maxDist) {
        Region best = null;
        double bestD = Double.MAX_VALUE;
        for (Region r : regions.ofType(RegionType.SHOP)) {
            if (!r.dimension.equals(dim)) continue;
            double dx = Math.max(0, Math.max(r.minX - pos.getX(), pos.getX() - r.maxX));
            double dy = Math.max(0, Math.max(r.minY - pos.getY(), pos.getY() - r.maxY));
            double dz = Math.max(0, Math.max(r.minZ - pos.getZ(), pos.getZ() - r.maxZ));
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= maxDist && d < bestD) {
                best = r;
                bestD = d;
            }
        }
        return forRegion(best);
    }

    public List<ContainerEntry> containers(Shop s) {
        List<ContainerEntry> out = new ArrayList<>();
        for (ContainerEntry e : WarehouseClient.index().inRegion(s.regionId)) if (!e.kind.isEntity()) out.add(e);
        return out;
    }

    /** Current stock from the index, shulker contents included. */
    public Map<ItemKey, Integer> stock(Shop s, Map<ItemKey, String> namesOut) {
        Map<ItemKey, Integer> counts = new LinkedHashMap<>();
        for (ContainerEntry e : containers(s)) for (StackRecord r : e.contents) add(counts, namesOut, r);
        return counts;
    }

    private static void add(Map<ItemKey, Integer> counts, Map<ItemKey, String> names, StackRecord r) {
        if (r.nested != null && !r.nested.isEmpty()) {
            for (StackRecord n : r.nested) add(counts, names, n);
            return;
        }
        counts.merge(r.key, r.count, Integer::sum);
        if (r.displayName != null) names.putIfAbsent(r.key, r.displayName);
    }

    /** Prices and thresholds are keyed by price key (see {@link PriceKeys}), so every variant of a custom item shares them. */
    public void setSellPrice(Shop s, String priceKey, @Nullable Double price) {
        if (price == null) s.sellPrices.remove(priceKey);
        else s.sellPrices.put(priceKey, price);
        store.markDirty();
    }

    public void setRestockMin(Shop s, String priceKey, @Nullable Integer min) {
        if (min == null) s.restockMin.remove(priceKey);
        else s.restockMin.put(priceKey, min);
        store.markDirty();
    }

    public @Nullable Double sellPrice(Shop s, ItemKey key) {
        return s.sellPrices.get(PriceKeys.of(key));
    }

    /** Unit price for valuing stock: the shop's own price, else the market value. */
    public @Nullable Double unitPrice(Shop s, ItemKey key, boolean[] marketOut) {
        Double p = sellPrice(s, key);
        if (p != null) {
            marketOut[0] = false;
            return p;
        }
        Valuation.Value v = WarehouseClient.valuation().unitValue(key);
        marketOut[0] = true;
        return v != null ? v.unit() : null;
    }

    public int maxSeen(Shop s, ItemKey key, int current) {
        int max = current;
        String k = key.asString();
        for (Shop.Snapshot snap : s.history) max = Math.max(max, snap.counts.getOrDefault(k, 0));
        return max;
    }

    public int threshold(Shop s, ItemKey key, int maxSeen) {
        Integer o = s.restockMin.get(PriceKeys.of(key));
        if (o != null) return o;
        double f = Math.max(0, Math.min(1, ConfigIO.get().shopRestockFraction));
        return Math.max(1, (int) Math.floor(maxSeen * f));
    }

    public Report report(Shop s) {
        Region r = region(s);
        Map<ItemKey, String> names = new LinkedHashMap<>();
        Map<ItemKey, Integer> stock = stock(s, names);
        Shop.Snapshot last = s.history.isEmpty() ? null : s.history.get(s.history.size() - 1);
        // Items that were stocked before but are gone now still belong on the report as OUT.
        if (last != null) for (Map.Entry<String, Integer> en : last.counts.entrySet()) {
            ItemKey k = ItemKey.parse(en.getKey());
            if (!stock.containsKey(k) && en.getValue() > 0) {
                stock.put(k, 0);
                names.putIfAbsent(k, last.names.getOrDefault(en.getKey(), PriceKeys.displayName(k)));
            }
        }
        List<ItemReport> items = new ArrayList<>();
        double value = 0, revenue = 0;
        int low = 0, out = 0;
        for (Map.Entry<ItemKey, Integer> en : stock.entrySet()) {
            ItemKey key = en.getKey();
            int count = en.getValue();
            int maxSeen = maxSeen(s, key, count);
            int threshold = threshold(s, key, maxSeen);
            Status status = count == 0 ? Status.OUT : count < threshold ? Status.LOW : Status.OK;
            if (status == Status.LOW) low++;
            if (status == Status.OUT) out++;
            boolean[] market = {false};
            Double unit = unitPrice(s, key, market);
            double v = unit != null ? unit * count : 0;
            value += v;
            int sold = last != null ? Math.max(0, last.counts.getOrDefault(key.asString(), 0) - count) : 0;
            if (unit != null) revenue += sold * unit;
            items.add(new ItemReport(key, names.getOrDefault(key, PriceKeys.displayName(key)), count, threshold, status, unit, market[0], v, sold, maxSeen));
        }
        items.sort(Comparator.comparing((ItemReport i) -> i.status() == Status.OUT ? 0 : i.status() == Status.LOW ? 1 : 2).thenComparing(Comparator.comparingDouble(ItemReport::value).reversed()));
        return new Report(s, r, items, containers(s).size(), value, low, out, revenue, last != null ? last.whenEpochMs : 0);
    }

    /** Store the current stock as the latest inventory check. */
    public Shop.Snapshot recordInventory(Shop s) {
        Report rep = report(s);
        Shop.Snapshot snap = new Shop.Snapshot();
        snap.whenEpochMs = System.currentTimeMillis();
        snap.containers = rep.containers();
        snap.value = rep.value();
        for (ItemReport i : rep.items()) {
            if (i.count() <= 0) continue;
            snap.counts.put(i.key().asString(), i.count());
            snap.names.put(i.key().asString(), i.name());
        }
        s.history.add(snap);
        while (s.history.size() > MAX_HISTORY) s.history.remove(0);
        s.lastInventoryEpochMs = snap.whenEpochMs;
        store.markDirty();
        return snap;
    }

    public boolean overdue(Shop s) {
        long hours = Math.max(1, ConfigIO.get().shopInventoryReminderHours);
        return System.currentTimeMillis() - s.lastInventoryEpochMs > hours * 3_600_000L;
    }

    public void remove(UUID regionId) {
        if (store.get().removeIf(s -> s.regionId.equals(regionId))) store.markDirty();
    }
}
