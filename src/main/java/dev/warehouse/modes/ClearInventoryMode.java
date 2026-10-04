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
import dev.warehouse.organizer.PlanDiff;
import dev.warehouse.organizer.RoutePlanner;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Route-guided put-away. CLEAR mode lights the destination chest of every non-essential inventory stack;
 * SORT mode additionally visits chests holding misplaced items so they can be taken and re-homed.
 * Stops are ordered into a short walking tour from the player and numbered in the world.
 */
public final class ClearInventoryMode {
    public enum Kind { CLEAR, SORT }

    private boolean active;
    private Kind kind = Kind.CLEAR;
    private boolean showEssentials;
    /** Destination chest id -> labels (item x count) for stacks in the inventory. */
    private final Map<UUID, List<String>> pendingByChest = new LinkedHashMap<>();
    private final Map<UUID, Integer> colorByChest = new LinkedHashMap<>();
    /** Source chest id -> misplaced stack count (SORT only). */
    private final Map<UUID, Integer> takeByChest = new LinkedHashMap<>();
    private List<ContainerEntry> route = List.of();
    private int routeTick;
    private Set<UUID> lastStopSet = Set.of();
    private int noRoomCount;
    private int lastAnnouncedNoRoom = -1;

    public boolean isActive() {
        return active;
    }

    public Kind kind() {
        return kind;
    }

    public boolean showEssentials() {
        return showEssentials;
    }

    public void setShowEssentials(boolean b) {
        showEssentials = b;
    }

    public List<ContainerEntry> route() {
        return route;
    }

    public void toggle(Minecraft mc) {
        toggle(mc, Kind.CLEAR);
    }

    public void toggle(Minecraft mc, Kind wanted) {
        if (active && kind == wanted) {
            exit((kind == Kind.SORT ? "Sort" : "Clear-inventory") + " mode off.");
            return;
        }
        if (!WarehouseClient.organizer().plan().isActive()) {
            Chat.error("No accepted plan. Run /warehouse plan run and accept it first.");
            return;
        }
        active = true;
        kind = wanted;
        lastAnnouncedNoRoom = -1;
        lastStopSet = Set.of();
        refresh(mc);
        if (pendingByChest.isEmpty() && takeByChest.isEmpty() && noRoomCount == 0) {
            exit(kind == Kind.SORT ? "Nothing to sort — no misplaced items and the inventory is clear." : "Nothing to put away — inventory is clear (essentials kept).");
        } else {
            Chat.info((kind == Kind.SORT ? "Sort mode: " : "Clear-inventory mode: ") + (pendingByChest.size() + takeByChest.size()) + " stop(s). Follow the particles; stop 1 is lit brightest.");
        }
    }

    private void exit(String msg) {
        active = false;
        pendingByChest.clear();
        colorByChest.clear();
        takeByChest.clear();
        route = List.of();
        Chat.info(msg);
    }

    public void onDeposited(Minecraft mc) {
        // Contents arrive via the screen tracker; the next tick's refresh picks them up.
    }

    /** Called every client tick. */
    public void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null) return;
        refresh(mc);
        if (pendingByChest.isEmpty() && takeByChest.isEmpty() && noRoomCount == 0) {
            exit(kind == Kind.SORT ? "Everything sorted — sort mode off." : "Inventory clear — clear-inventory mode off.");
            return;
        }
        if (noRoomCount != lastAnnouncedNoRoom && noRoomCount > 0) {
            lastAnnouncedNoRoom = noRoomCount;
            Chat.warn(noRoomCount + " stack(s) have no destination with room; they stay in your inventory.");
        }
        updateRoute(mc);
        render(mc);
    }

    private void refresh(Minecraft mc) {
        pendingByChest.clear();
        takeByChest.clear();
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
            colorByChest.computeIfAbsent(dest.id, k -> WarehouseClient.organizer().categoryColor(WarehouseClient.categories().categoryOf(st)));
        }
        if (kind == Kind.SORT) {
            for (PlanDiff.Misplaced m : WarehouseClient.planDiff().entries()) takeByChest.merge(m.source().id, 1, Integer::sum);
        }
    }

    private void updateRoute(Minecraft mc) {
        Set<UUID> stops = new LinkedHashSet<>(pendingByChest.keySet());
        Inventory inv = mc.player.getInventory();
        int freeSlots = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (inv.getItem(i).isEmpty()) freeSlots++;
        // Pick-ups only count while there is room to carry; otherwise offload first.
        if (kind == Kind.SORT && freeSlots >= 4) stops.addAll(takeByChest.keySet());
        if (stops.isEmpty()) stops.addAll(takeByChest.keySet());
        boolean changed = !stops.equals(lastStopSet);
        if (!changed && ++routeTick % 60 != 0) return;
        routeTick = 0;
        lastStopSet = stops;
        List<ContainerEntry> entries = new ArrayList<>();
        String dim = RegionManager.dimensionId(mc.level);
        for (UUID id : stops) {
            ContainerEntry e = WarehouseClient.index().byId(id);
            if (e != null && e.dimension.equals(dim)) entries.add(e);
        }
        route = RoutePlanner.order(mc.player.position(), entries);
    }

    /** Destination for an inventory stack while the mode is active; null if essential, no plan, or no room. */
    public @Nullable ContainerEntry destinationFor(Minecraft mc, ItemStack stack, int inventorySlot) {
        if (!active || stack.isEmpty()) return null;
        if (isEssential(mc, stack, inventorySlot)) return null;
        Organizer organizer = WarehouseClient.organizer();
        Organizer.Resolution res = organizer.resolve(stack);
        if (res == null) return null;
        ContainerEntry dest = res.container();
        ItemKey key = Fingerprinter.key(stack);
        if (dest.freeSlots() > 0 || dest.totalCount(key) > 0) return dest;
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

    private static AABB boxOf(ContainerEntry e) {
        return e.secondaryPos != null ? AABB.encapsulatingFullBlocks(e.pos, e.secondaryPos) : new AABB(e.pos);
    }

    private void render(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        float pulse = 0.6F + 0.4F * (float) Math.sin(System.currentTimeMillis() / 200.0);
        try {
            for (int i = 0; i < route.size(); i++) {
                ContainerEntry e = route.get(i);
                boolean next = i == 0;
                boolean deposit = pendingByChest.containsKey(e.id);
                boolean take = takeByChest.containsKey(e.id);
                int color = deposit ? colorByChest.getOrDefault(e.id, 0xFFFFFFFF) : 0xFFFF7050;
                AABB box = boxOf(e);
                float stroke = next ? 3.5F : 1.5F;
                int fill = ARGB.color(next ? (int) (90 * pulse) : 25, color);
                Gizmos.cuboid(box.inflate(next ? 0.04 : 0.01), GizmoStyle.strokeAndFill(next ? color : ARGB.color(140, color), stroke, fill)).setAlwaysOnTop();
                Vec3 c = box.getCenter();
                if (next) Gizmos.line(new Vec3(c.x, box.maxY, c.z), new Vec3(c.x, box.maxY + 8, c.z), ARGB.color(150, color), 2.0F).setAlwaysOnTop();
                double d = c.distanceToSqr(eye);
                if (d < 48 * 48 || next) {
                    String head = (i + 1) + (take && deposit ? "  take + deposit" : take ? "  take " + takeByChest.get(e.id) + " misplaced" : "  deposit");
                    Gizmos.billboardText(head, new Vec3(c.x, box.maxY + 0.55, c.z), TextGizmo.Style.forColorAndCentered(next ? 0xFFFFFFFF : color).withScale(next ? 0.4F : 0.3F)).setAlwaysOnTop();
                    if (deposit && (next || d < 16 * 16)) {
                        List<String> labels = pendingByChest.get(e.id);
                        int shown = Math.min(labels.size(), next ? 5 : 2);
                        for (int k = 0; k < shown; k++) {
                            Gizmos.billboardText(labels.get(k), new Vec3(c.x, box.maxY + 0.85 + (shown - k) * 0.26, c.z), TextGizmo.Style.forColorAndCentered(color).withScale(0.28F)).setAlwaysOnTop();
                        }
                        if (labels.size() > shown) Gizmos.billboardText("+" + (labels.size() - shown) + " more", new Vec3(c.x, box.maxY + 0.85, c.z), TextGizmo.Style.forColorAndCentered(0xFFAAAAAA).withScale(0.26F)).setAlwaysOnTop();
                    }
                }
                if (next) WarehouseClient.guidePath().request(box, color, 20);
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public Map<UUID, List<String>> pending() {
        return pendingByChest;
    }

    public Map<UUID, Integer> takeByChest() {
        return takeByChest;
    }
}
