package dev.warehouse.index;

import dev.warehouse.items.ItemKey;

import java.util.List;

/** One stack as last seen inside a container. Shulker boxes carry their nested contents. */
public final class StackRecord {
    public ItemKey key;
    public int count;
    public int slot;
    public List<StackRecord> nested;
    /** Display name at capture time, so the UI can show it without resolving the key. */
    public String displayName;

    public StackRecord() {}

    public StackRecord(ItemKey key, int count, int slot, List<StackRecord> nested, String displayName) {
        this.key = key;
        this.count = count;
        this.slot = slot;
        this.nested = nested;
        this.displayName = displayName;
    }
}
