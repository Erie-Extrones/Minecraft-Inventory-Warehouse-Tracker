package dev.warehouse.organizer;

import dev.warehouse.index.ContainerEntry;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Orders chest stops into a short walking tour: nearest-neighbour from the start, then 2-opt improvement. */
public final class RoutePlanner {
    private RoutePlanner() {}

    public static Vec3 center(ContainerEntry e) {
        if (e.secondaryPos != null) {
            return new Vec3((e.pos.getX() + e.secondaryPos.getX()) / 2.0 + 0.5, e.pos.getY() + 0.5, (e.pos.getZ() + e.secondaryPos.getZ()) / 2.0 + 0.5);
        }
        return new Vec3(e.pos.getX() + 0.5, e.pos.getY() + 0.5, e.pos.getZ() + 0.5);
    }

    /** Walking distance proxy: Manhattan on the horizontal plane plus a vertical penalty. */
    static double dist(Vec3 a, Vec3 b) {
        return Math.abs(a.x - b.x) + Math.abs(a.z - b.z) + 3.0 * Math.abs(a.y - b.y);
    }

    public static List<ContainerEntry> order(Vec3 start, List<ContainerEntry> stops) {
        List<ContainerEntry> remaining = new ArrayList<>(stops);
        List<ContainerEntry> tour = new ArrayList<>(stops.size());
        Vec3 cur = start;
        while (!remaining.isEmpty()) {
            ContainerEntry best = null;
            double bestD = Double.MAX_VALUE;
            for (ContainerEntry e : remaining) {
                double d = dist(cur, center(e));
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            tour.add(best);
            remaining.remove(best);
            cur = center(best);
        }
        twoOpt(start, tour);
        return tour;
    }

    private static void twoOpt(Vec3 start, List<ContainerEntry> tour) {
        int n = tour.size();
        if (n < 4) return;
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 50) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                Vec3 a = i == 0 ? start : center(tour.get(i - 1));
                Vec3 b = center(tour.get(i));
                for (int k = i + 1; k < n; k++) {
                    Vec3 c = center(tour.get(k));
                    Vec3 d = k + 1 < n ? center(tour.get(k + 1)) : null;
                    double before = dist(a, b) + (d != null ? dist(c, d) : 0);
                    double after = dist(a, c) + (d != null ? dist(b, d) : 0);
                    if (after + 1e-6 < before) {
                        reverse(tour, i, k);
                        improved = true;
                    }
                }
            }
        }
    }

    private static void reverse(List<ContainerEntry> t, int i, int k) {
        while (i < k) {
            ContainerEntry tmp = t.get(i);
            t.set(i, t.get(k));
            t.set(k, tmp);
            i++;
            k--;
        }
    }
}
