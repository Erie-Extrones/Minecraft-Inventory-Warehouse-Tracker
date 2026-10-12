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

/**
 * Shops live in Shop regions; this keeps their price lists, thresholds and inventory history and builds the stock report.
 * Stock is counted in {@link StockUnit}s, so a chest of shulkers full of logs is "shulker of logs" units, not loose logs.
 */
public final class ShopManager {
    public enum Status { OK, LOW, OUT }

    /** One unit of a shop's stock report. {@code soldSinceLast} is the drop since the previous inventory, never negative. */
    public record ItemReport(StockUnit unit, String name, int count, int threshold, Status status, @Nullable Double sellPrice, boolean marketPrice,
                             double value, int soldSinceLast, int maxSeen) {
        public ItemKey key() {
            return unit.key;
        }
    }

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

    /** Current stock from the index, in units: loose stacks as their item, boxes with contents as packed units. */
    public Map<StockUnit, Integer> stock(Shop s, Map<StockUnit, String> namesOut) {
        Map<StockUnit, Integer> counts = new LinkedHashMap<>();
        for (ContainerEntry e : containers(s)) {
            for (StackRecord r : e.contents) {
                StockUnit u = StockUnit.of(r);
                counts.merge(u, r.count, Integer::sum);
                namesOut.putIfAbsent(u, u.name(r.displayName));
            }
        }
        return counts;
    }

    /** Key under which a unit's shop price and threshold are stored: packed units get their own, plain ones share the item's price key. */
    public static String priceKey(StockUnit u) {
        return u.packed() ? "unit:" + u.unitKey() : PriceKeys.of(u.key);
    }

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

    /** The shop's own price for a unit; a packed unit falls back to the price recorded for its box item (what QuickShop reports). */
    public @Nullable Double sellPrice(Shop s, StockUnit u) {
        Double p = s.sellPrices.get(priceKey(u));
        if (p == null && u.packed()) p = s.sellPrices.get(PriceKeys.of(u.key));
        return p;
    }

    /** Unit price for valuing stock: the shop's own price, else the market value (box plus contents for packed units). */
    public @Nullable Double unitPrice(Shop s, StockUnit u, boolean[] marketOut) {
        Double p = sellPrice(s, u);
        if (p != null) {
            marketOut[0] = false;
            return p;
        }
        marketOut[0] = true;
        return marketValue(u);
    }

    /**
     * The packed unit sold at a shop block: the dominant box of {@code boxKey}'s kind with contents in the indexed container at
     * the clicked block or next to it (ChestShop signs sit on or beside their chest). Null when none is known there.
     */
    public @Nullable StockUnit packedUnitNear(String dim, BlockPos pos, ItemKey boxKey) {
        Map<StockUnit, Integer> seen = new LinkedHashMap<>();
        List<BlockPos> probe = new ArrayList<>();
        probe.add(pos);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) probe.add(pos.relative(d));
        java.util.Set<UUID> done = new java.util.HashSet<>();
        for (BlockPos p : probe) {
            ContainerEntry e = WarehouseClient.index().atBlock(dim, p);
            if (e == null || !done.add(e.id)) continue;
            for (StackRecord r : e.contents) {
                if (r.nested == null || r.nested.isEmpty() || !r.key.itemId.equals(boxKey.itemId)) continue;
                seen.merge(StockUnit.of(r), r.count, Integer::sum);
            }
        }
        StockUnit best = null;
        for (Map.Entry<StockUnit, Integer> en : seen.entrySet()) if (best == null || en.getValue() > seen.get(best)) best = en.getKey();
        return best;
    }

    public static @Nullable Double marketValue(StockUnit u) {
        Valuation val = WarehouseClient.valuation();
        if (u.packed()) {
            // Prices seen for exactly this packed box beat box-plus-contents arithmetic.
            Valuation.Value direct = val.valueForKeys(List.of(priceKey(u)));
            if (direct != null) return direct.unit();
        }
        Valuation.Value box = val.unitValue(u.key);
        if (!u.packed() || u.perBox == null) return box != null ? box.unit() : null;
        double sum = box != null ? box.unit() : 0;
        boolean any = box != null;
        for (StackRecord n : u.perBox) {
            Valuation.Value v = val.unitValue(n.key);
            if (v != null) {
                sum += v.unit() * n.count;
                any = true;
            }
        }
        return any ? sum : null;
    }

    public int maxSeen(Shop s, StockUnit u, int current) {
        int max = current;
        String k = u.unitKey();
        for (Shop.Snapshot snap : s.history) max = Math.max(max, snap.counts.getOrDefault(k, 0));
        return max;
    }

    public int threshold(Shop s, StockUnit u, int maxSeen) {
        Integer o = s.restockMin.get(priceKey(u));
        if (o == null && u.packed()) o = s.restockMin.get(PriceKeys.of(u.key));
        if (o != null) return o;
        double f = Math.max(0, Math.min(1, ConfigIO.get().shopRestockFraction));
        return Math.max(1, (int) Math.floor(maxSeen * f));
    }

    public Report report(Shop s) {
        Region r = region(s);
        Map<StockUnit, String> names = new LinkedHashMap<>();
        Map<StockUnit, Integer> stock = stock(s, names);
        Shop.Snapshot last = s.history.isEmpty() ? null : s.history.get(s.history.size() - 1);
        // Units that were stocked before but are gone now still belong on the report as OUT.
        if (last != null) for (Map.Entry<String, Integer> en : last.counts.entrySet()) {
            StockUnit u = StockUnit.parse(en.getKey(), last.templates != null ? last.templates.get(en.getKey()) : null);
            if (!stock.containsKey(u) && en.getValue() > 0) {
                stock.put(u, 0);
                names.putIfAbsent(u, last.names.getOrDefault(en.getKey(), u.name(null)));
            }
        }
        List<ItemReport> items = new ArrayList<>();
        double value = 0, revenue = 0;
        int low = 0, out = 0;
        for (Map.Entry<StockUnit, Integer> en : stock.entrySet()) {
            StockUnit u = en.getKey();
            int count = en.getValue();
            int maxSeen = maxSeen(s, u, count);
            int threshold = threshold(s, u, maxSeen);
            Status status = count == 0 ? Status.OUT : count < threshold ? Status.LOW : Status.OK;
            if (status == Status.LOW) low++;
            if (status == Status.OUT) out++;
            boolean[] market = {false};
            Double unit = unitPrice(s, u, market);
            double v = unit != null ? unit * count : 0;
            value += v;
            int sold = last != null ? Math.max(0, last.counts.getOrDefault(u.unitKey(), 0) - count) : 0;
            if (unit != null) revenue += sold * unit;
            items.add(new ItemReport(u, names.getOrDefault(u, u.name(null)), count, threshold, status, unit, market[0], v, sold, maxSeen));
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
            String k = i.unit().unitKey();
            snap.counts.put(k, i.count());
            snap.names.put(k, i.name());
            if (i.unit().packed() && i.unit().perBox != null) snap.templates.put(k, i.unit().perBox);
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
