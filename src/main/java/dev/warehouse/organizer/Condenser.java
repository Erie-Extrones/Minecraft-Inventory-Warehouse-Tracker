package dev.warehouse.organizer;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.NestedContents;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Suggests which items to pack into shulker boxes when a zone is running out of slots.
 * A shulker box holds 27 stacks in one slot, so an item spread over N slots can be cut to ceil(N/27) slots.
 */
public final class Condenser {
    public static final int SHULKER_SLOTS = 27;

    /** One item worth condensing inside a zone. {@code sources} are the chests holding it, most stacks first. */
    public record Suggestion(String zone, ItemKey key, String displayName, int total, int slotsNow, int shulkersNeeded, int slotsFreed,
                             List<ContainerEntry> sources, Map<UUID, Integer> slotsPerSource, @Nullable ContainerEntry putBack) {}

    /** A zone (plan category, or the whole warehouse without a plan) and what could be condensed in it. */
    public record ZoneReport(String zone, int slotsUsed, int slotsTotal, int percent, boolean lowOnSpace, List<Suggestion> suggestions) {
        public int totalSlotsFreed() {
            int n = 0;
            for (Suggestion s : suggestions) n += s.slotsFreed();
            return n;
        }
    }

    private final ContainerIndex index;
    private final Organizer organizer;
    private final CategoryResolver categories;
    private final RegionManager regions;
    private boolean dirty = true;
    private List<ZoneReport> cached = List.of();
    private final Map<String, Long> hintedAtMs = new HashMap<>();
    private int ticks;

    public Condenser(ContainerIndex index, Organizer organizer, CategoryResolver categories, RegionManager regions) {
        this.index = index;
        this.organizer = organizer;
        this.categories = categories;
        this.regions = regions;
    }

    public void invalidate() {
        dirty = true;
    }

    /** Every zone with at least one suggestion, fullest first. */
    public List<ZoneReport> reports() {
        if (dirty) rebuild();
        return cached;
    }

    /** Zones that are over the fill threshold and have something worth condensing. */
    public List<ZoneReport> urgent() {
        List<ZoneReport> out = new ArrayList<>();
        for (ZoneReport r : reports()) if (r.lowOnSpace() && !r.suggestions().isEmpty()) out.add(r);
        return out;
    }

    /** Periodic chat hint: once per zone per cooldown, only while the player stands in a warehouse region. */
    public void tick(Minecraft mc) {
        ModConfig cfg = ConfigIO.get();
        if (!cfg.condenseSuggestions || mc.player == null || mc.level == null) return;
        if (++ticks % 200 != 0) return;
        String dim = mc.level.dimension().identifier().toString();
        Region here = regions.warehouseAt(dim, mc.player.blockPosition());
        if (here == null) return;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1, cfg.condenseHintCooldownMinutes) * 60_000L;
        for (ZoneReport r : urgent()) {
            Long last = hintedAtMs.get(r.zone());
            if (last != null && now - last < cooldown) continue;
            hintedAtMs.put(r.zone(), now);
            Chat.info(describe(r) + " See /warehouse condense.");
            break; // one hint at a time
        }
    }

    /** Short sentence for chat: "Building is 88% full. Packing Cobblestone, Stone and 2 more into 4 shulkers would free 61 slots." */
    public static String describe(ZoneReport r) {
        List<Suggestion> s = r.suggestions();
        StringBuilder names = new StringBuilder();
        int shulkers = 0;
        for (int i = 0; i < s.size(); i++) {
            shulkers += s.get(i).shulkersNeeded();
            if (i < 2) {
                if (i > 0) names.append(s.size() == 2 ? " and " : ", ");
                names.append(s.get(i).displayName());
            }
        }
        if (s.size() > 2) names.append(" and ").append(s.size() - 2).append(" more");
        return r.zone() + " is " + r.percent() + "% full. Packing " + names + " into " + shulkers + " shulker" + (shulkers == 1 ? "" : "s")
                + " would free " + r.totalSlotsFreed() + " slots.";
    }

    // ------------------------------------------------------------ availability of empty shulkers

    public int emptyShulkersInInventory(Minecraft mc) {
        if (mc.player == null) return 0;
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.is(ItemTags.SHULKER_BOXES) && NestedContents.of(st).isEmpty()) n += st.getCount();
        }
        return n;
    }

    /** Empty shulker boxes sitting in indexed containers, and the chest holding most of them. */
    public record Stored(int count, @Nullable ContainerEntry where) {}

    public Stored emptyShulkersIndexed() {
        int total = 0, best = 0;
        ContainerEntry where = null;
        for (ContainerEntry e : index.all()) {
            int here = 0;
            for (StackRecord s : e.contents) {
                if (isShulker(s.key) && (s.nested == null || s.nested.isEmpty())) here += s.count;
            }
            total += here;
            if (here > best) {
                best = here;
                where = e;
            }
        }
        return new Stored(total, where);
    }

    static boolean isShulker(ItemKey key) {
        return key.itemId.endsWith("shulker_box");
    }

    // ------------------------------------------------------------ computation

    private void rebuild() {
        dirty = false;
        ModConfig cfg = ConfigIO.get();
        Plan plan = organizer.plan();
        Map<String, List<ContainerEntry>> zones = new LinkedHashMap<>();
        if (plan.isActive()) {
            for (Zone z : plan.zones.values()) {
                List<ContainerEntry> chests = new ArrayList<>();
                for (UUID id : z.containerIds) {
                    ContainerEntry e = index.byId(id);
                    if (e != null) chests.add(e);
                }
                if (!chests.isEmpty()) zones.put(z.category, chests);
            }
        } else {
            List<ContainerEntry> chests = new ArrayList<>();
            for (ContainerEntry e : index.all()) {
                if (e.kind.isEntity() || e.regionId == null) continue;
                Region r = regions.byId(e.regionId);
                if (r != null && r.type == dev.warehouse.region.RegionType.WAREHOUSE) chests.add(e);
            }
            if (!chests.isEmpty()) zones.put("Warehouse", chests);
        }
        List<ZoneReport> out = new ArrayList<>();
        for (Map.Entry<String, List<ContainerEntry>> en : zones.entrySet()) {
            ZoneReport r = report(cfg, en.getKey(), en.getValue());
            if (r != null) out.add(r);
        }
        out.sort(Comparator.comparingInt(ZoneReport::percent).reversed());
        cached = out;
    }

    private @Nullable ZoneReport report(ModConfig cfg, String zone, List<ContainerEntry> chests) {
        int used = 0, total = 0;
        record Acc(ItemKey key, String name, int count, int slots, Map<UUID, Integer> perChest) {}
        Map<ItemKey, Acc> acc = new LinkedHashMap<>();
        for (ContainerEntry e : chests) {
            used += e.usedSlots();
            total += e.slotCount > 0 ? e.slotCount : ChestDiscovery.defaultSlots(e.kind);
            for (StackRecord s : e.contents) {
                if (s.nested != null && !s.nested.isEmpty()) continue;
                if (isShulker(s.key) || Categorizer.STORAGE.equals(categories.categoryOf(s.key))) continue;
                Acc a = acc.get(s.key);
                if (a == null) {
                    a = new Acc(s.key, s.displayName != null ? s.displayName : s.key.itemId, 0, 0, new HashMap<>());
                    acc.put(s.key, a);
                }
                a.perChest().merge(e.id, 1, Integer::sum);
                acc.put(s.key, new Acc(a.key(), a.name(), a.count() + s.count, a.slots() + 1, a.perChest()));
            }
        }
        if (total == 0) return null;
        int percent = 100 * used / total;
        boolean low = percent >= cfg.condenseAtFillPercent;
        List<Suggestion> suggestions = new ArrayList<>();
        for (Acc a : acc.values()) {
            if (a.slots() < cfg.condenseMinStacks) continue;
            int max = Allocator.maxStack(a.key());
            int packedStacks = (int) Math.ceil(a.count() / (double) max);
            int shulkers = (int) Math.ceil(packedStacks / (double) SHULKER_SLOTS);
            int freed = a.slots() - shulkers;
            if (freed < cfg.condenseMinSlotsFreed) continue;
            List<ContainerEntry> sources = new ArrayList<>();
            for (UUID id : a.perChest().keySet()) {
                ContainerEntry e = index.byId(id);
                if (e != null) sources.add(e);
            }
            sources.sort(Comparator.comparingInt((ContainerEntry e) -> a.perChest().getOrDefault(e.id, 0)).reversed());
            // The packed shulker goes where the item lives: the chest holding most of it, which has room once emptied.
            ContainerEntry putBack = sources.isEmpty() ? null : sources.get(0);
            suggestions.add(new Suggestion(zone, a.key(), a.name(), a.count(), a.slots(), shulkers, freed, sources, a.perChest(), putBack));
        }
        suggestions.sort(Comparator.comparingInt(Suggestion::slotsFreed).reversed());
        return new ZoneReport(zone, used, total, percent, low, suggestions);
    }
}
