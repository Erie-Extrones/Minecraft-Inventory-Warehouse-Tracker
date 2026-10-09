package dev.warehouse.prices;

import dev.warehouse.WarehouseClient;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps item keys and shop item names to the keys prices are stored under. Custom items are priced per group, not per fingerprint. */
public final class PriceKeys {
    private static Map<String, String> vanillaByName;

    private PriceKeys() {}

    public static String of(ItemKey key) {
        if (key.isVanilla()) return "item:" + key.itemId;
        ItemGroup g = WarehouseClient.categories().groupFor(key);
        return g != null ? "group:" + g.groupId : "key:" + key.asString();
    }

    /** Price keys to try for an item, most specific first. */
    public static List<String> candidates(ItemKey key) {
        return List.of(of(key), "name:" + displayName(key).toLowerCase(Locale.ROOT).trim());
    }

    public static String displayName(ItemKey key) {
        if (!key.isVanilla()) {
            ItemGroup g = WarehouseClient.categories().groupFor(key);
            if (g != null) return g.displayName;
        }
        ItemStack s = CategoryResolver.sampleStack(key);
        return s != null ? s.getHoverName().getString() : key.itemId;
    }

    /** Resolve a name seen in chat or on a sign: a vanilla item by display name, then a custom item group, else a name key. */
    public static String fromName(String name) {
        String n = name.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        String vanilla = vanillaByName().get(lower);
        if (vanilla != null) return "item:" + vanilla;
        for (ItemGroup g : WarehouseClient.itemGroups().all()) if (g.displayName != null && g.displayName.trim().equalsIgnoreCase(n)) return "group:" + g.groupId;
        Identifier id = Identifier.tryParse(lower.replace(' ', '_'));
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) return "item:" + id;
        return "name:" + lower;
    }

    private static synchronized Map<String, String> vanillaByName() {
        if (vanillaByName == null) {
            Map<String, String> m = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) continue;
                m.putIfAbsent(new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT), id.toString());
            }
            vanillaByName = m;
        }
        return vanillaByName;
    }
}
