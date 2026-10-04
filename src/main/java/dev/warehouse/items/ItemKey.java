package dev.warehouse.items;

import java.util.Objects;

/** Item identity: item id + stable hash of the (noise-stripped) component patch. Vanilla default items have an empty hash. */
public final class ItemKey {
    public String itemId;
    public String componentHash;

    public ItemKey() {}

    public ItemKey(String itemId, String componentHash) {
        this.itemId = itemId;
        this.componentHash = componentHash == null ? "" : componentHash;
    }

    public boolean isVanilla() {
        return componentHash == null || componentHash.isEmpty();
    }

    /** Stable string form used as map key: "minecraft:stone" or "minecraft:stone|abcdef". */
    public String asString() {
        return isVanilla() ? itemId : itemId + "|" + componentHash;
    }

    public static ItemKey parse(String s) {
        int i = s.indexOf('|');
        return i < 0 ? new ItemKey(s, "") : new ItemKey(s.substring(0, i), s.substring(i + 1));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemKey k)) return false;
        return itemId.equals(k.itemId) && Objects.equals(hashOrEmpty(), k.hashOrEmpty());
    }

    private String hashOrEmpty() {
        return componentHash == null ? "" : componentHash;
    }

    @Override
    public int hashCode() {
        return itemId.hashCode() * 31 + hashOrEmpty().hashCode();
    }

    @Override
    public String toString() {
        return asString();
    }
}
