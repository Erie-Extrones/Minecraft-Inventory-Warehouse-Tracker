package dev.warehouse.items;

import java.util.ArrayList;
import java.util.List;

/** Custom-item group: one display name, many fingerprints. User can rename/merge and pin a category. */
public final class ItemGroup {
    public String groupId;
    public String displayName;
    public String loreHint;
    public List<ItemKey> members = new ArrayList<>();
    public String categoryOverride;
    public long firstSeenEpochMs;

    public ItemGroup() {}

    public ItemGroup(String groupId, String displayName, String loreHint) {
        this.groupId = groupId;
        this.displayName = displayName;
        this.loreHint = loreHint;
        this.firstSeenEpochMs = System.currentTimeMillis();
    }

    public boolean contains(ItemKey key) {
        return members.contains(key);
    }
}
