package dev.warehouse.index;

public enum ContainerKind {
    CHEST,
    DOUBLE_CHEST,
    BARREL,
    SHULKER_BOX,
    ENDER_CHEST,
    ARMOR_STAND,
    ITEM_FRAME,
    OTHER;

    public boolean isEntity() {
        return this == ARMOR_STAND || this == ITEM_FRAME;
    }

    public boolean isPlannable() {
        return this == CHEST || this == DOUBLE_CHEST || this == BARREL || this == SHULKER_BOX;
    }

    public String label() {
        return switch (this) {
            case CHEST -> "Chest";
            case DOUBLE_CHEST -> "Double chest";
            case BARREL -> "Barrel";
            case SHULKER_BOX -> "Shulker box";
            case ENDER_CHEST -> "Ender chest";
            case ARMOR_STAND -> "Armor stand";
            case ITEM_FRAME -> "Item frame";
            case OTHER -> "Container";
        };
    }
}
