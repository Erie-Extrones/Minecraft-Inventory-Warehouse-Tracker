package dev.warehouse.jei;

import com.mojang.blaze3d.platform.InputConstants;
import dev.warehouse.Chat;
import dev.warehouse.Keybinds;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Two keys that work while JEI is showing an item under the mouse: find it in the warehouse (highlights the chest and
 * closes the screen) and plan a craft of it (opens chat with the craft command prefilled so you type the count).
 */
public final class WarehouseJeiClient implements ClientModInitializer {
    public static KeyMapping find, craft;

    @Override
    public void onInitializeClient() {
        find = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.warehouse_jei.find", InputConstants.Type.KEYBOARD, InputConstants.KEY_W, Keybinds.CATEGORY));
        craft = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.warehouse_jei.craft", InputConstants.Type.KEYBOARD, InputConstants.KEY_C, Keybinds.CATEGORY));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenKeyboardEvents.afterKeyPress(screen).register((scr, event) -> {
            if (find.matches(event)) onFind(client);
            else if (craft.matches(event)) onCraft(client);
        }));
    }

    private static void onFind(Minecraft mc) {
        ItemStack stack = WarehouseJeiPlugin.hovered().orElse(null);
        if (stack == null || !WarehouseClient.storage().isBound()) return;
        ItemKey key = Fingerprinter.key(stack);
        String name = Fingerprinter.displayName(stack);
        List<ContainerEntry> holders = WarehouseClient.index().holding(key);
        Organizer.Resolution res = WarehouseClient.organizer().resolve(key);
        if (holders.isEmpty() && res == null) {
            Chat.info(name + ": not in the warehouse and no planned home.");
            return;
        }
        int secs = ConfigIO.get().highlightSeconds;
        if (!holders.isEmpty()) {
            ContainerEntry best = holders.get(0);
            WarehouseClient.highlights().containerForItem(best, key, ConfigIO.get().highlightColor, secs, name + " ×" + best.totalCount(key), true, "jei");
            Chat.info(name + ": " + WarehouseClient.index().totalCount(key) + " stored, nearest highlighted at " + best.posString() + (holders.size() > 1 ? " (+" + (holders.size() - 1) + " more chests)" : "") + ".");
        } else {
            WarehouseClient.highlights().containerForItem(res.container(), key, WarehouseClient.organizer().categoryColor(res.category()), secs, "belongs: " + res.category(), true, "jei");
            Chat.info(name + ": none stored; it belongs in " + res.category() + " @ " + res.container().posString() + ".");
        }
        mc.gui.setScreen(null);
    }

    private static void onCraft(Minecraft mc) {
        ItemStack stack = WarehouseJeiPlugin.hovered().orElse(null);
        if (stack == null) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        mc.gui.setScreen(new ChatScreen("/warehouse craft 64 " + id, false));
    }
}
