package dev.warehouse.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Computes {@link ItemKey}s. The component *patch* (difference from the item's defaults) is hashed,
 * so vanilla items with default components get an empty hash. Volatile components and noisy lore lines
 * are stripped first (configurable).
 */
public final class Fingerprinter {
    private static Set<Identifier> stripSet;
    private static List<Pattern> lorePatterns;
    private static List<String[]> customDataPaths;
    private static List<String> cachedCustomDataSource;
    private static List<String> cachedStripSource;
    private static List<String> cachedLoreSource;

    private Fingerprinter() {}

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static ItemKey key(ItemStack stack) {
        if (stack.isEmpty()) return new ItemKey("minecraft:air", "");
        String id = itemId(stack);
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) return new ItemKey(id, "");
        refreshConfig();

        ItemStack work = stack;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty() && !lorePatterns.isEmpty()) {
            List<Component> kept = new ArrayList<>();
            for (Component line : lore.lines()) {
                String s = line.getString();
                boolean drop = false;
                for (Pattern p : lorePatterns) {
                    if (p.matcher(s).find()) {
                        drop = true;
                        break;
                    }
                }
                if (!drop) kept.add(line);
            }
            if (kept.size() != lore.lines().size()) {
                work = stack.copy();
                work.set(DataComponents.LORE, kept.isEmpty() ? null : new ItemLore(kept));
                patch = work.getComponentsPatch();
            }
        }
        CustomData customData = work.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty() && !customDataPaths.isEmpty()) {
            CompoundTag tag = customData.copyTag();
            boolean changed = false;
            for (String[] path : customDataPaths) changed |= removePath(tag, path, 0);
            if (changed) {
                if (work == stack) work = stack.copy();
                if (tag.isEmpty()) work.remove(DataComponents.CUSTOM_DATA);
                else work.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                patch = work.getComponentsPatch();
            }
        }
        patch = patch.forget(Fingerprinter::isStripped);
        if (patch.isEmpty()) return new ItemKey(id, "");

        try {
            DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            JsonElement json = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
            if (json == null) return new ItemKey(id, "unencodable");
            String canonical = canonicalize(json).toString();
            return new ItemKey(id, sha1(canonical));
        } catch (Exception e) {
            WarehouseClient.LOGGER.debug("Fingerprint failed for {}", id, e);
            return new ItemKey(id, "error");
        }
    }

    /** Remove a nested key from a compound tag; empty parents are dropped too. Returns true if anything was removed. */
    private static boolean removePath(CompoundTag tag, String[] path, int i) {
        String seg = path[i];
        if (i == path.length - 1) return tag.remove(seg) != null;
        CompoundTag child = tag.getCompound(seg).orElse(null);
        if (child == null) return false;
        boolean removed = removePath(child, path, i + 1);
        if (removed) {
            if (child.isEmpty()) tag.remove(seg);
            else tag.put(seg, child);
        }
        return removed;
    }

    private static boolean isStripped(DataComponentType<?> type) {
        Identifier k = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
        return k != null && stripSet.contains(k);
    }

    private static synchronized void refreshConfig() {
        ModConfig cfg = ConfigIO.get();
        if (stripSet == null || cachedStripSource != cfg.strippedComponents) {
            Set<Identifier> s = new HashSet<>();
            for (String id : cfg.strippedComponents) {
                Identifier i = Identifier.tryParse(id);
                if (i != null) s.add(i);
            }
            stripSet = s;
            cachedStripSource = cfg.strippedComponents;
        }
        if (customDataPaths == null || cachedCustomDataSource != cfg.strippedCustomDataPaths) {
            List<String[]> ps = new ArrayList<>();
            if (cfg.strippedCustomDataPaths != null) for (String p : cfg.strippedCustomDataPaths) {
                if (p == null || p.isBlank()) continue;
                ps.add(p.split("/"));
            }
            customDataPaths = ps;
            cachedCustomDataSource = cfg.strippedCustomDataPaths;
        }
        if (lorePatterns == null || cachedLoreSource != cfg.strippedLorePatterns) {
            List<Pattern> ps = new ArrayList<>();
            for (String p : cfg.strippedLorePatterns) {
                try {
                    ps.add(Pattern.compile(p));
                } catch (PatternSyntaxException e) {
                    WarehouseClient.LOGGER.warn("Bad lore strip regex '{}': {}", p, e.getMessage());
                }
            }
            lorePatterns = ps;
            cachedLoreSource = cfg.strippedLorePatterns;
        }
    }

    /** Sort object keys recursively so the hash is independent of encoding order. */
    static JsonElement canonicalize(JsonElement e) {
        if (e.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) sorted.put(en.getKey(), canonicalize(en.getValue()));
            JsonObject o = new JsonObject();
            sorted.forEach(o::add);
            return o;
        }
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) a.add(canonicalize(x));
            return a;
        }
        return e;
    }

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Canonical JSON of the full (unstripped) component patch, or null if it cannot be encoded. */
    public static String rawPatchJson(ItemStack stack) {
        try {
            DataComponentPatch patch = stack.getComponentsPatch();
            if (patch.isEmpty()) return "{}";
            DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            JsonElement json = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
            return json == null ? null : canonicalize(json).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> loreLines(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore == null) return out;
        for (Component c : lore.lines()) out.add(c.getString());
        return out;
    }

    public static String displayName(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    public static String loreHint(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return null;
        String s = lore.lines().get(0).getString().trim();
        return s.isEmpty() ? null : s;
    }
}
