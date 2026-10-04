package dev.warehouse.organizer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Zone {
    public String category;
    public List<UUID> containerIds = new ArrayList<>();

    public Zone() {}

    public Zone(String category) {
        this.category = category;
    }
}
