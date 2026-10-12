package dev.warehouse.shops;

import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.NestedContents;
import dev.warehouse.prices.PriceKeys;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a shop sells as one unit: a plain item, or a container item with specific contents ("Shulker Box of Oak Log").
 * Two packed units are the same when the box item and the per-box contents match.
 */
public final class StockUnit {
    public final ItemKey key;
    public final @Nullable String signature;
    public final @Nullable List<StackRecord> perBox;

    private StockUnit(ItemKey key, @Nullable String signature, @Nullable List<StackRecord> perBox) {
        this.key = key;
        this.signature = signature;
        this.perBox = perBox;
    }

    public static StockUnit plain(ItemKey key) {
        return new StockUnit(key, null, null);
    }

    /** From an indexed record; a slot of several identical boxes is normalised to one box's contents. */
    public static StockUnit of(StackRecord r) {
        if (r.nested == null || r.nested.isEmpty()) return plain(r.key);
        List<StackRecord> per = perBox(r.nested, Math.max(1, r.count));
        return new StockUnit(r.key, signature(per), per);
    }

    public static StockUnit of(ItemStack st) {
        List<StackRecord> nested = NestedContents.of(st);
        if (nested.isEmpty()) return plain(Fingerprinter.key(st));
        List<StackRecord> per = perBox(nested, Math.max(1, st.getCount()));
        return new StockUnit(Fingerprinter.key(st), signature(per), per);
    }

    public static StockUnit parse(String unitKey, @Nullable List<StackRecord> template) {
        int i = unitKey.indexOf('#');
        if (i < 0) return plain(ItemKey.parse(unitKey));
        return new StockUnit(ItemKey.parse(unitKey.substring(0, i)), unitKey.substring(i + 1), template);
    }

    private static List<StackRecord> perBox(List<StackRecord> nested, int boxes) {
        List<StackRecord> out = new ArrayList<>();
        for (StackRecord n : nested) out.add(new StackRecord(n.key, Math.max(1, n.count / boxes), n.slot, null, n.displayName));
        return out;
    }

    static String signature(List<StackRecord> perBox) {
        Map<String, Integer> m = new TreeMap<>();
        for (StackRecord n : perBox) m.merge(n.key.asString(), n.count, Integer::sum);
        return m.toString();
    }

    public boolean packed() {
        return signature != null;
    }

    public String unitKey() {
        return packed() ? key.asString() + "#" + signature : key.asString();
    }

    /** "Shulker Box of Oak Log", "Shulker Box (mixed)", or the item name. */
    public String name(@Nullable String boxName) {
        String base = boxName != null ? boxName : PriceKeys.displayName(key);
        if (!packed() || perBox == null || perBox.isEmpty()) return base;
        String first = null;
        for (StackRecord n : perBox) {
            String nm = n.displayName != null ? n.displayName : PriceKeys.displayName(n.key);
            if (first == null) first = nm;
            else if (!first.equals(nm)) return base + " (mixed)";
        }
        return base + " of " + first;
    }

    /** Units of this kind in a container. Plain units count loose stacks, and with {@code includeNested} also what sits inside boxes. */
    public int countIn(ContainerEntry e, boolean includeNested) {
        int n = 0;
        for (StackRecord r : e.contents) {
            boolean hasNested = r.nested != null && !r.nested.isEmpty();
            if (packed()) {
                if (hasNested && key.equals(r.key) && signature.equals(signature(perBox(r.nested, Math.max(1, r.count))))) n += r.count;
            } else if (key.equals(r.key) && !hasNested) {
                n += r.count;
            } else if (includeNested && hasNested) {
                for (StackRecord x : r.nested) if (key.equals(x.key)) n += x.count;
            }
        }
        return n;
    }

    /** How many of this unit an inventory stack is: the stack count when it matches, else 0. */
    public int matches(ItemStack st) {
        if (st.isEmpty()) return 0;
        return equals(of(st)) ? st.getCount() : 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StockUnit u && key.equals(u.key) && java.util.Objects.equals(signature, u.signature);
    }

    @Override
    public int hashCode() {
        return key.hashCode() * 31 + (signature == null ? 0 : signature.hashCode());
    }

    @Override
    public String toString() {
        return unitKey();
    }
}
