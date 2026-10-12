package dev.warehouse.inventory;

import java.util.HashMap;
import java.util.Map;

/** Persisted per server: when each item kind was last used, and since when it has been sitting in the inventory. */
public final class UsageData {
    /** ItemKey string -> epoch ms of the last use (placed, consumed, swung, crafted with, damaged...). */
    public Map<String, Long> lastUsed = new HashMap<>();
    /** ItemKey string -> epoch ms since which the item has been in the inventory without leaving it. */
    public Map<String, Long> carriedSince = new HashMap<>();
}
