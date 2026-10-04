package dev.warehouse.items;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.organizer.Categorizer;
import dev.warehouse.organizer.Plan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
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
    }

    public String categoryOf(ItemKey key) {
        String c = cache.get(key);
        if (c == null) {
            c = compute(key);
            cache.put(key, c);
        }
        return c;
    }

    public String subFamilyOf(ItemKey key) {
        String s = subFamilyCache.get(key);
        if (s == null) {
            ItemStack sample = sampleStack(key);
            s = sample == null ? key.itemId : Categorizer.subFamily(sample);
            ItemGroup g = groups.groupFor(key);
            if (g != null) s = g.groupId;
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
            // Custom gear keeps its functional category so the essentials rules work; everything else is Custom.
            if (heuristic.equals(Categorizer.TOOLS) || heuristic.equals(Categorizer.ARMOR)) return heuristic;
            return Categorizer.CUSTOM;
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
