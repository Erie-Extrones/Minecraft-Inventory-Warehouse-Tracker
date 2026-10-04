package dev.warehouse.organizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Organizer output. A plan is "accepted" once {@link #accepted} is true; a pending plan is shown but not used for routing. */
public final class Plan {
    public long createdEpochMs;
    public boolean accepted;
    /** Warehouse region the plan was built for (null = all warehouse regions). */
    public UUID regionId;
    /** Category -> zone. */
    public Map<String, Zone> zones = new LinkedHashMap<>();
    /** ItemKey string -> container id. User overrides. */
    public Map<String, UUID> explicitOverrides = new LinkedHashMap<>();
    /** itemId or groupId -> category. Per-server overrides. */
    public Map<String, String> categoryOverrides = new LinkedHashMap<>();
    /** Chest count used when planning (discovered or manual). */
    public int chestBudget;
    /** Manual chest count override (0 = use discovery). */
    public int manualChestCount;
    /** Entrance point used for ordering. */
    public int entranceX, entranceY, entranceZ;

    public static Plan empty() {
        return new Plan();
    }

    public boolean exists() {
        return createdEpochMs > 0 && !zones.isEmpty();
    }

    public boolean isActive() {
        return exists() && accepted;
    }

    public String zoneOf(UUID containerId) {
        for (Zone z : zones.values()) if (z.containerIds.contains(containerId)) return z.category;
        return null;
    }
}
