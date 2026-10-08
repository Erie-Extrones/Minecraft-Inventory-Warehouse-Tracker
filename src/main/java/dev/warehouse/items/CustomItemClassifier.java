package dev.warehouse.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.warehouse.organizer.Categorizer;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decides what a fingerprinted (non-default) item really is, from the component samples the mod collected.
 * Pure logic: no Minecraft client dependencies, so it can be checked offline.
 *
 * <ul>
 *   <li>Vanilla variants (enchanted books, potions, dyed leather, fireworks...) keep their base category.</li>
 *   <li>Plugin items are recognised by custom_data markers (ExecutableItems, ExcellentCrates, ItemsAdder...),
 *       custom_model_data / item_model, and name or lore keywords.</li>
 *   <li>Gear (attributes, enchantments, equippable, tool/weapon base) stays Tools / Armor.</li>
 * </ul>
 */
public final class CustomItemClassifier {
    public record Result(String category, String family, boolean vanillaVariant, String reason) {}

    /** Components a vanilla item may legitimately carry without being a plugin item. */
    private static final java.util.Set<String> VANILLA_VARIANT_COMPONENTS = java.util.Set.of(
            "minecraft:stored_enchantments", "minecraft:enchantments", "minecraft:potion_contents", "minecraft:dyed_color", "minecraft:fireworks",
            "minecraft:firework_explosion", "minecraft:trim", "minecraft:written_book_content", "minecraft:writable_book_content", "minecraft:map_id",
            "minecraft:map_color", "minecraft:map_decorations", "minecraft:charged_projectiles", "minecraft:pot_decorations", "minecraft:banner_patterns",
            "minecraft:base_color", "minecraft:bees", "minecraft:ominous_bottle_amplifier", "minecraft:suspicious_stew_effects", "minecraft:instrument",
            "minecraft:jukebox_playable", "minecraft:damage", "minecraft:repair_cost", "minecraft:custom_name", "minecraft:lore", "minecraft:container",
            "minecraft:bundle_contents", "minecraft:block_entity_data", "minecraft:block_state", "minecraft:profile", "minecraft:note_block_sound",
            "minecraft:lodestone_tracker", "minecraft:enchantment_glint_override", "minecraft:unbreakable", "minecraft:entity_data", "minecraft:bucket_entity_data",
            "minecraft:debug_stick_state", "minecraft:recipes", "minecraft:intangible_projectile", "minecraft:axolotl/variant", "minecraft:cat/collar",
            "minecraft:tooltip_display", "minecraft:rarity", "minecraft:max_stack_size", "minecraft:max_damage", "minecraft:attribute_modifiers",
            "minecraft:custom_model_data", "minecraft:item_model", "minecraft:item_name", "minecraft:custom_data", "minecraft:equippable", "minecraft:glider",
            "minecraft:tooltip_style", "minecraft:use_remainder", "minecraft:use_cooldown", "minecraft:food", "minecraft:consumable", "minecraft:damage_resistant",
            "minecraft:weapon", "minecraft:tool", "minecraft:blocks_attacks", "minecraft:break_sound", "minecraft:provides_banner_patterns", "minecraft:provides_trim_material");

    /** Components whose presence marks a plugin item regardless of anything else. */
    private static final java.util.Set<String> PLUGIN_MARKERS = java.util.Set.of("minecraft:custom_data", "minecraft:custom_model_data", "minecraft:item_model");

    private static final Map<String, Result> CACHE = new HashMap<>();
    /** Server key the rules' optional {@code server} filter is matched against; null outside a game. */
    private static volatile @Nullable String currentServer;

    private CustomItemClassifier() {}

    public static void clearCache() {
        CACHE.clear();
    }

    public static void setServer(@Nullable String server) {
        currentServer = server;
        CACHE.clear();
    }

    /**
     * @param group        the item group holding samples for the key (may be null when no sample was ever captured)
     * @param key          the fingerprinted key
     * @param baseCategory the vanilla heuristic category of the base item
     * @param baseFamily   the vanilla heuristic sub-family of the base item
     */
    public static Result classify(@Nullable ItemGroup group, ItemKey key, String baseCategory, String baseFamily) {
        String cacheKey = key.asString() + "|" + baseCategory + "|" + (group != null ? group.groupId + ":" + (group.samples != null ? group.samples.size() : 0) : "-");
        Result r = CACHE.get(cacheKey);
        if (r == null) {
            r = compute(group, key, baseCategory, baseFamily);
            CACHE.put(cacheKey, r);
        }
        return r;
    }

    private static Result compute(@Nullable ItemGroup group, ItemKey key, String baseCategory, String baseFamily) {
        ItemSample sample = null;
        if (group != null && group.samples != null) {
            for (ItemSample s : group.samples) if (key.componentHash.equals(s.componentHash)) sample = s;
            if (sample == null && !group.samples.isEmpty()) sample = group.samples.get(0);
        }
        String name = (sample != null && sample.displayName != null ? sample.displayName : group != null ? group.displayName : "").toLowerCase(Locale.ROOT);
        String lore = sample != null && sample.lore != null ? String.join(" | ", sample.lore).toLowerCase(Locale.ROOT) : (group != null && group.loreHint != null ? group.loreHint.toLowerCase(Locale.ROOT) : "");
        JsonObject comps = parse(sample != null ? sample.componentsJson : null);

        boolean pluginMarked = false;
        boolean hasGearStats = false;
        String pluginId = null;
        String crateKeyId = null;
        if (comps != null) {
            for (String k : comps.keySet()) if (PLUGIN_MARKERS.contains(k)) pluginMarked = true;
            // A renamed item that also carries lore is a plugin or shop item in practice; players rarely write lore.
            if (comps.has("minecraft:custom_name") && comps.has("minecraft:lore")) pluginMarked = true;
            hasGearStats = comps.has("minecraft:equippable");
            JsonObject cd = obj(comps.get("minecraft:custom_data"));
            if (cd != null) {
                JsonObject pbv = obj(cd.get("PublicBukkitValues"));
                if (pbv != null) {
                    for (Map.Entry<String, JsonElement> en : pbv.entrySet()) {
                        String k = en.getKey();
                        if (k.endsWith("crate_key.id")) crateKeyId = str(en.getValue());
                        if (k.equals("executableitems:ei-id") || k.endsWith(":id") && pluginId == null) pluginId = str(en.getValue());
                    }
                }
                if (cd.has("itemsadder") && pluginId == null) pluginId = "itemsadder";
                if (cd.has("mmoitems") || cd.has("MMOITEMS_ITEM_ID")) pluginId = pluginId == null ? "mmoitems" : pluginId;
            }
        } else if (group != null) {
            // No sample (older data): treat obviously vanilla groups as vanilla, the rest as plugin items.
            pluginMarked = !(name.equals("enchanted book") || name.contains("potion") || name.contains("arrow") || name.endsWith("shulker box"));
        }

        // 0. Data-driven rules (jar defaults, config file, shared folder) win over everything below.
        CustomItemRules.Context ctx = new CustomItemRules.Context(key.itemId, name, lore, pluginId, crateKeyId,
                CustomItemRules.keyPaths(comps != null ? obj(comps.get("minecraft:custom_data")) : null), pluginMarked, baseCategory, baseFamily, currentServer);
        CustomItemRules.Rule rule = CustomItemRules.match(ctx);
        if (rule != null) return new Result(rule.category, CustomItemRules.family(rule, ctx, packPrefix(pluginId)), false, "rule " + rule.id);

        // 1. Vanilla variants keep their base category.
        if (!pluginMarked) return new Result(baseCategory, baseFamily, true, "vanilla variant");

        // 2. Gear keeps its functional category.
        boolean gearBase = baseCategory.equals(Categorizer.TOOLS) || baseCategory.equals(Categorizer.ARMOR);
        if (gearBase || hasGearStats) {
            String fam = packPrefix(pluginId);
            return new Result(gearBase ? baseCategory : Categorizer.ARMOR, fam != null ? fam : baseFamily, false, "custom gear");
        }

        // 3. Keys, vouchers, tokens, tickets and other currency-like items.
        if (crateKeyId != null || name.contains(" key") || name.startsWith("key") || name.contains("voucher") || name.contains("token") || name.contains("ticket")
                || name.contains("coin") || lore.contains("currency") || lore.contains("open at /warp") || lore.contains("/warp crates")) {
            String fam = crateKeyId != null ? "crate-key" : name.contains("voucher") ? "voucher" : name.contains("token") || lore.contains("currency") ? "currency" : "key";
            return new Result(Categorizer.KEYS, fam, false, "key/currency");
        }

        // 4. Infinite / unlimited placeable blocks: file by the base block's colour or family.
        if (name.contains("infinite") || name.contains("unlimited") || name.contains("endless") || pluginId != null && (pluginId.startsWith("GLASS_") || pluginId.startsWith("CONCRETE_"))) {
            return new Result(Categorizer.INFINITE, baseFamily, false, "infinite block");
        }

        // 5. Cosmetics and collectibles: plushies, pendants, hats, pets, trophies.
        if (name.contains("plushie") || name.contains("plush") || name.contains("pendant") || name.contains("trophy") || name.contains("hat") && !name.contains("hatchet")
                || name.contains("cosmetic") || name.contains("pet ") || name.endsWith(" pet") || name.contains("crate") && !name.contains("key")
                || name.contains("booster") || name.contains("satchel") || name.contains("journal") || name.contains("jar") || name.contains("basket")) {
            String fam = packPrefix(pluginId);
            return new Result(Categorizer.COLLECTIBLES, fam != null ? fam : "collectible", false, "collectible");
        }

        // 6. Everything else from a plugin: Custom, clustered by plugin pack.
        String fam = packPrefix(pluginId);
        return new Result(Categorizer.CUSTOM, fam != null ? fam : (pluginId != null ? pluginId.toLowerCase(Locale.ROOT) : "custom"), false, "plugin item");
    }

    /** "EMBERFALL_SLIPPERSHEEP" -> "emberfall"; "ANIMEPLUSHIE_PLUSHIE2" -> "animeplushie". */
    static @Nullable String packPrefix(@Nullable String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return null;
        int i = pluginId.indexOf('_');
        String p = i > 0 ? pluginId.substring(0, i) : pluginId;
        return p.toLowerCase(Locale.ROOT);
    }

    private static @Nullable JsonObject parse(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static @Nullable JsonObject obj(@Nullable JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonElement e) {
        return e == null ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }
}
