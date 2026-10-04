package dev.warehouse.items;

import java.util.ArrayList;
import java.util.List;

/** One observed custom-item variant: full component patch (before noise stripping) plus lore, for offline analysis. */
public final class ItemSample {
    public String componentHash;
    public String itemId;
    public String displayName;
    /** Canonical JSON of the full component patch as received from the server. */
    public String componentsJson;
    /** Plain-text lore lines. */
    public List<String> lore = new ArrayList<>();
    public int count;
    public long firstSeenEpochMs;
    public int timesSeen;

    public ItemSample() {}
}
