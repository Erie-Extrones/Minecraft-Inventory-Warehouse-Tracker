package dev.warehouse.inventory;

import dev.warehouse.items.ItemKey;
import net.minecraft.world.phys.Vec3;

public final class LostEntry {
    public ItemKey key;
    public int count;
    public String dimension;
    public Vec3 pos;
    public long whenEpochMs;
    /** DROP | GUI_DROP | DEATH */
    public String reason;
    public String displayName;

    public LostEntry() {}

    public LostEntry(ItemKey key, int count, String dimension, Vec3 pos, long whenEpochMs, String reason, String displayName) {
        this.key = key;
        this.count = count;
        this.dimension = dimension;
        this.pos = pos;
        this.whenEpochMs = whenEpochMs;
        this.reason = reason;
        this.displayName = displayName;
    }
}
