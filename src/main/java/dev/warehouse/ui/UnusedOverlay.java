package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.inventory.UsageTracker;
import dev.warehouse.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Tints the player-inventory slots holding unused stacks in every container screen while the inventory is full. */
public final class UnusedOverlay {
    private UnusedOverlay() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register(UnusedOverlay::onInit);
    }

    private static void onInit(Minecraft mc, Screen screen, int w, int h) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
        if (WarehouseClient.get() == null) return;
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) cs;
        ScreenEvents.afterBackground(screen).register((sc, g, mx, my, tick) -> {
            UsageTracker usage = WarehouseClient.usage();
            if (mc.player == null || usage == null || !usage.highlightArmed() || usage.idle().isEmpty()) return;
            int left = acc.warehouse$leftPos();
            int top = acc.warehouse$topPos();
            float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 200.0);
            int color = ARGB.color((int) (70 + 90 * pulse), 0xB070FF);
            for (Slot slot : cs.getMenu().slots) {
                if (slot.container != mc.player.getInventory() || !slot.hasItem()) continue;
                int inv = slot.getContainerSlot();
                if (inv >= Inventory.INVENTORY_SIZE) continue;
                for (UsageTracker.Idle i : usage.idle()) {
                    if (i.slot() == inv) {
                        g.fill(left + slot.x - 1, top + slot.y - 1, left + slot.x + 17, top + slot.y + 17, color);
                        g.outline(left + slot.x - 1, top + slot.y - 1, 18, 18, 0xFFC090FF);
                        break;
                    }
                }
            }
        });
    }
}
