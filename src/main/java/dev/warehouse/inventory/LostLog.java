package dev.warehouse.inventory;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.storage.JsonStore;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Persisted lost-item log with expiry. */
public final class LostLog {
    public static final String DROP = "DROP";
    public static final String GUI_DROP = "GUI_DROP";
    public static final String DEATH = "DEATH";

    private final JsonStore<List<LostEntry>> store;

    public LostLog(JsonStore<List<LostEntry>> store) {
        this.store = store;
    }

    public List<LostEntry> entries() {
        return store.get();
    }

    public void add(ItemStack stack, int count, String dimension, Vec3 pos, String reason) {
        if (stack.isEmpty() || count <= 0) return;
        ItemKey key = Fingerprinter.key(stack);
        store.get().add(0, new LostEntry(key, count, dimension, pos, System.currentTimeMillis(), reason, Fingerprinter.displayName(stack)));
        store.markDirty();
    }

    public void remove(LostEntry e) {
        if (store.get().remove(e)) store.markDirty();
    }

    public void clear() {
        store.get().clear();
        store.markDirty();
    }

    /** Drop expired entries. Call once a second or so. */
    public void expire() {
        long now = System.currentTimeMillis();
        long normal = ConfigIO.get().lostExpiryMinutes * 60_000L;
        long death = ConfigIO.get().deathLostExpiryMinutes * 60_000L;
        boolean removed = store.get().removeIf(e -> now - e.whenEpochMs > (DEATH.equals(e.reason) ? death : normal));
        if (removed) store.markDirty();
    }
}
