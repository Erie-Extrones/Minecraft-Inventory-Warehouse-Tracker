package dev.warehouse.organizer;

import dev.warehouse.Chat;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemKey;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.storage.JsonStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Facade: runs discovery + allocation, keeps the plan store, resolves item -> chest. */
public final class Organizer {
    public record Resolution(ContainerEntry container, String category, String reason) {}

    private final JsonStore<Plan> store;
    private final ContainerIndex index;
    private final RegionManager regions;
    private final CategoryResolver categories;
    private final ChestDiscovery discovery;
    private final Allocator allocator;
    private final Map<ItemKey, Resolution> resolveCache = new HashMap<>();
    private boolean previewEnabled;
    /** A plan built but not yet accepted (kept in memory until accept/reject). */
    private @Nullable Plan pending;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public Organizer(JsonStore<Plan> store, ContainerIndex index, RegionManager regions, CategoryResolver categories) {
        this.store = store;
        this.index = index;
        this.regions = regions;
        this.categories = categories;
        this.discovery = new ChestDiscovery(index, regions);
        this.allocator = new Allocator(categories);
        index.onChange(this::invalidate);
    }

    public void onChange(Runnable r) {
        changeListeners.add(r);
    }

    public Plan plan() {
        return store.get();
    }

    public @Nullable Plan pending() {
        return pending;
    }

    public boolean previewEnabled() {
        return previewEnabled;
    }

    public void setPreview(boolean b) {
        previewEnabled = b;
    }

    public void invalidate() {
        resolveCache.clear();
        categories.invalidate();
        for (Runnable r : changeListeners) r.run();
    }

    public ChestDiscovery discovery() {
        return discovery;
    }

    public Allocator allocator() {
        return allocator;
    }

    public List<ContainerEntry> plannableChests() {
        List<ContainerEntry> out = new ArrayList<>();
        for (ContainerEntry e : index.all()) {
            if (!e.kind.isPlannable()) continue;
            Region r = regions.byId(e.regionId);
            if (r == null || r.type != dev.warehouse.region.RegionType.WAREHOUSE) continue;
            out.add(e);
        }
        return out;
    }

    private Map<UUID, Region> regionMap(List<ContainerEntry> chests) {
        Map<UUID, Region> m = new HashMap<>();
        for (ContainerEntry e : chests) m.put(e.id, regions.byId(e.regionId));
        return m;
    }

    private List<ItemStack> inventory(Minecraft mc) {
        List<ItemStack> out = new ArrayList<>();
        if (mc.player == null) return out;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) out.add(inv.getItem(i));
        return out;
    }

    /** Discover chests and build a pending plan (fresh or replan). */
    public @Nullable Plan run(Minecraft mc, boolean replan) {
        if (mc.player == null) return null;
        ChestDiscovery.Report rep = discovery.run(mc);
        List<ContainerEntry> chests = plannableChests();
        Plan prev = plan();
        if (prev.manualChestCount > 0 && chests.size() > prev.manualChestCount) chests = new ArrayList<>(chests.subList(0, prev.manualChestCount));
        if (chests.isEmpty()) {
            Chat.error("No chests to plan with. " + rep.message() + ". Draw a Warehouse region around your chests first.");
            return null;
        }
        BlockPos entrance = mc.player.blockPosition();
        Map<String, Integer> volume = allocator.volumeByCategory(index.all(), inventory(mc));
        List<ContainerEntry> ordered = Allocator.orderChests(chests, regionMap(chests), entrance);
        Plan p = replan && prev.exists() ? allocator.replan(prev, ordered, volume, entrance) : allocator.build(ordered, volume, prev, entrance);
        p.accepted = false;
        pending = p;
        Chat.info(rep.message() + ". Plan: " + p.zones.size() + " zones over " + ordered.size() + " chests. /warehouse plan show, then accept or reject.");
        return p;
    }

    public boolean accept() {
        if (pending == null) return false;
        pending.accepted = true;
        store.set(pending);
        pending = null;
        invalidate();
        return true;
    }

    public void reject() {
        pending = null;
    }

    public void clearPlan() {
        store.set(Plan.empty());
        pending = null;
        invalidate();
    }

    public void setManualCount(int n) {
        plan().manualChestCount = n;
        store.markDirty();
    }

    public void setOverride(ItemKey key, @Nullable UUID containerId) {
        if (containerId == null) plan().explicitOverrides.remove(key.asString());
        else plan().explicitOverrides.put(key.asString(), containerId);
        store.markDirty();
        invalidate();
    }

    public void setCategoryOverride(String idOrGroup, @Nullable String category) {
        if (category == null) plan().categoryOverrides.remove(idOrGroup);
        else plan().categoryOverrides.put(idOrGroup, category);
        store.markDirty();
        invalidate();
    }

    /** Where should this key live? null when no plan or no room. */
    public @Nullable Resolution resolve(ItemKey key) {
        Plan plan = plan();
        if (!plan.isActive()) return null;
        Resolution cached = resolveCache.get(key);
        if (cached != null || resolveCache.containsKey(key)) return cached;
        Resolution r = compute(plan, key);
        resolveCache.put(key, r);
        return r;
    }

    private @Nullable Resolution compute(Plan plan, ItemKey key) {
        UUID ov = plan.explicitOverrides.get(key.asString());
        if (ov != null) {
            ContainerEntry e = index.byId(ov);
            if (e != null) return new Resolution(e, plan.zoneOf(e.id) != null ? plan.zoneOf(e.id) : categories.categoryOf(key), "override");
        }
        String cat = categories.categoryOf(key);
        Zone zone = plan.zones.get(cat);
        if (zone == null) zone = plan.zones.get(Categorizer.MISC);
        if (zone == null) return null;
        List<ContainerEntry> chests = new ArrayList<>();
        for (UUID id : zone.containerIds) {
            ContainerEntry e = index.byId(id);
            if (e != null) chests.add(e);
        }
        // 1. chest in the zone already holding this key (with room, else still prefer it)
        ContainerEntry holder = null;
        for (ContainerEntry e : chests) if (e.totalCount(key) > 0) {
            if (e.freeSlots() > 0 || hasPartialStack(e, key)) return new Resolution(e, cat, "already there");
            if (holder == null) holder = e;
        }
        // 2. chest in the zone holding the same sub-family, with room
        String fam = categories.subFamilyOf(key);
        ContainerEntry best = null;
        for (ContainerEntry e : chests) {
            if (e.freeSlots() <= 0) continue;
            for (StackRecord s : e.contents) {
                if (fam.equals(categories.subFamilyOf(s.key))) {
                    if (best == null || e.freeSlots() > best.freeSlots()) best = e;
                    break;
                }
            }
        }
        if (best != null) return new Resolution(best, cat, "same family");
        // 3. chest in the zone with most free slots
        for (ContainerEntry e : chests) if (e.freeSlots() > 0 && (best == null || e.freeSlots() > best.freeSlots())) best = e;
        if (best != null) return new Resolution(best, cat, "free space");
        if (holder != null) return new Resolution(holder, cat, "already there (full)");
        // 4. nearest Misc chest with room
        Zone misc = plan.zones.get(Categorizer.MISC);
        if (misc != null && misc != zone) {
            for (UUID id : misc.containerIds) {
                ContainerEntry e = index.byId(id);
                if (e != null && e.freeSlots() > 0) return new Resolution(e, cat, "overflow to Misc");
            }
        }
        return null;
    }

    private static boolean hasPartialStack(ContainerEntry e, ItemKey key) {
        int max = Allocator.maxStack(key);
        for (StackRecord s : e.contents) if (key.equals(s.key) && s.count < max) return true;
        return false;
    }

    /** Summary rows for the Plan tab / show command. */
    public record ZoneSummary(String category, int chests, int slotsUsed, int slotsTotal, int unopened) {}

    public List<ZoneSummary> summarize(Plan p) {
        List<ZoneSummary> out = new ArrayList<>();
        for (Zone z : p.zones.values()) {
            int used = 0, total = 0, unopened = 0;
            for (UUID id : z.containerIds) {
                ContainerEntry e = index.byId(id);
                if (e == null) continue;
                used += e.usedSlots();
                total += e.slotCount > 0 ? e.slotCount : ChestDiscovery.defaultSlots(e.kind);
                if (e.lastSeenEpochMs < p.createdEpochMs) unopened++;
            }
            out.add(new ZoneSummary(z.category, z.containerIds.size(), used, total, unopened));
        }
        return out;
    }

    public int categoryColor(String category) {
        Integer c = dev.warehouse.config.ConfigIO.get().categoryColors.get(category);
        return c != null ? c : 0xFFFFFFFF;
    }
}
