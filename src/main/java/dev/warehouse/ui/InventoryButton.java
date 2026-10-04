package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/** Adds a "Warehouse" button above the survival inventory that opens the search screen. */
public final class InventoryButton {
    private InventoryButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register(InventoryButton::onInit);
    }

    private static void onInit(Minecraft mc, Screen screen, int w, int h) {
        if (!(screen instanceof InventoryScreen inv) || !ConfigIO.get().inventoryButton) return;
        if (WarehouseClient.get() == null || !WarehouseClient.storage().isBound()) return;
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) inv;
        Button button = Button.builder(Component.literal("Warehouse"), b -> mc.gui.setScreen(new SearchScreen()))
                .bounds(acc.warehouse$leftPos() + acc.warehouse$imageWidth() - 64, Math.max(0, acc.warehouse$topPos() - 16), 64, 14)
                .tooltip(Tooltip.create(Component.literal("Open the Warehouse search, lost log, misplaced list and plan")))
                .build();
        Screens.getWidgets(screen).add(button);
        // The recipe book shifts the inventory panel when toggled; follow it.
        ScreenEvents.afterTick(screen).register(sc -> {
            button.setX(acc.warehouse$leftPos() + acc.warehouse$imageWidth() - 64);
            button.setY(Math.max(0, acc.warehouse$topPos() - 16));
        });
    }
}
