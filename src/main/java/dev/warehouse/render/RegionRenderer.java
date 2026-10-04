package dev.warehouse.render;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import dev.warehouse.wand.PendingSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws region boxes with the vanilla gizmo API. Invoked at the end of every client tick
 * (the tick gizmo collector is active there and the shapes persist until the next tick).
 */
public final class RegionRenderer {
    private final RegionManager regions;
    private final PendingSelection selection;

    public RegionRenderer(RegionManager regions, PendingSelection selection) {
        this.regions = regions;
        this.selection = selection;
    }

    public void tick(Minecraft mc) {
        ModConfig cfg = ConfigIO.get();
        if (mc.level == null || mc.player == null) return;
        String dim = RegionManager.dimensionId(mc.level);
        try {
            if (cfg.showRegionOutlines) {
                for (Region r : regions.inDimension(dim)) {
                    int color = r.type == RegionType.WAREHOUSE ? cfg.warehouseColor : cfg.claimColor;
                    drawBox(r, color, mc);
                }
            }
            if (!selection.isEmpty() && dim.equals(selection.dimension)) {
                BlockPos a = selection.cornerA;
                BlockPos b = selection.cornerB != null ? selection.cornerB : a;
                AABB box = AABB.encapsulatingFullBlocks(a, b);
                Gizmos.cuboid(box, GizmoStyle.strokeAndFill(cfg.pendingSelectionColor, 2.5F, ARGB.color(40, cfg.pendingSelectionColor))).setAlwaysOnTop();
                Gizmos.cuboid(new AABB(a), GizmoStyle.stroke(cfg.pendingSelectionColor, 3.0F)).setAlwaysOnTop();
            }
        } catch (IllegalStateException ignored) {
            // No gizmo collector active (should not happen during tick); skip this frame.
        }
    }

    private static void drawBox(Region r, int color, Minecraft mc) {
        AABB box = new AABB(r.minX, r.minY, r.minZ, r.maxX + 1, r.maxY + 1, r.maxZ + 1);
        // Claims are full-height; clamp drawing to something sane around the player so the box is visible.
        if (r.type == RegionType.CLAIM && mc.player != null) {
            double py = mc.player.getY();
            box = new AABB(box.minX, Math.max(box.minY, py - 48), box.minZ, box.maxX, Math.min(box.maxY, py + 48), box.maxZ);
        }
        Gizmos.cuboid(box, GizmoStyle.stroke(color, 2.0F));
        Vec3 labelPos = new Vec3(box.minX + (box.maxX - box.minX) / 2.0, Math.min(box.maxY, (mc.player != null ? mc.player.getEyeY() : box.maxY) + 2.0), box.minZ + (box.maxZ - box.minZ) / 2.0);
        if (mc.player != null && mc.player.position().distanceToSqr(labelPos) < 64 * 64) {
            Gizmos.billboardText(r.name, labelPos, TextGizmo.Style.forColorAndCentered(color).withScale(0.4F)).setAlwaysOnTop();
        }
    }
}
