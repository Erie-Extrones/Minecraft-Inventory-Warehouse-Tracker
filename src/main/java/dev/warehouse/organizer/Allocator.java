package dev.warehouse.organizer;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.NestedContents;
import dev.warehouse.region.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic category -> zone -> chest assignment, with sub-family homes inside each zone. */
public final class Allocator {
    private final CategoryResolver categories;

    public Allocator(CategoryResolver categories) {
        this.categories = categories;
    }

    /** What is known: slots needed per category and per (category, sub-family). */
    public static final class Demand {
        public final Map<String, Integer> slotsByCategory = new LinkedHashMap<>();
        public final Map<String, Map<String, Integer>> slotsByFamily = new LinkedHashMap<>();

        void add(String category, String family, int slots) {
            slotsByCategory.merge(category, slots, Integer::sum);
            slotsByFamily.computeIfAbsent(category, k -> new LinkedHashMap<>()).merge(family, slots, Integer::sum);
        }
    }

    /** Slots needed per category/family from all known stacks (index + inventory). Shulkers count as one slot in their dominant category. */
    public Demand demand(List<ContainerEntry> allContainers, List<ItemStack> inventory) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, ItemKey> keyOf = new HashMap<>();
        Map<String, List<StackRecord>> nestedOf = new HashMap<>();
        for (ContainerEntry e : allContainers) {
            for (StackRecord s : e.contents) {
                String id = s.key.asString() + (s.nested != null ? "#" + s.slot + e.id : "");
                counts.merge(id, s.count, Integer::sum);
                keyOf.put(id, s.key);
                if (s.nested != null) nestedOf.put(id, s.nested);
            }
        }
        int invIdx = 0;
        for (ItemStack st : inventory) {
            if (st.isEmpty()) continue;
            ItemKey k = dev.warehouse.items.Fingerprinter.key(st);
            List<StackRecord> nested = NestedContents.of(st);
            String id = k.asString() + (nested.isEmpty() ? "" : "#inv" + invIdx++);
            counts.merge(id, st.getCount(), Integer::sum);
            keyOf.put(id, k);
            if (!nested.isEmpty()) nestedOf.put(id, nested);
        }
        Demand d = new Demand();
        for (String c : Categorizer.DEFAULT_ORDER) d.slotsByCategory.put(c, 0);
        double headroom = Math.max(1.0, ConfigIO.get().volumeHeadroom);
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            ItemKey key = keyOf.get(en.getKey());
            String cat = categories.categoryOf(key, nestedOf.get(en.getKey()));
            int max = maxStack(key);
            int need = (int) Math.ceil(Math.ceil(en.getValue() / (double) max) * headroom);
            d.add(cat, categories.subFamilyOf(key), Math.max(1, need));
        }
        return d;
    }

    public static int maxStack(ItemKey key) {
        ItemStack s = CategoryResolver.sampleStack(key);
        return s == null ? 64 : Math.max(1, s.getMaxStackSize());
    }

    /** Orders chests for a walk: rows along the region's longest horizontal axis, starting nearest the entrance. */
    public static List<ContainerEntry> orderChests(List<ContainerEntry> chests, Map<UUID, Region> regionOf, BlockPos entrance) {
        List<ContainerEntry> out = new ArrayList<>(chests);
        out.sort(Comparator.comparing((ContainerEntry e) -> regionOf.get(e.id) != null ? regionOf.get(e.id).name : "")
                .thenComparingLong(e -> walkKey(e, regionOf.get(e.id), entrance)));
        return out;
    }

    private static long walkKey(ContainerEntry e, Region r, BlockPos entrance) {
        if (r == null) return 0;
        int sx = r.maxX - r.minX, sz = r.maxZ - r.minZ;
        boolean rowsAlongX = sx >= sz;
        boolean xAsc = Math.abs(entrance.getX() - r.minX) <= Math.abs(entrance.getX() - r.maxX);
        boolean zAsc = Math.abs(entrance.getZ() - r.minZ) <= Math.abs(entrance.getZ() - r.maxZ);
        boolean yAsc = Math.abs(entrance.getY() - r.minY) <= Math.abs(entrance.getY() - r.maxY);
        int x = xAsc ? e.pos.getX() - r.minX : r.maxX - e.pos.getX();
        int z = zAsc ? e.pos.getZ() - r.minZ : r.maxZ - e.pos.getZ();
        int y = yAsc ? e.pos.getY() - r.minY : r.maxY - e.pos.getY();
        long primary = rowsAlongX ? z : x;
        long tertiary = rowsAlongX ? x : z;
        if ((primary & 1) == 1) tertiary = 4096 - tertiary; // snake rows so consecutive chests stay adjacent
        return primary * (1L << 40) + (long) y * (1L << 20) + tertiary;
    }

    /** Fresh plan from scratch. */
    public Plan build(List<ContainerEntry> orderedChests, Demand demand, Plan previous, BlockPos entrance) {
        Plan plan = new Plan();
        plan.createdEpochMs = System.currentTimeMillis();
        plan.explicitOverrides = previous != null ? new LinkedHashMap<>(previous.explicitOverrides) : new LinkedHashMap<>();
        plan.categoryOverrides = previous != null ? new LinkedHashMap<>(previous.categoryOverrides) : new LinkedHashMap<>();
        plan.manualChestCount = previous != null ? previous.manualChestCount : 0;
        plan.entranceX = entrance.getX();
        plan.entranceY = entrance.getY();
        plan.entranceZ = entrance.getZ();
        plan.chestBudget = orderedChests.size();
        if (orderedChests.isEmpty()) return plan;

        Map<String, Integer> volume = demand.slotsByCategory;
        Map<String, Integer> chestsPer = distribute(orderedChests, volume);
        // Biggest categories nearest the entrance; Misc last.
        List<String> order = new ArrayList<>(chestsPer.keySet());
        order.sort((a, b) -> {
            if (a.equals(Categorizer.MISC)) return 1;
            if (b.equals(Categorizer.MISC)) return -1;
            int c = Integer.compare(volume.getOrDefault(b, 0), volume.getOrDefault(a, 0));
            return c != 0 ? c : Integer.compare(Categorizer.DEFAULT_ORDER.indexOf(a), Categorizer.DEFAULT_ORDER.indexOf(b));
        });
        int cursor = 0;
        for (String cat : order) {
            int n = chestsPer.get(cat);
            Zone z = new Zone(cat);
            for (int i = 0; i < n && cursor < orderedChests.size(); i++) z.containerIds.add(orderedChests.get(cursor++).id);
            if (!z.containerIds.isEmpty()) plan.zones.put(cat, z);
        }
        if (cursor < orderedChests.size()) {
            Zone misc = plan.zones.computeIfAbsent(Categorizer.MISC, Zone::new);
            while (cursor < orderedChests.size()) misc.containerIds.add(orderedChests.get(cursor++).id);
        }
        assignFamilyHomes(plan, demand);
        return plan;
    }

    /** Spread each zone's known sub-families across its chests in walk order, biggest families first. */
    static void assignFamilyHomes(Plan plan, Demand demand) {
        for (Zone z : plan.zones.values()) {
            z.familyHomes = new LinkedHashMap<>();
            Map<String, Integer> fams = demand.slotsByFamily.getOrDefault(z.category, Map.of());
            if (fams.isEmpty() || z.containerIds.isEmpty()) continue;
            List<String> order = new ArrayList<>(fams.keySet());
            order.sort((a, b) -> {
                int c = Integer.compare(fams.get(b), fams.get(a));
                return c != 0 ? c : a.compareTo(b);
            });
            int n = z.containerIds.size();
            if (order.size() <= n) {
                // Give each family a proportional run of chests; its home is the first chest of the run.
                int totalSlots = 0;
                for (String f : order) totalSlots += fams.get(f);
                int cursor = 0;
                for (int i = 0; i < order.size(); i++) {
                    String f = order.get(i);
                    z.familyHomes.put(f, z.containerIds.get(Math.min(cursor, n - 1)));
                    int run = totalSlots > 0 ? (int) Math.max(1, Math.round(fams.get(f) / (double) totalSlots * n)) : 1;
                    int remainingFamilies = order.size() - i - 1;
                    cursor = Math.min(cursor + run, Math.max(cursor + 1, n - remainingFamilies));
                }
            } else {
                for (int i = 0; i < order.size(); i++) z.familyHomes.put(order.get(i), z.containerIds.get((int) ((long) i * n / order.size())));
            }
        }
    }

    /**
     * Chest counts per category. Weight = known slot volume + the category's prior share of the capacity that is
     * not yet accounted for, so an unexplored warehouse still gets sensible zones and a well-indexed one follows
     * its real contents. Every category keeps at least minChestsPerCategory when the budget allows.
     */
    static Map<String, Integer> distribute(List<ContainerEntry> chests, Map<String, Integer> volume) {
        int totalChests = chests.size();
        int totalSlots = 0;
        for (ContainerEntry c : chests) totalSlots += c.slotCount > 0 ? c.slotCount : 27;
        Map<String, Integer> priors = ConfigIO.get().categoryPriorShares;
        if (priors == null || priors.isEmpty()) priors = dev.warehouse.config.ModConfig.defaultPriorShares();
        double priorSum = 0;
        for (String c : Categorizer.DEFAULT_ORDER) priorSum += Math.max(0, priors.getOrDefault(c, 0));
        if (priorSum <= 0) priorSum = 1;

        int known = 0;
        for (String c : Categorizer.DEFAULT_ORDER) known += Math.max(0, volume.getOrDefault(c, 0));
        // Assume a good part of the free capacity will eventually follow the typical distribution.
        double unknown = Math.max(0, totalSlots - known) * 0.6;

        Map<String, Double> weight = new LinkedHashMap<>();
        double weightSum = 0;
        for (String c : Categorizer.DEFAULT_ORDER) {
            double w = Math.max(0, volume.getOrDefault(c, 0)) + Math.max(0, priors.getOrDefault(c, 0)) / priorSum * unknown;
            weight.put(c, w);
            weightSum += w;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        int baseline = Math.max(0, ConfigIO.get().minChestsPerCategory);
        while (baseline > 0 && baseline * Categorizer.DEFAULT_ORDER.size() > totalChests) baseline--;
        Map<String, Double> frac = new HashMap<>();
        int assigned = 0;
        for (String c : Categorizer.DEFAULT_ORDER) {
            double exact = weightSum > 0 ? weight.get(c) / weightSum * totalChests : 0;
            int n = Math.max(baseline, (int) Math.floor(exact));
            if (baseline == 0 && volume.getOrDefault(c, 0) > 0 && n == 0) n = 1;
            out.put(c, n);
            frac.put(c, exact - Math.floor(exact));
            assigned += n;
        }
        // Hand out leftovers by largest remainder; reclaim from the largest zones if the baselines overshot.
        List<String> byFrac = new ArrayList<>(Categorizer.DEFAULT_ORDER);
        byFrac.sort((a, b) -> Double.compare(frac.get(b), frac.get(a)));
        int remaining = totalChests - assigned;
        for (String c : byFrac) {
            if (remaining <= 0) break;
            out.merge(c, 1, Integer::sum);
            remaining--;
        }
        while (remaining < 0) {
            String biggest = null;
            for (String c : Categorizer.DEFAULT_ORDER) if (out.get(c) > baseline && (biggest == null || out.get(c) > out.get(biggest))) biggest = c;
            if (biggest == null) break;
            out.merge(biggest, -1, Integer::sum);
            remaining++;
        }
        out.values().removeIf(v -> v <= 0);
        if (!out.containsKey(Categorizer.MISC)) out.put(Categorizer.MISC, 0);
        return out;
    }

    private static int sumValues(Map<String, Integer> m) {
        int s = 0;
        for (int v : m.values()) s += v;
        return s;
    }

    /** Replan: keep assignments that still fit; only new chests or overflowing categories move. Family homes are recomputed. */
    public Plan replan(Plan previous, List<ContainerEntry> orderedChests, Demand demand, BlockPos entrance) {
        Plan fresh = build(orderedChests, demand, previous, entrance);
        if (previous == null || !previous.exists()) return fresh;
        Set<UUID> current = new LinkedHashSet<>();
        for (ContainerEntry c : orderedChests) current.add(c.id);
        Map<String, Integer> want = new HashMap<>();
        for (Zone z : fresh.zones.values()) want.put(z.category, z.containerIds.size());

        Plan plan = new Plan();
        plan.createdEpochMs = System.currentTimeMillis();
        plan.explicitOverrides = new LinkedHashMap<>(previous.explicitOverrides);
        plan.categoryOverrides = new LinkedHashMap<>(previous.categoryOverrides);
        plan.manualChestCount = previous.manualChestCount;
        plan.entranceX = entrance.getX();
        plan.entranceY = entrance.getY();
        plan.entranceZ = entrance.getZ();
        plan.chestBudget = orderedChests.size();

        Set<UUID> assigned = new LinkedHashSet<>();
        for (Zone old : previous.zones.values()) {
            Zone z = new Zone(old.category);
            for (UUID id : old.containerIds) if (current.contains(id) && assigned.add(id)) z.containerIds.add(id);
            if (!z.containerIds.isEmpty()) plan.zones.put(z.category, z);
        }
        List<UUID> free = new ArrayList<>();
        for (UUID id : current) if (!assigned.contains(id)) free.add(id);

        for (String cat : fresh.zones.keySet()) {
            int need = want.getOrDefault(cat, 0);
            Zone z = plan.zones.computeIfAbsent(cat, Zone::new);
            while (z.containerIds.size() < need && !free.isEmpty()) {
                UUID pick = nearestInWalk(orderedChests, z.containerIds, free);
                free.remove(pick);
                z.containerIds.add(pick);
            }
        }
        for (String cat : fresh.zones.keySet()) {
            Zone z = plan.zones.get(cat);
            int need = want.getOrDefault(cat, 0);
            while (z.containerIds.size() < need) {
                Zone donor = null;
                for (Zone other : plan.zones.values()) {
                    if (other == z) continue;
                    int otherNeed = want.getOrDefault(other.category, 0);
                    if (other.containerIds.size() > Math.max(1, otherNeed)) {
                        if (donor == null || (!other.category.equals(Categorizer.MISC) && donor.category.equals(Categorizer.MISC))) donor = other;
                    }
                }
                if (donor == null) break;
                // Donate a chest that is empty if possible.
                UUID moved = donor.containerIds.remove(donor.containerIds.size() - 1);
                z.containerIds.add(moved);
            }
        }
        if (!free.isEmpty()) plan.zones.computeIfAbsent(Categorizer.MISC, Zone::new).containerIds.addAll(free);
        plan.zones.values().removeIf(z -> z.containerIds.isEmpty());
        // Keep zone chest lists in walk order.
        Map<UUID, Integer> pos = new HashMap<>();
        for (int i = 0; i < orderedChests.size(); i++) pos.put(orderedChests.get(i).id, i);
        for (Zone z : plan.zones.values()) z.containerIds.sort(Comparator.comparingInt(id -> pos.getOrDefault(id, Integer.MAX_VALUE)));
        assignFamilyHomes(plan, demand);
        return plan;
    }

    private static UUID nearestInWalk(List<ContainerEntry> ordered, List<UUID> anchors, List<UUID> free) {
        if (anchors.isEmpty()) return free.get(0);
        Map<UUID, Integer> pos = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) pos.put(ordered.get(i).id, i);
        UUID best = free.get(0);
        int bestD = Integer.MAX_VALUE;
        for (UUID f : free) {
            int fp = pos.getOrDefault(f, 0);
            for (UUID a : anchors) {
                int d = Math.abs(fp - pos.getOrDefault(a, 0));
                if (d < bestD) {
                    bestD = d;
                    best = f;
                }
            }
        }
        return best;
    }
}
