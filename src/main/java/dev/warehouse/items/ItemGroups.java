package dev.warehouse.items;

import dev.warehouse.storage.JsonStore;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Groups custom-item fingerprints by display name; persisted in items.json. */
public final class ItemGroups {
    private final JsonStore<List<ItemGroup>> store;
    private final Map<ItemKey, ItemGroup> byKey = new HashMap<>();
    private final Map<ItemKey, String> lastDisplayName = new HashMap<>();
    private boolean indexBuilt;

    public ItemGroups(JsonStore<List<ItemGroup>> store) {
        this.store = store;
    }

    public void invalidate() {
        indexBuilt = false;
        byKey.clear();
    }

    private void ensureIndex() {
        if (indexBuilt) return;
        byKey.clear();
        for (ItemGroup g : store.get()) for (ItemKey k : g.members) byKey.put(k, g);
        indexBuilt = true;
    }

    public List<ItemGroup> all() {
        return store.get();
    }

    public @Nullable ItemGroup groupFor(ItemKey key) {
        if (key == null || key.isVanilla()) return null;
        ensureIndex();
        return byKey.get(key);
    }

    public Optional<ItemGroup> byIdOrName(String s) {
        for (ItemGroup g : store.get()) if (g.groupId.equalsIgnoreCase(s)) return Optional.of(g);
        for (ItemGroup g : store.get()) if (g.displayName.equalsIgnoreCase(s)) return Optional.of(g);
        return Optional.empty();
    }

    private static final int MAX_SAMPLES_PER_GROUP = 40;

    /** Record a sighting of a stack with a non-vanilla key. Creates/extends the group matching its display name. */
    public ItemGroup record(ItemStack stack, ItemKey key) {
        ensureIndex();
        String name = Fingerprinter.displayName(stack);
        lastDisplayName.put(key, name);
        ItemGroup g = byKey.get(key);
        if (g != null) {
            sample(g, stack, key);
            return g;
        }
        for (ItemGroup cand : store.get()) {
            if (cand.displayName.equals(name)) {
                g = cand;
                break;
            }
        }
        if (g == null) {
            g = new ItemGroup(makeGroupId(name), name, Fingerprinter.loreHint(stack));
            store.get().add(g);
        }
        g.members.add(key);
        byKey.put(key, g);
        sample(g, stack, key);
        store.markDirty();
        return g;
    }

    /** Keep one full component sample per fingerprint (capped per group) so variants can be analysed offline. */
    private void sample(ItemGroup g, ItemStack stack, ItemKey key) {
        if (!dev.warehouse.config.ConfigIO.get().collectItemSamples) return;
        if (g.samples == null) g.samples = new java.util.ArrayList<>();
        for (ItemSample s : g.samples) {
            if (key.componentHash.equals(s.componentHash)) {
                s.timesSeen++;
                return;
            }
        }
        if (g.samples.size() >= MAX_SAMPLES_PER_GROUP) return;
        ItemSample s = new ItemSample();
        s.componentHash = key.componentHash;
        s.itemId = key.itemId;
        s.displayName = Fingerprinter.displayName(stack);
        s.componentsJson = Fingerprinter.rawPatchJson(stack);
        s.lore = Fingerprinter.loreLines(stack);
        s.count = stack.getCount();
        s.firstSeenEpochMs = System.currentTimeMillis();
        s.timesSeen = 1;
        g.samples.add(s);
        store.markDirty();
    }

    public String displayNameFor(ItemKey key, String fallback) {
        ItemGroup g = groupFor(key);
        if (g != null) return g.displayName;
        String s = lastDisplayName.get(key);
        return s != null ? s : fallback;
    }

    public boolean rename(ItemGroup g, String newName) {
        g.displayName = newName;
        store.markDirty();
        return true;
    }

    public boolean merge(ItemGroup into, ItemGroup from) {
        if (into == from) return false;
        for (ItemKey k : from.members) if (!into.members.contains(k)) into.members.add(k);
        if (into.categoryOverride == null) into.categoryOverride = from.categoryOverride;
        store.get().remove(from);
        store.markDirty();
        invalidate();
        return true;
    }

    public void setCategory(ItemGroup g, @Nullable String category) {
        g.categoryOverride = category;
        store.markDirty();
    }

    private String makeGroupId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "group";
        String id = base;
        int n = 2;
        while (byIdOrName(id).isPresent()) id = base + "_" + n++;
        return id;
    }
}
