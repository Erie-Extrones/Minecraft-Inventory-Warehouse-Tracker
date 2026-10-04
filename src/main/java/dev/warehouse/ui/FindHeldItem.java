package dev.warehouse.ui;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.region.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Highlights where the held item is stored (and where it belongs), from a command or keybind. */
public final class FindHeldItem {
    private FindHeldItem() {}

    public static void run(Minecraft mc, boolean all) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            Chat.error("Hold the item you want to find.");
            return;
        }
        ItemKey key = Fingerprinter.key(held);
        String name = Fingerprinter.displayName(held);
        List<ContainerEntry> holders = WarehouseClient.index().holding(key);
        Organizer.Resolution dest = WarehouseClient.organizer().resolve(key);
        var highlights = WarehouseClient.highlights();
        highlights.clearGroup("find");

        if (holders.isEmpty() && dest == null) {
            Chat.warn(name + " is not stored anywhere I have seen" + (WarehouseClient.organizer().plan().isActive() ? " and its zone has no room." : "."));
            return;
        }
        int shown = 0;
        int total = WarehouseClient.index().totalCount(key);
        for (ContainerEntry e : holders) {
            highlights.container(e, highlights.nextColor(), dev.warehouse.config.ConfigIO.get().highlightSeconds, name + " ×" + e.totalCount(key), shown == 0, "find");
            shown++;
            if (!all) break;
        }
        if (dest != null && (holders.isEmpty() || !dest.container().id.equals(holders.get(0).id))) {
            highlights.container(dest.container(), WarehouseClient.organizer().categoryColor(dest.category()), dev.warehouse.config.ConfigIO.get().highlightSeconds, "Belongs: " + dest.category(), holders.isEmpty(), "find");
        }
        if (!holders.isEmpty()) {
            ContainerEntry best = holders.get(0);
            Region r = WarehouseClient.regions().byId(best.regionId);
            Chat.info(name + ": " + total + " in " + holders.size() + " container(s). Nearest match highlighted at " + (r != null ? r.name + " " : "") + best.posString()
                    + (all || holders.size() == 1 ? "" : " (use /warehouse find all for every location)") + ".");
        } else {
            Chat.info(name + " is not stored yet; highlighted where it belongs (" + dest.category() + " @ " + dest.container().posString() + ").");
        }
    }
}
