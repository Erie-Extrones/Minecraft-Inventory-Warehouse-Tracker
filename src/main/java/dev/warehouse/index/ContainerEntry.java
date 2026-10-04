package dev.warehouse.index;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A known container (block or entity) and its last-seen contents. */
public final class ContainerEntry {
    public UUID id;
    public ContainerKind kind;
    public String dimension;
    public BlockPos pos;
    public BlockPos secondaryPos;
    public UUID entityId;
    public List<StackRecord> contents = new ArrayList<>();
    public long lastSeenEpochMs;
    public boolean manualPin;
    public UUID regionId;
    /** Total slots of the container as last seen (27, 54, ...). 0 if unknown. */
    public int slotCount;
    /** Custom name of the container if it had one. */
    public String customName;

    public ContainerEntry() {}

    public ContainerEntry(UUID id, ContainerKind kind, String dimension, BlockPos pos) {
        this.id = id;
        this.kind = kind;
        this.dimension = dimension;
        this.pos = pos;
    }

    public boolean coversPos(String dim, BlockPos p) {
        if (!dim.equals(dimension)) return false;
        return p.equals(pos) || (secondaryPos != null && p.equals(secondaryPos));
    }

    public int totalCount(dev.warehouse.items.ItemKey key) {
        int n = 0;
        for (StackRecord s : contents) {
            if (key.equals(s.key)) n += s.count;
            if (s.nested != null) for (StackRecord x : s.nested) if (key.equals(x.key)) n += x.count;
        }
        return n;
    }

    public int usedSlots() {
        return contents.size();
    }

    public int freeSlots() {
        return slotCount > 0 ? Math.max(0, slotCount - contents.size()) : 0;
    }

    public String posString() {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public String label() {
        return (customName != null && !customName.isBlank() ? customName : kind.label()) + " @ " + posString();
    }
}
