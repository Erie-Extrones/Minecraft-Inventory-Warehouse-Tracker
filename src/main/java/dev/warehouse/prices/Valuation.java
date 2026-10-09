package dev.warehouse.prices;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns price observations into item, container, inventory and warehouse values. */
public final class Valuation {
    /** A unit value: the median of the configured side, or of the other side when that is all we have ({@code fallback}). */
    public record Value(double unit, boolean sell, boolean fallback, int count, double min, double max, long newestEpochMs) {}

    /** A sum and how much of it is backed by prices. */
    public record Total(double amount, int pricedTypes, int unpricedTypes, long unpricedItems) {}

    public record Unpriced(ItemKey key, String name, long count) {}

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private final PriceBook book;
    private final Map<ItemKey, Value> cache = new HashMap<>();
    private final Map<ItemKey, Boolean> missing = new HashMap<>();

    public Valuation(PriceBook book) {
        this.book = book;
        book.onChange(this::invalidate);
    }

    public void invalidate() {
        cache.clear();
        missing.clear();
    }

    public static String money(double amount) {
        String symbol = ConfigIO.get().currencySymbol;
        return (symbol == null ? "$" : symbol) + MONEY.format(amount);
    }

    public @Nullable Value unitValue(ItemKey key) {
        Value v = cache.get(key);
        if (v != null) return v;
        if (missing.containsKey(key)) return null;
        ModConfig cfg = ConfigIO.get();
        boolean sell = !"buy".equalsIgnoreCase(cfg.valuationSide);
        List<String> keys = PriceKeys.candidates(key);
        PriceBook.Stats s = book.stats(keys, sell);
        boolean fallback = false;
        if (s == null && cfg.valuationFallbackOtherSide) {
            s = book.stats(keys, !sell);
            fallback = s != null;
        }
        if (s == null) {
            missing.put(key, Boolean.TRUE);
            return null;
        }
        v = new Value(s.median(), s.sell(), fallback, s.count(), s.min(), s.max(), s.newestEpochMs());
        cache.put(key, v);
        return v;
    }

    public @Nullable Value unitValue(ItemStack stack) {
        return stack.isEmpty() ? null : unitValue(Fingerprinter.key(stack));
    }

    /** Value of a container's contents including what is inside shulker boxes (the boxes themselves are not counted). */
    public Total containerValue(ContainerEntry e) {
        Map<ItemKey, Long> counts = new LinkedHashMap<>();
        for (StackRecord s : e.contents) addCounts(counts, s);
        return total(counts);
    }

    public Total inventoryValue(Minecraft mc) {
        Map<ItemKey, Long> counts = new LinkedHashMap<>();
        if (mc.player == null) return total(counts);
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            counts.merge(Fingerprinter.key(st), (long) st.getCount(), Long::sum);
            for (StackRecord n : dev.warehouse.items.NestedContents.of(st)) addCounts(counts, n);
        }
        return total(counts);
    }

    /** Everything stored in containers inside regions of the given type (entities included, ender chest excluded). */
    public Total regionValue(RegionType type) {
        return total(regionCounts(type));
    }

    public Map<ItemKey, Long> regionCounts(RegionType type) {
        Map<ItemKey, Long> counts = new LinkedHashMap<>();
        for (ContainerEntry e : WarehouseClient.index().all()) {
            if (e.kind == dev.warehouse.index.ContainerKind.ENDER_CHEST) continue;
            Region r = WarehouseClient.regions().byId(e.regionId);
            if (r == null || r.type != type) continue;
            for (StackRecord s : e.contents) addCounts(counts, s);
        }
        return counts;
    }

    /** Item types without a price, most items first. */
    public List<Unpriced> unpriced(Map<ItemKey, Long> counts, int limit) {
        List<Unpriced> out = new ArrayList<>();
        for (Map.Entry<ItemKey, Long> en : counts.entrySet()) {
            if (unitValue(en.getKey()) == null) out.add(new Unpriced(en.getKey(), PriceKeys.displayName(en.getKey()), en.getValue()));
        }
        out.sort(Comparator.comparingLong(Unpriced::count).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public Total total(Map<ItemKey, Long> counts) {
        double sum = 0;
        int priced = 0, unpriced = 0;
        long unpricedItems = 0;
        for (Map.Entry<ItemKey, Long> en : counts.entrySet()) {
            Value v = unitValue(en.getKey());
            if (v == null) {
                unpriced++;
                unpricedItems += en.getValue();
            } else {
                priced++;
                sum += v.unit() * en.getValue();
            }
        }
        return new Total(sum, priced, unpriced, unpricedItems);
    }

    private static void addCounts(Map<ItemKey, Long> counts, StackRecord s) {
        if (s.nested != null && !s.nested.isEmpty()) {
            for (StackRecord n : s.nested) addCounts(counts, n);
            return;
        }
        counts.merge(s.key, (long) s.count, Long::sum);
    }

    /** "$20 each" with a short provenance suffix. */
    public static String describe(Value v) {
        return money(v.unit()) + " each (" + (v.sell() ? "sell" : "buy") + (v.fallback() ? " only" : "") + ", " + v.count() + " price" + (v.count() == 1 ? "" : "s") + ")";
    }
}
