package dev.warehouse.render;

import dev.warehouse.config.ConfigIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Leads the player to one target with a dust-particle trail along a walkable path (A* over blocks,
 * straight line fallback). Callers request a target each tick; the highest priority wins.
 */
public final class GuidePath {
    private @Nullable AABB requested;
    private int requestedColor;
    private int requestedPriority = Integer.MIN_VALUE;

    private @Nullable AABB target;
    private int color;
    private List<Vec3> path = List.of();
    private BlockPos pathStart;
    private int ticksSincePath = 999;
    private int emitTick;

    /** Ask to guide toward a box this tick. Higher priority requests win; ties keep the first. */
    public void request(AABB box, int argb, int priority) {
        if (priority > requestedPriority) {
            requested = box;
            requestedColor = argb;
            requestedPriority = priority;
        }
    }

    public void tick(Minecraft mc) {
        AABB req = requested;
        int reqColor = requestedColor;
        requested = null;
        requestedPriority = Integer.MIN_VALUE;
        if (req == null || mc.player == null || mc.level == null) {
            target = null;
            path = List.of();
            return;
        }
        boolean changed = target == null || !sameBox(target, req);
        target = req;
        color = reqColor;
        if (ConfigIO.get().guideLine) {
            try {
                Gizmos.line(mc.player.getEyePosition().add(0, -0.4, 0), req.getCenter(), ARGB.color(190, color), 2.0F).setAlwaysOnTop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (!ConfigIO.get().guideParticles) return;

        BlockPos feet = mc.player.blockPosition();
        ticksSincePath++;
        boolean farFromStart = pathStart == null || feet.distSqr(pathStart) > 9;
        if (changed || ticksSincePath > 40 || (farFromStart && ticksSincePath > 5)) {
            path = findPath(mc.level, feet, req);
            pathStart = feet;
            ticksSincePath = 0;
        }
        if (++emitTick % 3 != 0) return;
        emit(mc, path, req);
    }

    private static boolean sameBox(AABB a, AABB b) {
        return a.minX == b.minX && a.minY == b.minY && a.minZ == b.minZ && a.maxX == b.maxX && a.maxY == b.maxY && a.maxZ == b.maxZ;
    }

    private void emit(Minecraft mc, List<Vec3> pts, AABB box) {
        if (pts.isEmpty()) return;
        DustParticleOptions dust = new DustParticleOptions(color & 0xFFFFFF, 0.9F);
        int max = Math.max(10, ConfigIO.get().guideTrailMaxPoints);
        Vec3 eye = mc.player.getEyePosition();
        int n = 0;
        for (Vec3 p : pts) {
            if (n++ >= max) break;
            if (p.distanceToSqr(eye) < 2.0) continue; // don't spawn in the player's face
            mc.level.addParticle(dust, p.x, p.y, p.z, 0, 0, 0);
        }
        // Pulse at the target itself so the end of the trail is obvious.
        Vec3 c = box.getCenter();
        mc.level.addParticle(dust, c.x, box.maxY + 0.3, c.z, 0, 0.02, 0);
    }

    // ------------------------------------------------------------------ pathfinding

    private static final int MAX_EXPANSIONS = 6000;
    private static final double MAX_RANGE = 128;

    /** Points (about every half block) from the player to a block adjacent to the target. */
    static List<Vec3> findPath(Level level, BlockPos feet, AABB target) {
        Vec3 goalCenter = target.getCenter();
        if (Vec3.atCenterOf(feet).distanceTo(goalCenter) > MAX_RANGE) return straight(Vec3.atCenterOf(feet), goalCenter);
        BlockPos start = standable(level, feet);
        if (start == null) return straight(Vec3.atCenterOf(feet), goalCenter);

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> g = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        Map<Long, BlockPos> nodes = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        g.put(start, 0.0);
        nodes.put(start.asLong(), start);
        open.add(new double[]{heuristic(start, goalCenter), start.asLong()});
        int expansions = 0;
        BlockPos found = null;
        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            double[] top = open.poll();
            BlockPos cur = nodes.get((long) top[1]);
            if (cur == null || !closed.add(cur)) continue;
            expansions++;
            if (reaches(cur, target)) {
                found = cur;
                break;
            }
            double gc = g.get(cur);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = cur.relative(d);
                BlockPos step;
                if (walkable(level, n)) step = n;                                                   // flat
                else if (walkable(level, n.below())) step = n.below();                              // step down
                else if (walkable(level, n.above()) && passable(level, cur.above(2))) step = n.above(); // step up
                else continue;
                if (closed.contains(step)) continue;
                double ng = gc + 1 + (step.getY() != cur.getY() ? 0.5 : 0);
                if (ng < g.getOrDefault(step, Double.MAX_VALUE)) {
                    g.put(step, ng);
                    cameFrom.put(step, cur);
                    nodes.put(step.asLong(), step);
                    open.add(new double[]{ng + heuristic(step, goalCenter), step.asLong()});
                }
            }
        }
        if (found == null) return straight(Vec3.atCenterOf(feet), goalCenter);
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos p = found; p != null; p = cameFrom.get(p)) cells.add(p);
        Collections.reverse(cells);
        List<Vec3> pts = new ArrayList<>();
        Vec3 prev = null;
        for (BlockPos c : cells) {
            Vec3 v = new Vec3(c.getX() + 0.5, c.getY() + 0.15, c.getZ() + 0.5);
            if (prev != null) pts.add(prev.add(v.subtract(prev).scale(0.5)));
            pts.add(v);
            prev = v;
        }
        return pts;
    }

    private static boolean reaches(BlockPos node, AABB target) {
        double dx = Math.max(target.minX - (node.getX() + 1), Math.max(0, node.getX() - target.maxX));
        double dz = Math.max(target.minZ - (node.getZ() + 1), Math.max(0, node.getZ() - target.maxZ));
        double dy = Math.max(target.minY - (node.getY() + 2), Math.max(0, node.getY() - target.maxY));
        return dx <= 0.01 && dz <= 0.01 && dy <= 1.01;
    }

    private static double heuristic(BlockPos p, Vec3 goal) {
        return Math.abs(p.getX() + 0.5 - goal.x) + Math.abs(p.getZ() + 0.5 - goal.z) + Math.abs(p.getY() - goal.y);
    }

    private static boolean passable(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static boolean walkable(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above()) && !passable(level, feet.below());
    }

    private static @Nullable BlockPos standable(Level level, BlockPos feet) {
        if (walkable(level, feet)) return feet;
        if (walkable(level, feet.below())) return feet.below();
        if (walkable(level, feet.above())) return feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) if (walkable(level, feet.relative(d))) return feet.relative(d);
        return null;
    }

    private static List<Vec3> straight(Vec3 a, Vec3 b) {
        List<Vec3> pts = new ArrayList<>();
        double len = a.distanceTo(b);
        int n = (int) Math.max(2, Math.min(200, len * 2));
        for (int i = 1; i <= n; i++) pts.add(a.add(b.subtract(a).scale(i / (double) n)));
        return pts;
    }
}
