package dev.warehouse.inventory;

import dev.warehouse.WarehouseClient;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.region.RegionManager;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Writes lost-log entries for Q-drops, GUI drops and deaths. Called from mixins and the death screen hook. */
public final class DropDetector {
    private static final Map<ItemKey, Long> recentDrops = new HashMap<>();
    private static long lastDeathLoggedMs;

    private DropDetector() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof DeathScreen) onDeath(mc);
        });
    }

    static boolean wasRecentlyDropped(ItemKey key) {
        Long t = recentDrops.get(key);
        return t != null && System.currentTimeMillis() - t < 2000;
    }

    /** Hotbar drop (Q / ctrl-Q). Called before the client-side prediction removes the item. */
    public static void onHotbarDrop(Minecraft mc, boolean all) {
        if (mc.player == null || mc.level == null || !WarehouseClient.storage().isBound()) return;
        ItemStack sel = mc.player.getInventory().getSelectedItem();
        if (sel.isEmpty()) return;
        int count = all ? sel.getCount() : 1;
        log(mc, sel, count, LostLog.DROP);
    }

    /** Container click. THROW on a slot (Q in GUI) or a PICKUP click outside the window with a carried stack. */
    public static void onContainerClick(Minecraft mc, int slotNum, int buttonNum, ContainerInput input) {
        if (mc.player == null || mc.level == null || !WarehouseClient.storage().isBound()) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (input == ContainerInput.THROW && slotNum >= 0 && slotNum < menu.slots.size()) {
            Slot slot = menu.getSlot(slotNum);
            if (!slot.hasItem()) return;
            int count = buttonNum == 1 ? slot.getItem().getCount() : 1;
            log(mc, slot.getItem(), count, LostLog.GUI_DROP);
        } else if (slotNum == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && (input == ContainerInput.PICKUP)) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) return;
            int count = buttonNum == 1 ? 1 : carried.getCount();
            log(mc, carried, count, LostLog.GUI_DROP);
        }
    }

    private static void onDeath(Minecraft mc) {
        if (mc.player == null || mc.level == null || !WarehouseClient.storage().isBound()) return;
        if (System.currentTimeMillis() - lastDeathLoggedMs < 3000) return; // death screen can re-init on resize
        lastDeathLoggedMs = System.currentTimeMillis();
        Inventory inv = mc.player.getInventory();
        int stacks = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            log(mc, s, s.getCount(), LostLog.DEATH);
            stacks++;
        }
        if (stacks > 0) {
            dev.warehouse.Chat.warn("Death at " + (int) mc.player.getX() + ", " + (int) mc.player.getY() + ", " + (int) mc.player.getZ()
                    + " — " + stacks + " stack(s) written to the lost log (/warehouse lost).");
        }
    }

    private static void log(Minecraft mc, ItemStack stack, int count, String reason) {
        recentDrops.put(Fingerprinter.key(stack), System.currentTimeMillis());
        WarehouseClient.lostLog().add(stack, count, RegionManager.dimensionId(mc.level), mc.player.position(), reason);
    }
}
