package dev.warehouse.shops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A player-run shop: a Shop region plus its own price list, restock thresholds and inventory history. */
public final class Shop {
    /** Stock levels at one inventory check. Keys are item key strings. */
    public static final class Snapshot {
        public long whenEpochMs;
        public Map<String, Integer> counts = new LinkedHashMap<>();
        public Map<String, String> names = new LinkedHashMap<>();
        public int containers;
        public double value;
    }

    public UUID regionId;
    /** Price key (PriceKeys) -> unit price customers pay you. */
    public Map<String, Double> sellPrices = new LinkedHashMap<>();
    /** Price key -> restock threshold override. */
    public Map<String, Integer> restockMin = new LinkedHashMap<>();
    public long lastInventoryEpochMs;
    public List<Snapshot> history = new ArrayList<>();

    public Shop() {}

    public Shop(UUID regionId) {
        this.regionId = regionId;
    }
}
