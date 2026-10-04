package dev.warehouse.ui;

import dev.warehouse.index.ContainerKind;
import dev.warehouse.index.ScreenTracker;
import dev.warehouse.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/** Small toggle in the top-right corner of allow-listed container screens. */
public final class ContainerPinButton {
    private ContainerPinButton() {}

    public static void attach(Minecraft mc, AbstractContainerScreen<?> screen, ScreenTracker tracker) {
        ScreenTracker.Session s = tracker.session();
        if (s == null || s.resolved.kind() == ContainerKind.ENDER_CHEST) return;
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int x = acc.warehouse$leftPos() + acc.warehouse$imageWidth() - 20;
        int y = acc.warehouse$topPos() - 12;
        Button[] holder = new Button[1];
        holder[0] = Button.builder(label(tracker), b -> {
                    tracker.togglePin(mc);
                    b.setMessage(label(tracker));
                    b.setTooltip(Tooltip.create(tooltip(tracker)));
                })
                .bounds(x, Math.max(0, y), 20, 12)
                .tooltip(Tooltip.create(tooltip(tracker)))
                .build();
        Screens.getWidgets(screen).add(holder[0]);
    }

    private static Component label(ScreenTracker tracker) {
        if (tracker.isPinned()) return Component.literal("📌").withStyle(ChatFormatting.GOLD);
        if (tracker.isInsideRegion()) return Component.literal("W").withStyle(ChatFormatting.AQUA);
        return Component.literal("W").withStyle(ChatFormatting.GRAY);
    }

    private static Component tooltip(ScreenTracker tracker) {
        if (tracker.isPinned()) return Component.literal("Warehouse: pinned (click to unpin)");
        if (tracker.isInsideRegion()) return Component.literal("Warehouse: inside a region, auto-indexed. Click to pin anyway.");
        return Component.literal("Warehouse: outside regions, not indexed. Click to pin.");
    }
}
