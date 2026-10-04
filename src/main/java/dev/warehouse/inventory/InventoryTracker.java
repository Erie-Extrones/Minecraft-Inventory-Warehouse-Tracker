package dev.warehouse.inventory;

import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a per-tick snapshot of the player inventory (36 + armor + offhand). Gains need no reconciliation;
 * losses explained by drops/deposits are handled elsewhere; unexplained losses are counted for diagnostics only.
 */
public final class InventoryTracker {
    private List<ItemStack> last = new ArrayList<>();
    private Map<ItemKey, Integer> lastCounts = new HashMap<>();
    private int unexplainedLossEvents;
    private boolean containerOpen;

    public List<ItemStack> lastSnapshot() {
        return last;
    }

    public int unexplainedLossEvents() {
        return unexplainedLossEvents;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null) {
            last = new ArrayList<>();
            lastCounts = new HashMap<>();
            return;
        }
        Inventory inv = mc.player.getInventory();
        List<ItemStack> now = new ArrayList<>(inv.getContainerSize());
        Map<ItemKey, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i).copy();
            now.add(s);
            if (!s.isEmpty()) counts.merge(Fingerprinter.key(s), s.getCount(), Integer::sum);
        }
        boolean screenOpen = mc.gui.screen() != null;
        if (!screenOpen && !containerOpen && !lastCounts.isEmpty()) {
            for (Map.Entry<ItemKey, Integer> en : lastCounts.entrySet()) {
                int after = counts.getOrDefault(en.getKey(), 0);
                if (after < en.getValue() && !DropDetector.wasRecentlyDropped(en.getKey())) {
                    // Consumption, placement, crafting... all legitimate; just count for /warehouse debug.
                    unexplainedLossEvents++;
                    break;
                }
            }
        }
        containerOpen = screenOpen;
        last = now;
        lastCounts = counts;
    }
}
