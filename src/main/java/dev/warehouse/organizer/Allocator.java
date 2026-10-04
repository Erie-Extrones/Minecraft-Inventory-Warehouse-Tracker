package dev.warehouse.organizer;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemKey;
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

/** Deterministic category -> zone -> chest assignment. */
public final class Allocator {
    private final CategoryResolver categories;

    public Allocator(CategoryResolver categories) {
        this.categories = categories;
    }

    /** Slots needed per category from all known stacks (index + inventory). */
    public Map<String, Integer> volumeByCategory(List<ContainerEntry> allContainers, List<ItemStack> inventory) {
        Map<ItemKey, Integer> counts = new HashMap<>();
        for (ContainerEntry e : allContainers) {
            for (StackRecord s : e.contents) {
                counts.merge(s.key, s.count, Integer::sum);
                // Shulker contents stay inside their shulker; the shulker itself is the stored unit.
            }
        }
        for (ItemStack st : inventory) {
            if (st.isEmpty()) continue;
            counts.merge(dev.warehouse.items.Fingerprinter.key(st), st.getCount(), Integer::sum);
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (String c : Categorizer.DEFAULT_ORDER) slots.put(c, 0);
        for (Map.Entry<ItemKey, Integer> en : counts.entrySet()) {
            String cat = categories.categoryOf(en.getKey());
            int max = maxStack(en.getKey());
            int need = (int) Math.ceil(en.getValue() / (double) max);
            slots.merge(cat, need, Integer::sum);
        }
        double headroom = Math.max(1.0, ConfigIO.get().volumeHeadroom);
        for (Map.Entry<String, Integer> en : slots.entrySet()) en.setValue((int) Math.ceil(en.getValue() * headroom));
        return slots;
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
        // Entrance determines direction on each axis.
        boolean xAsc = Math.abs(entrance.getX() - r.minX) <= Math.abs(entrance.getX() - r.maxX);
        boolean zAsc = Math.abs(entrance.getZ() - r.minZ) <= Math.abs(entrance.getZ() - r.maxZ);
        boolean yAsc = Math.abs(entrance.getY() - r.minY) <= Math.abs(entrance.getY() - r.maxY);
        int x = xAsc ? e.pos.getX() - r.minX : r.maxX - e.pos.getX();
        int z = zAsc ? e.pos.getZ() - r.minZ : r.maxZ - e.pos.getZ();
        int y = yAsc ? e.pos.getY() - r.minY : r.maxY - e.pos.getY();
        // Primary: the "column" axis (perpendicular to rows), then y layer, then along the row.
        long primary = rowsAlongX ? z : x;
        long tertiary = rowsAlongX ? x : z;
        // Snake rows so consecutive chests stay adjacent.
        if ((primary & 1) == 1) tertiary = 4096 - tertiary;
        return primary * (1L << 40) + (long) y * (1L << 20) + tertiary;
    }

    /** Fresh plan from scratch. */
    public Plan build(List<ContainerEntry> orderedChests, Map<String, Integer> volume, Plan previous, BlockPos entrance) {
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

        Map<String, Integer> chestsPer = distribute(orderedChests, volume);
        // Biggest categories nearest the entrance.
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
        // Any leftover (rounding) goes to Misc.
        if (cursor < orderedChests.size()) {
            Zone misc = plan.zones.computeIfAbsent(Categorizer.MISC, Zone::new);
            while (cursor < orderedChests.size()) misc.containerIds.add(orderedChests.get(cursor++).id);
        }
        return plan;
    }

    /** Chest counts per category: proportional to slot volume, min 1 per non-empty category, Misc gets the remainder (min 1). */
    private static Map<String, Integer> distribute(List<ContainerEntry> chests, Map<String, Integer> volume) {
        int totalChests = chests.size();
        int totalSlots = 0;
        for (ContainerEntry c : chests) totalSlots += c.slotCount > 0 ? c.slotCount : 27;
        double avgSlots = totalSlots / (double) totalChests;
        List<String> nonEmpty = new ArrayList<>();
        int needSum = 0;
        for (Map.Entry<String, Integer> en : volume.entrySet()) {
            if (en.getValue() > 0 && !en.getKey().equals(Categorizer.MISC)) {
                nonEmpty.add(en.getKey());
                needSum += en.getValue();
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        int reservedMisc = 1;
        int available = Math.max(0, totalChests - reservedMisc);
        if (nonEmpty.isEmpty()) {
            out.put(Categorizer.MISC, totalChests);
            return out;
        }
        // Start with one chest each, then distribute the rest proportionally by remaining need.
        int assigned = 0;
        Map<String, Double> ideal = new HashMap<>();
        for (String c : nonEmpty) {
            double chestsNeeded = volume.get(c) / avgSlots;
            ideal.put(c, chestsNeeded);
        }
        for (String c : nonEmpty) {
            int n = available >= nonEmpty.size() ? 1 : 0;
            out.put(c, n);
            assigned += n;
        }
        int remaining = available - assigned;
        if (remaining > 0 && needSum > 0) {
            double idealSum = 0;
            for (String c : nonEmpty) idealSum += Math.max(0, ideal.get(c) - 1);
            if (idealSum > 0) {
                Map<String, Double> frac = new HashMap<>();
                int given = 0;
                for (String c : nonEmpty) {
                    double share = Math.max(0, ideal.get(c) - 1) / idealSum * remaining;
                    int whole = (int) Math.floor(share);
                    whole = Math.min(whole, Math.max(0, (int) Math.ceil(ideal.get(c)) - out.get(c)));
                    out.merge(c, whole, Integer::sum);
                    given += whole;
                    frac.put(c, share - whole);
                }
                remaining -= given;
                // Largest fractional remainders get the leftover, but never beyond ceil(ideal).
                List<String> byFrac = new ArrayList<>(nonEmpty);
                byFrac.sort((a, b) -> Double.compare(frac.get(b), frac.get(a)));
                for (String c : byFrac) {
                    if (remaining <= 0) break;
                    if (out.get(c) < Math.ceil(ideal.get(c))) {
                        out.merge(c, 1, Integer::sum);
                        remaining--;
                    }
                }
            }
        }
        int used = 0;
        for (int v : out.values()) used += v;
        out.put(Categorizer.MISC, Math.max(reservedMisc, totalChests - used));
        return out;
    }

    /**
     * Replan: keep assignments that still fit; only new chests or overflowing categories move.
     */
    public Plan replan(Plan previous, List<ContainerEntry> orderedChests, Map<String, Integer> volume, BlockPos entrance) {
        Plan fresh = build(orderedChests, volume, previous, entrance);
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

        // Categories that need more chests take free (new) ones first, nearest to their existing chests in walk order.
        for (String cat : fresh.zones.keySet()) {
            int need = want.getOrDefault(cat, 0);
            Zone z = plan.zones.computeIfAbsent(cat, Zone::new);
            while (z.containerIds.size() < need && !free.isEmpty()) {
                UUID pick = nearestInWalk(orderedChests, z.containerIds, free);
                free.remove(pick);
                z.containerIds.add(pick);
            }
        }
        // Still short? Take surplus from categories with more than they need (Misc last).
        for (String cat : fresh.zones.keySet()) {
            Zone z = plan.zones.get(cat);
            int need = want.getOrDefault(cat, 0);
            while (z.containerIds.size() < need) {
                Zone donor = null;
                for (Zone other : plan.zones.values()) {
                    if (other == z) continue;
                    int otherNeed = want.getOrDefault(other.category, 0);
                    if (other.containerIds.size() > Math.max(1, otherNeed)) {
                        if (donor == null || other.category.equals(Categorizer.MISC) == false && donor.category.equals(Categorizer.MISC)) donor = other;
                    }
                }
                if (donor == null) break;
                UUID moved = donor.containerIds.remove(donor.containerIds.size() - 1);
                z.containerIds.add(moved);
            }
        }
        // Leftover free chests go to Misc.
        if (!free.isEmpty()) plan.zones.computeIfAbsent(Categorizer.MISC, Zone::new).containerIds.addAll(free);
        plan.zones.values().removeIf(z -> z.containerIds.isEmpty());
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
