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
        Allocator.Demand demand = allocator.demand(index.all(), inventory(mc));
        List<ContainerEntry> ordered = Allocator.orderChests(chests, regionMap(chests), entrance);
        Plan p = replan && prev.exists() ? allocator.replan(prev, ordered, demand, entrance) : allocator.build(ordered, demand, prev, entrance);
        p.accepted = false;
        pending = p;
        int opened = 0;
        for (ContainerEntry e : ordered) if (e.lastSeenEpochMs > 0) opened++;
        String hint = opened < ordered.size() / 2
                ? " Only " + opened + "/" + ordered.size() + " chests have been opened; the plan improves once more are indexed (replan later)."
                : "";
        Chat.info(rep.message() + ". Plan: " + p.zones.size() + " zones over " + ordered.size() + " chests." + hint + " /warehouse plan show, then accept or reject.");
        return p;
    }

    public boolean accept() {
        if (pending == null) return false;
        pending.accepted = true;
        store.set(pending);
        pending = null;
        newChestsSincePlan = 0;
        replanHinted = false;
        invalidate();
        return true;
    }

    private int newChestsSincePlan;
    private boolean replanHinted;

    /** Called when a container is indexed for the first time (contents seen). */
    public void onContainerFirstSeen() {
        if (!plan().isActive()) return;
        newChestsSincePlan++;
        int limit = dev.warehouse.config.ConfigIO.get().replanHintAfterNewChests;
        if (!replanHinted && limit > 0 && newChestsSincePlan >= limit) {
            replanHinted = true;
            Chat.info(newChestsSincePlan + " chests were opened for the first time since the plan. Consider /warehouse plan replan to rebalance zones.");
        }
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
        return resolve(key, null);
    }

    /** Resolution for a stack, honouring shulker contents. */
    public @Nullable Resolution resolve(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ItemKey key = dev.warehouse.items.Fingerprinter.key(stack);
        if (!key.isVanilla() && categories.groupFor(key) == null) {
            categories.learn(stack, key);
            resolveCache.remove(key);
        }
        return resolve(key, dev.warehouse.items.NestedContents.of(stack));
    }

    public @Nullable Resolution resolve(ItemKey key, @Nullable List<StackRecord> nested) {
        Plan plan = plan();
        if (!plan.isActive()) return null;
        boolean cacheable = nested == null || nested.isEmpty();
        if (cacheable) {
            Resolution cached = resolveCache.get(key);
            if (cached != null || resolveCache.containsKey(key)) return cached;
        }
        Resolution r = compute(plan, key, nested);
        if (cacheable) resolveCache.put(key, r);
        return r;
    }

    private @Nullable Resolution compute(Plan plan, ItemKey key, @Nullable List<StackRecord> nested) {
        UUID ov = plan.explicitOverrides.get(key.asString());
        if (ov != null) {
            ContainerEntry e = index.byId(ov);
            if (e != null) return new Resolution(e, plan.zoneOf(e.id) != null ? plan.zoneOf(e.id) : categories.categoryOf(key, nested), "override");
        }
        // Plugin items stay where they already live: a rule or classifier change must not turn a sorted warehouse into misplaced work.
        if (!key.isVanilla() && dev.warehouse.config.ConfigIO.get().customItemsStayPut) {
            ContainerEntry withRoom = null, any = null;
            for (ContainerEntry e : index.holding(key)) {
                if (plan.zoneOf(e.id) == null) continue;
                if (any == null) any = e;
                if (e.freeSlots() > 0 || hasPartialStack(e, key)) {
                    withRoom = e;
                    break;
                }
            }
            ContainerEntry home = withRoom != null ? withRoom : any;
            if (home != null) return new Resolution(home, plan.zoneOf(home.id), withRoom != null ? "stays put" : "stays put (full)");
        }
        String cat = categories.categoryOf(key, nested);
        Zone zone = plan.zones.get(cat);
        if (zone == null) zone = plan.zones.get(Categorizer.MISC);
        if (zone == null) return null;
        List<ContainerEntry> chests = new ArrayList<>();
        for (UUID id : zone.containerIds) {
            ContainerEntry e = index.byId(id);
            if (e != null) chests.add(e);
        }
        if (chests.isEmpty()) return null;
        // 1. a chest in the zone already holding this exact item, if it has room for more
        ContainerEntry holder = null;
        for (ContainerEntry e : chests) if (e.totalCount(key) > 0) {
            if (e.freeSlots() > 0 || hasPartialStack(e, key)) return new Resolution(e, cat, "already there");
            if (holder == null) holder = e;
        }
        // 2. the family's home chest, then walk forward from it so related items stay adjacent
        String fam = categories.subFamilyOf(key);
        int homeIdx = homeIndex(zone, chests, fam);
        for (int step = 0; step < chests.size(); step++) {
            ContainerEntry e = chests.get((homeIdx + step) % chests.size());
            if (e.freeSlots() > 0 && (step == 0 || holdsFamily(e, fam) || isEmptyOrSameFamilyOnly(e, fam))) {
                return new Resolution(e, cat, step == 0 ? "family home" : "near family home");
            }
        }
        // 3. any chest in the zone with room, nearest the home in walk order
        for (int step = 0; step < chests.size(); step++) {
            ContainerEntry e = chests.get((homeIdx + step) % chests.size());
            if (e.freeSlots() > 0) return new Resolution(e, cat, "free space");
        }
        if (holder != null) return new Resolution(holder, cat, "already there (full)");
        // 4. Misc overflow
        Zone misc = plan.zones.get(Categorizer.MISC);
        if (misc != null && misc != zone) {
            for (UUID id : misc.containerIds) {
                ContainerEntry e = index.byId(id);
                if (e != null && e.freeSlots() > 0) return new Resolution(e, cat, "overflow to Misc");
            }
        }
        return null;
    }

    private int homeIndex(Zone zone, List<ContainerEntry> chests, String family) {
        UUID home = zone.familyHomes != null ? zone.familyHomes.get(family) : null;
        if (home != null) {
            for (int i = 0; i < chests.size(); i++) if (chests.get(i).id.equals(home)) return i;
        }
        // Unknown family: a stable slot in the zone, biased toward the second half so planned families keep the front.
        int n = chests.size();
        int h = Math.floorMod(family.hashCode(), Math.max(1, n));
        return n > 2 ? (n / 2 + h / 2) % n : h;
    }

    private boolean holdsFamily(ContainerEntry e, String fam) {
        for (StackRecord s : e.contents) if (fam.equals(categories.subFamilyOf(s.key))) return true;
        return false;
    }

    /** True if the chest is empty or every stack in it belongs to the given family. */
    private boolean isEmptyOrSameFamilyOnly(ContainerEntry e, String fam) {
        for (StackRecord s : e.contents) if (!fam.equals(categories.subFamilyOf(s.key))) return false;
        return true;
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
