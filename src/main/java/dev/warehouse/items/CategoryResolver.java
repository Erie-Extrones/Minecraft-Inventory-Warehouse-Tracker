package dev.warehouse.items;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.organizer.Categorizer;
import dev.warehouse.organizer.Plan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import dev.warehouse.index.StackRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Category for a key: group override -> per-server item/group override -> global config override
 * -> heuristics (gear keeps its type) -> Custom if fingerprinted -> Misc.
 */
public final class CategoryResolver {
    private final ItemGroups groups;
    private final Supplier<Plan> plan;
    private final Map<ItemKey, String> cache = new HashMap<>();
    private final Map<ItemKey, String> subFamilyCache = new HashMap<>();

    public CategoryResolver(ItemGroups groups, Supplier<Plan> plan) {
        this.groups = groups;
        this.plan = plan;
    }

    public void invalidate() {
        cache.clear();
        subFamilyCache.clear();
        CustomItemClassifier.clearCache();
    }

    public String categoryOf(ItemKey key) {
        String c = cache.get(key);
        if (c == null) {
            c = compute(key);
            cache.put(key, c);
        }
        return c;
    }

    /** Category honouring shulker contents: a box that is mostly one category files under that category. */
    public String categoryOf(ItemKey key, @Nullable List<StackRecord> nested) {
        if (nested == null || nested.isEmpty() || !ConfigIO.get().shulkerByContents) return categoryOf(key);
        Map<String, Integer> weights = new HashMap<>();
        int total = 0;
        for (StackRecord n : nested) {
            int w = Math.max(1, (int) Math.ceil(n.count / (double) Math.max(1, dev.warehouse.organizer.Allocator.maxStack(n.key))));
            weights.merge(categoryOf(n.key, n.nested), w, Integer::sum);
            total += w;
        }
        String best = null;
        int bestW = 0;
        for (Map.Entry<String, Integer> en : weights.entrySet()) if (en.getValue() > bestW) {
            best = en.getKey();
            bestW = en.getValue();
        }
        if (best != null && bestW * 2 >= total) return best;
        return categoryOf(key);
    }

    public String categoryOf(ItemStack stack) {
        ItemKey key = Fingerprinter.key(stack);
        learn(stack, key);
        return categoryOf(key, NestedContents.of(stack));
    }

    /**
     * Register a plugin item seen in the player's hand or inventory so its display name and components are known before it
     * ever sits in a chest. Without this a fresh fingerprint classifies as a plain vanilla variant until it is indexed.
     */
    public void learn(ItemStack stack, ItemKey key) {
        if (stack.isEmpty() || key.isVanilla() || groups.groupFor(key) != null) return;
        groups.record(stack, key);
        cache.remove(key);
        subFamilyCache.remove(key);
    }

    public String subFamilyOf(ItemKey key) {
        String s = subFamilyCache.get(key);
        if (s == null) {
            ItemStack sample = sampleStack(key);
            s = sample == null ? key.itemId : Categorizer.subFamily(sample);
            if (!key.isVanilla()) {
                CustomItemClassifier.Result r = CustomItemClassifier.classify(groups.groupFor(key), key, sample == null ? Categorizer.MISC : Categorizer.categorize(sample), s);
                s = r.family();
            }
            subFamilyCache.put(key, s);
        }
        return s;
    }

    private String compute(ItemKey key) {
        ItemGroup g = groups.groupFor(key);
        if (g != null && g.categoryOverride != null && !g.categoryOverride.isBlank()) return g.categoryOverride;
        Plan p = plan.get();
        if (p != null) {
            if (g != null) {
                String o = p.categoryOverrides.get(g.groupId);
                if (o != null) return o;
            }
            String o = p.categoryOverrides.get(key.asString());
            if (o != null) return o;
            o = p.categoryOverrides.get(key.itemId);
            if (o != null) return o;
        }
        Map<String, String> global = ConfigIO.get().categoryOverrides;
        if (g != null && global.containsKey(g.groupId)) return global.get(g.groupId);
        if (global.containsKey(key.asString())) return global.get(key.asString());
        if (global.containsKey(key.itemId)) return global.get(key.itemId);

        ItemStack sample = sampleStack(key);
        String heuristic = sample == null ? Categorizer.MISC : Categorizer.categorize(sample);
        if (!key.isVanilla()) {
            String fam = sample == null ? key.itemId : Categorizer.subFamily(sample);
            return CustomItemClassifier.classify(g, key, heuristic, fam).category();
        }
        return heuristic;
    }

    public static @Nullable ItemStack sampleStack(ItemKey key) {
        Identifier id = Identifier.tryParse(key.itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return null;
        return new ItemStack(item);
    }

    public @Nullable ItemGroup groupFor(ItemKey key) {
        return groups.groupFor(key);
    }

    public ItemGroups groups() {
        return groups;
    }
}
