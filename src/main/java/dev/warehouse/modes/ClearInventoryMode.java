package dev.warehouse.modes;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Categorizer;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.organizer.Zone;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lights up destination chests for every non-essential inventory stack until the inventory is clear. */
public final class ClearInventoryMode {
    private boolean active;
    private boolean showEssentials;
    /** Container id -> labels (item x count) for this tick. */
    private final Map<UUID, List<String>> pendingByChest = new LinkedHashMap<>();
    private final Map<UUID, Integer> colorByChest = new LinkedHashMap<>();
    private int noRoomCount;
    private int lastAnnouncedNoRoom = -1;

    public boolean isActive() {
        return active;
    }

    public boolean showEssentials() {
        return showEssentials;
    }

    public void setShowEssentials(boolean b) {
        showEssentials = b;
    }

    public void toggle(Minecraft mc) {
        if (active) {
            exit("Clear-inventory mode off.");
            return;
        }
        if (!WarehouseClient.organizer().plan().isActive()) {
            Chat.error("No accepted plan. Run /warehouse plan run and accept it first.");
            return;
        }
        active = true;
        lastAnnouncedNoRoom = -1;
        refresh(mc);
        if (pendingByChest.isEmpty() && noRoomCount == 0) {
            exit("Nothing to put away — inventory is clear (essentials kept).");
        } else {
            Chat.info("Clear-inventory mode: " + pendingByChest.size() + " chest(s) lit. Walk to each and press Deposit matching.");
        }
    }

    private void exit(String msg) {
        active = false;
        pendingByChest.clear();
        colorByChest.clear();
        Chat.info(msg);
    }

    public void onDeposited(Minecraft mc) {
        if (!active) return;
        // Contents will arrive via the container packet; refresh next tick.
    }

    /** Called every client tick. */
    public void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null) return;
        refresh(mc);
        if (pendingByChest.isEmpty() && noRoomCount == 0) {
            exit("Inventory clear — clear-inventory mode off.");
            return;
        }
        if (noRoomCount != lastAnnouncedNoRoom && noRoomCount > 0) {
            lastAnnouncedNoRoom = noRoomCount;
            Chat.warn(noRoomCount + " stack(s) have no destination with room; they stay in your inventory.");
        }
        render(mc);
    }

    private void refresh(Minecraft mc) {
        pendingByChest.clear();
        noRoomCount = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            ContainerEntry dest = destinationFor(mc, st, i);
            if (dest == null) {
                if (!isEssential(mc, st, i)) noRoomCount++;
                continue;
            }
            pendingByChest.computeIfAbsent(dest.id, k -> new ArrayList<>()).add(Fingerprinter.displayName(st) + " ×" + st.getCount());
            colorByChest.computeIfAbsent(dest.id, k -> WarehouseClient.organizer().categoryColor(WarehouseClient.categories().categoryOf(Fingerprinter.key(st))));
        }
    }

    /** Destination for an inventory stack while the mode is active; null if essential, no plan, or no room. */
    public @Nullable ContainerEntry destinationFor(Minecraft mc, ItemStack stack, int inventorySlot) {
        if (!active || stack.isEmpty()) return null;
        if (isEssential(mc, stack, inventorySlot)) return null;
        Organizer organizer = WarehouseClient.organizer();
        ItemKey key = Fingerprinter.key(stack);
        Organizer.Resolution res = organizer.resolve(key);
        if (res == null) return null;
        ContainerEntry dest = res.container();
        if (dest.freeSlots() > 0 || dest.totalCount(key) > 0) return dest;
        // Full destination -> next chest in the same zone with room.
        Plan plan = organizer.plan();
        String zoneName = plan.zoneOf(dest.id);
        Zone zone = zoneName != null ? plan.zones.get(zoneName) : null;
        if (zone != null) {
            for (UUID id : zone.containerIds) {
                ContainerEntry e = WarehouseClient.index().byId(id);
                if (e != null && e.freeSlots() > 0) return e;
            }
        }
        return null;
    }

    public boolean isEssential(Minecraft mc, ItemStack stack, int inventorySlot) {
        ModConfig cfg = ConfigIO.get();
        if (inventorySlot >= Inventory.INVENTORY_SIZE) {
            // 36..39 armor, 40 offhand
            if (inventorySlot == 40) return cfg.essentialOffhand;
            return cfg.essentialEquippedArmor;
        }
        ItemKey key = Fingerprinter.key(stack);
        String id = key.itemId;
        for (String keep : cfg.alwaysKeep) {
            if (keep.equals(id) || keep.equals(key.asString())) return true;
        }
        ItemGroup g = WarehouseClient.itemGroups().groupFor(key);
        if (g != null) for (String keep : cfg.alwaysKeep) if (keep.equalsIgnoreCase(g.groupId)) return true;
        String cat = WarehouseClient.categories().categoryOf(key);
        boolean hotbar = inventorySlot < Inventory.SELECTION_SIZE;
        if (cfg.essentialHotbarTools && hotbar) {
            if (stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || cat.equals(Categorizer.TOOLS)) return true;
            Equippable eq = stack.get(DataComponents.EQUIPPABLE);
            if (eq != null && eq.slot().isArmor()) return true;
        }
        if (cfg.essentialCustomGear && !key.isVanilla() && (cat.equals(Categorizer.TOOLS) || cat.equals(Categorizer.ARMOR))) return true;
        if (cfg.essentialFood && (stack.has(DataComponents.FOOD) || cat.equals(Categorizer.FOOD))) return true;
        return false;
    }

    private void render(Minecraft mc) {
        String dim = RegionManager.dimensionId(mc.level);
        Vec3 eye = mc.player.getEyePosition().add(0, -0.3, 0);
        try {
            ContainerEntry nearest = null;
            double nearestD = Double.MAX_VALUE;
            for (Map.Entry<UUID, List<String>> en : pendingByChest.entrySet()) {
                ContainerEntry e = WarehouseClient.index().byId(en.getKey());
                if (e == null || !e.dimension.equals(dim)) continue;
                int color = colorByChest.getOrDefault(e.id, 0xFFFFFFFF);
                AABB box = e.secondaryPos != null ? AABB.encapsulatingFullBlocks(e.pos, e.secondaryPos) : new AABB(e.pos);
                float pulse = 0.6F + 0.4F * (float) Math.sin(System.currentTimeMillis() / 200.0);
                Gizmos.cuboid(box.inflate(0.03), GizmoStyle.strokeAndFill(color, 3.0F, ARGB.color((int) (80 * pulse), color))).setAlwaysOnTop();
                Vec3 c = box.getCenter();
                Gizmos.line(new Vec3(c.x, box.maxY, c.z), new Vec3(c.x, box.maxY + 8, c.z), ARGB.color(150, color), 2.0F).setAlwaysOnTop();
                List<String> labels = en.getValue();
                int shown = Math.min(labels.size(), 4);
                double d = c.distanceToSqr(eye);
                if (d < 40 * 40) {
                    for (int i = 0; i < shown; i++) {
                        Gizmos.billboardText(labels.get(i), new Vec3(c.x, box.maxY + 0.5 + (shown - i) * 0.28, c.z), TextGizmo.Style.forColorAndCentered(color).withScale(0.3F)).setAlwaysOnTop();
                    }
                    if (labels.size() > shown) {
                        Gizmos.billboardText("+" + (labels.size() - shown) + " more", new Vec3(c.x, box.maxY + 0.5, c.z), TextGizmo.Style.forColorAndCentered(0xFFAAAAAA).withScale(0.28F)).setAlwaysOnTop();
                    }
                }
                if (d < nearestD) {
                    nearestD = d;
                    nearest = e;
                }
            }
            if (nearest != null) {
                AABB box = nearest.secondaryPos != null ? AABB.encapsulatingFullBlocks(nearest.pos, nearest.secondaryPos) : new AABB(nearest.pos);
                Gizmos.line(eye, box.getCenter(), ARGB.color(180, colorByChest.getOrDefault(nearest.id, 0xFFFFFFFF)), 2.0F).setAlwaysOnTop();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public Map<UUID, List<String>> pending() {
        return pendingByChest;
    }
}
