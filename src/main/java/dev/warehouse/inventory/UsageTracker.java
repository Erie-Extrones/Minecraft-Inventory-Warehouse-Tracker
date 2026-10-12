package dev.warehouse.inventory;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.storage.JsonStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches what the player actually uses: swinging, using or placing the held item, eating, throwing, tool wear and crafting all
 * count as use of that item kind. From that it knows which stacks have been riding along unused, warns once when the inventory
 * fills up, and (optionally) marks those stacks in inventory screens so they can be put away.
 */
public final class UsageTracker {
    /** One idle stack: inventory slot and what is in it. */
    public record Idle(int slot, ItemStack stack, ItemKey key, long idleMs) {}

    private final JsonStore<UsageData> store;
    private Map<ItemKey, Integer> lastCounts = new HashMap<>();
    private int[] lastDamage = new int[0];
    private ItemKey[] lastKeys = new ItemKey[0];
    private boolean wasFull;
    private long lastAlertMs;
    /** Highlighting stays on from the moment the inventory fills until enough slots are free again. */
    private boolean highlightArmed;
    private List<Idle> idle = List.of();
    private int tick;

    public UsageTracker(JsonStore<UsageData> store) {
        this.store = store;
    }

    public boolean highlightArmed() {
        return highlightArmed && ConfigIO.get().highlightUnusedWhenFull;
    }

    /** Stacks in the main inventory that have not been used for the configured time, essentials excluded. Refreshed every second. */
    public List<Idle> idle() {
        return idle;
    }

    public boolean isIdle(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemKey key = Fingerprinter.key(stack);
        for (Idle i : idle) if (i.key().equals(key)) return true;
        return false;
    }

    public @org.jspecify.annotations.Nullable Long lastUsed(ItemKey key) {
        return store.get().lastUsed.get(key.asString());
    }

    public void markUsed(ItemStack stack) {
        if (stack.isEmpty()) return;
        markUsed(Fingerprinter.key(stack));
    }

    private void markUsed(ItemKey key) {
        store.get().lastUsed.put(key.asString(), System.currentTimeMillis());
        store.markDirty();
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !store.isBound()) {
            lastCounts = new HashMap<>();
            lastKeys = new ItemKey[0];
            return;
        }
        ModConfig cfg = ConfigIO.get();
        Inventory inv = mc.player.getInventory();
        int size = inv.getContainerSize();
        if (lastKeys.length != size) {
            lastKeys = new ItemKey[size];
            lastDamage = new int[size];
        }
        Screen screen = mc.gui.screen();
        boolean crafting = screen instanceof CraftingScreen || screen instanceof InventoryScreen;
        long now = System.currentTimeMillis();
        UsageData data = store.get();

        // 1. Active use of what is in hand.
        if (screen == null) {
            ItemStack main = mc.player.getMainHandItem();
            ItemStack off = mc.player.getOffhandItem();
            boolean acting = mc.player.isSwinging() || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
            if (mc.player.isUsingItem()) {
                markUsed(mc.player.getUsedItemHand() == InteractionHand.OFF_HAND ? off : main);
            } else if (acting) {
                if (!main.isEmpty()) markUsed(main);
                else if (mc.options.keyUse.isDown() && !off.isEmpty()) markUsed(off);
            }
        }

        // 2. Changes between ticks: a stack shrinking (placed, eaten, thrown, crafted) or a tool wearing counts as use.
        Map<ItemKey, Integer> counts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            ItemKey key = s.isEmpty() ? null : Fingerprinter.key(s);
            if (key != null) {
                counts.merge(key, s.getCount(), Integer::sum);
                int dmg = s.isDamageableItem() ? s.getDamageValue() : 0;
                if (lastKeys[i] != null && lastKeys[i].equals(key) && dmg > lastDamage[i]) markUsed(key);
                lastDamage[i] = dmg;
            }
            lastKeys[i] = key;
        }
        // A death or dimension change empties the inventory wholesale; that is not use.
        if ((screen == null || crafting) && !counts.isEmpty() && !mc.player.isDeadOrDying()) {
            for (Map.Entry<ItemKey, Integer> en : lastCounts.entrySet()) {
                int after = counts.getOrDefault(en.getKey(), 0);
                if (after < en.getValue() && !DropDetector.wasRecentlyDropped(en.getKey())) markUsed(en.getKey());
            }
        }
        // Items that just arrived start their idle clock now; items that left forget it.
        for (ItemKey k : counts.keySet()) if (data.carriedSince.putIfAbsent(k.asString(), now) == null) store.markDirty();
        if (!lastCounts.isEmpty()) {
            for (ItemKey k : lastCounts.keySet()) if (!counts.containsKey(k) && data.carriedSince.remove(k.asString()) != null) store.markDirty();
        } else if (data.carriedSince.size() > counts.size()) {
            data.carriedSince.keySet().retainAll(counts.keySet().stream().map(ItemKey::asString).toList());
            store.markDirty();
        }
        lastCounts = counts;

        // 3. Every second: which stacks are idle, and whether the inventory just filled up.
        if (++tick % 20 != 0) return;
        idle = computeIdle(mc, cfg, now);
        int free = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (inv.getItem(i).isEmpty()) free++;
        boolean full = free == 0;
        if (full) highlightArmed = true;
        else if (free >= cfg.unusedHighlightClearAtFreeSlots) highlightArmed = false;
        if (screen != null) return; // still sorting things in a screen: judge once it closes
        if (full && !wasFull && cfg.inventoryFullAlert && now - lastAlertMs > cfg.inventoryFullAlertCooldownMinutes * 60_000L) {
            lastAlertMs = now;
            announce(cfg);
        }
        wasFull = full;
    }

    private void announce(ModConfig cfg) {
        if (idle.isEmpty()) {
            Chat.warn("Inventory full. Everything in it has been used in the last " + cfg.unusedAfterMinutes + " minutes.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Idle i : idle) {
            if (shown++ >= 4) {
                sb.append(", +").append(idle.size() - 4).append(" more");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(Fingerprinter.displayName(i.stack())).append(" ×").append(i.stack().getCount());
        }
        Chat.warn("Inventory full. " + idle.size() + " stack(s) unused for " + cfg.unusedAfterMinutes + "+ minutes: " + sb
                + ". " + (cfg.highlightUnusedWhenFull ? "They are marked in your inventory; c" : "C") + "lear inventory mode puts them away.");
    }

    private List<Idle> computeIdle(Minecraft mc, ModConfig cfg, long now) {
        List<Idle> out = new ArrayList<>();
        Inventory inv = mc.player.getInventory();
        UsageData data = store.get();
        long limit = Math.max(1, cfg.unusedAfterMinutes) * 60_000L;
        var mode = WarehouseClient.clearMode();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || mode.isEssential(mc, s, i)) continue;
            ItemKey key = Fingerprinter.key(s);
            long since = Math.max(data.lastUsed.getOrDefault(key.asString(), 0L), data.carriedSince.getOrDefault(key.asString(), now));
            long idleFor = now - since;
            if (idleFor >= limit) out.add(new Idle(i, s, key, idleFor));
        }
        out.sort((a, b) -> Long.compare(b.idleMs(), a.idleMs()));
        return out;
    }

    public static String describe(long ms) {
        long m = ms / 60_000L;
        if (m < 60) return m + " min";
        long h = m / 60;
        return h < 48 ? h + " h " + (m % 60) + " min" : (h / 24) + " days";
    }
}
