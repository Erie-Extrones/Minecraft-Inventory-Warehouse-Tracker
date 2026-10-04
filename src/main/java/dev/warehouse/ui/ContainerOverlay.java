package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ScreenTracker;
import dev.warehouse.mixin.AbstractContainerScreenAccessor;
import dev.warehouse.modes.ClearInventoryMode;
import dev.warehouse.organizer.PlanDiff;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.warehouse.items.Fingerprinter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inside a tracked container screen: pulses misplaced container slots and inventory slots that belong here,
 * and offers "Take misplaced" / "Deposit matching" buttons that perform real quick-move clicks.
 */
public final class ContainerOverlay {
    private ContainerOverlay() {}

    public static void attach(Minecraft mc, AbstractContainerScreen<?> screen, ScreenTracker.Session s) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int left = acc.warehouse$leftPos();
        int top = acc.warehouse$topPos();
        int right = left + acc.warehouse$imageWidth();

        Button take = Button.builder(Component.literal("Take misplaced"), b -> takeMisplaced(mc, s))
                .bounds(right + 4, top + 4, 90, 16)
                .tooltip(Tooltip.create(Component.literal("Quick-move every stack that belongs in another zone into your inventory")))
                .build();
        Button deposit = Button.builder(Component.literal("Deposit matching"), b -> depositMatching(mc, s))
                .bounds(right + 4, top + 24, 90, 16)
                .tooltip(Tooltip.create(Component.literal("Quick-move every inventory stack that belongs in this chest")))
                .build();
        take.visible = false;
        deposit.visible = false;
        Screens.getWidgets(screen).add(take);
        Screens.getWidgets(screen).add(deposit);

        ScreenEvents.afterTick(screen).register(sc -> {
            take.visible = s.entry != null && !misplacedSlots(s).isEmpty();
            deposit.visible = s.entry != null && !matchingInventorySlots(mc, s).isEmpty();
        });
        ScreenEvents.afterBackground(screen).register((sc, graphics, mx, my, tick) -> draw(mc, graphics, s, left, top));
    }

    private static Set<Integer> misplacedSlots(ScreenTracker.Session s) {
        Set<Integer> out = new HashSet<>();
        if (s.entry == null) return out;
        for (PlanDiff.Misplaced m : WarehouseClient.planDiff().forContainer(s.entry.id)) out.add(m.slot());
        return out;
    }

    /** Menu slots (player inventory) whose stack belongs in this container: clear-mode destination, or plan resolution. */
    private static List<Slot> matchingInventorySlots(Minecraft mc, ScreenTracker.Session s) {
        List<Slot> out = new ArrayList<>();
        if (mc.player == null || s.entry == null) return out;
        ContainerEntry here = s.entry;
        ClearInventoryMode mode = WarehouseClient.clearMode();
        for (Slot slot : s.menu.slots) {
            if (slot.container != mc.player.getInventory() || !slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            int invSlot = slot.getContainerSlot();
            ContainerEntry dest;
            if (mode.isActive()) {
                dest = mode.destinationFor(mc, stack, invSlot); // null when essential or no room
            } else {
                var res = WarehouseClient.organizer().resolve(stack);
                dest = res != null ? res.container() : null;
            }
            if (dest != null && dest.id.equals(here.id)) out.add(slot);
        }
        return out;
    }

    private static void draw(Minecraft mc, GuiGraphicsExtractor g, ScreenTracker.Session s, int left, int top) {
        if (s.entry == null) return;
        float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 160.0);
        Set<Integer> misplaced = misplacedSlots(s);
        if (!misplaced.isEmpty()) {
            int color = ARGB.color((int) (60 + 100 * pulse), 0xFF4040);
            for (Slot slot : s.menu.slots) {
                if (slot.container == mc.player.getInventory()) continue;
                if (misplaced.contains(slot.getContainerSlot())) g.fill(left + slot.x - 1, top + slot.y - 1, left + slot.x + 17, top + slot.y + 17, color);
            }
        }
        List<Slot> matching = matchingInventorySlots(mc, s);
        if (!matching.isEmpty()) {
            int color = ARGB.color((int) (60 + 100 * pulse), 0x40FF80);
            for (Slot slot : matching) g.fill(left + slot.x - 1, top + slot.y - 1, left + slot.x + 17, top + slot.y + 17, color);
        }
        // Searched / found / guided item: pulse the container slots that hold it so it can be spotted in a full chest.
        List<dev.warehouse.items.ItemKey> wanted = new ArrayList<>(WarehouseClient.highlights().itemKeysFor(s.entry.id));
        var guide = WarehouseClient.heldItemGuide();
        if (guide.target() != null && guide.target().id.equals(s.entry.id) && guide.targetKey() != null) wanted.add(guide.targetKey());
        if (!wanted.isEmpty()) {
            int color = ARGB.color((int) (90 + 110 * pulse), 0xFFD040);
            for (Slot slot : s.menu.slots) {
                if (slot.container == mc.player.getInventory() || !slot.hasItem()) continue;
                dev.warehouse.items.ItemKey k = Fingerprinter.key(slot.getItem());
                for (dev.warehouse.items.ItemKey w : wanted) {
                    if (w.equals(k) || (w.isVanilla() && w.itemId.equals(k.itemId))) {
                        g.fill(left + slot.x - 1, top + slot.y - 1, left + slot.x + 17, top + slot.y + 17, color);
                        g.outline(left + slot.x - 1, top + slot.y - 1, 18, 18, 0xFFFFE080);
                        break;
                    }
                }
            }
        }
    }

    private static void takeMisplaced(Minecraft mc, ScreenTracker.Session s) {
        if (mc.player == null || mc.gameMode == null) return;
        Set<Integer> misplaced = misplacedSlots(s);
        for (Slot slot : s.menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem()) continue;
            if (misplaced.contains(slot.getContainerSlot())) {
                mc.gameMode.handleContainerInput(s.menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
            }
        }
    }

    private static void depositMatching(Minecraft mc, ScreenTracker.Session s) {
        if (mc.player == null || mc.gameMode == null) return;
        for (Slot slot : matchingInventorySlots(mc, s)) {
            mc.gameMode.handleContainerInput(s.menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        }
        WarehouseClient.clearMode().onDeposited(mc);
    }
}
