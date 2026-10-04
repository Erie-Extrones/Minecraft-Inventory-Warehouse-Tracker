package dev.warehouse.organizer;

import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.ItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Misplaced-item list: stacks sitting in a chest whose zone differs from where they resolve to. */
public final class PlanDiff {
    public record Misplaced(ItemKey key, String displayName, int count, int slot, ContainerEntry source, String sourceZone, ContainerEntry dest, String destZone) {}

    private final ContainerIndex index;
    private final Organizer organizer;
    private List<Misplaced> cached = List.of();
    private int unopenedSincePlan;
    private boolean dirty = true;

    public PlanDiff(ContainerIndex index, Organizer organizer) {
        this.index = index;
        this.organizer = organizer;
    }

    public void invalidate() {
        dirty = true;
    }

    public List<Misplaced> entries() {
        if (dirty) rebuild();
        return cached;
    }

    public int unopenedSincePlan() {
        if (dirty) rebuild();
        return unopenedSincePlan;
    }

    /** Misplaced stacks in one container (by source container id). */
    public List<Misplaced> forContainer(UUID containerId) {
        List<Misplaced> out = new ArrayList<>();
        for (Misplaced m : entries()) if (m.source().id.equals(containerId)) out.add(m);
        return out;
    }

    private void rebuild() {
        dirty = false;
        Plan plan = organizer.plan();
        List<Misplaced> out = new ArrayList<>();
        int unopened = 0;
        if (plan.isActive()) {
            for (ContainerEntry e : index.all()) {
                String zone = plan.zoneOf(e.id);
                if (zone == null) continue;
                if (e.lastSeenEpochMs < plan.createdEpochMs) unopened++;
                for (StackRecord s : e.contents) {
                    Organizer.Resolution res = organizer.resolve(s.key);
                    if (res == null) continue;
                    String destZone = plan.zoneOf(res.container().id);
                    if (destZone == null || destZone.equals(zone)) continue; // same-zone overflow is not misplaced
                    out.add(new Misplaced(s.key, s.displayName, s.count, s.slot, e, zone, res.container(), destZone));
                }
            }
            out.sort(Comparator.comparing((Misplaced m) -> m.source().posString())
                    .thenComparing(Misplaced::destZone)
                    .thenComparing(Comparator.comparingInt(Misplaced::count).reversed()));
        }
        cached = out;
        unopenedSincePlan = unopened;
    }
}
