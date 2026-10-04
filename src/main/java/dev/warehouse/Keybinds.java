package dev.warehouse;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class Keybinds {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(WarehouseClient.MOD_ID, "warehouse"));

    public static KeyMapping toggleOutlines;
    public static KeyMapping openSearch;
    public static KeyMapping clearInventoryMode;

    private Keybinds() {}

    public static void register() {
        toggleOutlines = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.warehouse.toggle_outlines", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, CATEGORY));
        openSearch = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.warehouse.open_search", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
        clearInventoryMode = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.warehouse.clear_inventory_mode", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
    }
}
