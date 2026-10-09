package dev.warehouse.prices;

/** One price seen at a shop. {@code sell} is from the player's point of view: true = what you receive, false = what you pay. */
public final class PriceObservation {
    /** "item:minecraft:stone", "group:vote_key", "key:<itemId>|<hash>" or "name:<lower-case name>" when the item could not be resolved. */
    public String priceKey;
    public String itemName;
    public double unitPrice;
    public int quantity = 1;
    public boolean sell;
    public String shop;
    /** chat, sign or manual. */
    public String source;
    public long whenEpochMs;

    public PriceObservation() {}

    public PriceObservation(String priceKey, String itemName, double unitPrice, int quantity, boolean sell, String shop, String source) {
        this.priceKey = priceKey;
        this.itemName = itemName;
        this.unitPrice = unitPrice;
        this.quantity = Math.max(1, quantity);
        this.sell = sell;
        this.shop = shop;
        this.source = source;
        this.whenEpochMs = System.currentTimeMillis();
    }
}
