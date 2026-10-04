package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Passive guide: while the player stands inside a Warehouse region holding an item, the chest that item
 * belongs in (plan destination, else where it is currently stored) stays highlighted with a guide line.
 */
public final class HeldItemGuide {
    private ItemStack lastStack = ItemStack.EMPTY;
    private @Nullable ItemKey lastKey;
    private @Nullable ContainerEntry target;
    private String targetLabel = "";
    private int targetColor;
    private int recheckTicks;

    public void invalidate() {
        lastStack = ItemStack.EMPTY;
        lastKey = null;
        target = null;
    }

    public void tick(Minecraft mc) {
        if (!ConfigIO.get().heldItemGuide || mc.player == null || mc.level == null) return;
        if (WarehouseClient.clearMode().isActive()) return; // clear mode already lights destinations
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            invalidate();
            return;
        }
        String dim = RegionManager.dimensionId(mc.level);
        if (WarehouseClient.regions().warehouseAt(dim, mc.player.blockPosition()) == null) return;

        boolean changed = !ItemStack.isSameItemSameComponents(held, lastStack);
        if (changed || --recheckTicks <= 0) {
            recheckTicks = 20; // re-resolve once a second so deposits / replans are picked up
            if (changed) {
                lastStack = held.copy();
                lastKey = Fingerprinter.key(held);
            }
            resolve(mc, lastKey, held);
        }
        if (target == null || !target.dimension.equals(dim)) return;
        draw(mc, held);
    }

    private void resolve(Minecraft mc, ItemKey key, ItemStack held) {
        target = null;
        Organizer.Resolution res = WarehouseClient.organizer().resolve(held);
        if (res != null) {
            target = res.container();
            targetLabel = "Belongs: " + res.category();
            targetColor = WarehouseClient.organizer().categoryColor(res.category());
            return;
        }
        List<ContainerEntry> holders = WarehouseClient.index().holding(key);
        if (!holders.isEmpty()) {
            target = holders.get(0);
            targetLabel = "Stored here ×" + target.totalCount(key);
            targetColor = ConfigIO.get().highlightColor;
        }
    }

    private void draw(Minecraft mc, ItemStack held) {
        try {
            AABB box = target.secondaryPos != null ? AABB.encapsulatingFullBlocks(target.pos, target.secondaryPos) : new AABB(target.pos);
            if (target.kind.isEntity() && target.entityId != null) {
                var e = mc.level.getEntity(target.entityId);
                if (e != null) box = e.getBoundingBox().inflate(0.1);
            }
            float pulse = 0.6F + 0.4F * (float) Math.sin(System.currentTimeMillis() / 220.0);
            Gizmos.cuboid(box.inflate(0.02), GizmoStyle.strokeAndFill(targetColor, 3.0F, ARGB.color((int) (70 * pulse), targetColor))).setAlwaysOnTop();
            Vec3 c = box.getCenter();
            Gizmos.line(new Vec3(c.x, box.maxY, c.z), new Vec3(c.x, box.maxY + 5, c.z), ARGB.color(150, targetColor), 2.0F).setAlwaysOnTop();
            Gizmos.billboardText(targetLabel, new Vec3(c.x, box.maxY + 0.6, c.z), TextGizmo.Style.forColorAndCentered(targetColor).withScale(0.32F)).setAlwaysOnTop();
            WarehouseClient.guidePath().request(box, targetColor, 10);
        } catch (IllegalStateException ignored) {
        }
    }
}
