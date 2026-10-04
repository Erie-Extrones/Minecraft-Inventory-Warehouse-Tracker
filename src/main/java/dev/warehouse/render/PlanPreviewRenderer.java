package dev.warehouse.render;

import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.organizer.Zone;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Colors every planned chest by its category while preview is on. */
public final class PlanPreviewRenderer {
    private final Organizer organizer;
    private final ContainerIndex index;

    public PlanPreviewRenderer(Organizer organizer, ContainerIndex index) {
        this.organizer = organizer;
        this.index = index;
    }

    public void tick(Minecraft mc) {
        if (!organizer.previewEnabled() || mc.level == null || mc.player == null) return;
        Plan plan = organizer.pending() != null ? organizer.pending() : organizer.plan();
        if (!plan.exists()) return;
        String dim = RegionManager.dimensionId(mc.level);
        Vec3 eye = mc.player.getEyePosition();
        try {
            for (Zone z : plan.zones.values()) {
                int color = organizer.categoryColor(z.category);
                for (UUID id : z.containerIds) {
                    ContainerEntry e = index.byId(id);
                    if (e == null || !e.dimension.equals(dim) || e.kind.isEntity()) continue;
                    AABB box = e.secondaryPos != null ? AABB.encapsulatingFullBlocks(e.pos, e.secondaryPos) : new AABB(e.pos);
                    if (box.getCenter().distanceToSqr(eye) > 64 * 64) continue;
                    Gizmos.cuboid(box.inflate(0.01), GizmoStyle.strokeAndFill(color, 1.5F, ARGB.color(50, color)));
                    if (box.getCenter().distanceToSqr(eye) < 20 * 20) {
                        Gizmos.billboardText(z.category, box.getCenter().add(0, 0.9, 0), TextGizmo.Style.forColorAndCentered(color).withScale(0.25F)).setAlwaysOnTop();
                    }
                }
            }
        } catch (IllegalStateException ignored) {
        }
    }
}
