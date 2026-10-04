package dev.warehouse.organizer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Zone {
    public String category;
    public List<UUID> containerIds = new ArrayList<>();
    /** Sub-family -> home chest inside this zone, so related items cluster instead of funnelling into one chest. */
    public java.util.Map<String, UUID> familyHomes = new java.util.LinkedHashMap<>();

    public Zone() {}

    public Zone(String category) {
        this.category = category;
    }
}
