package dev.warehouse.controlify;

import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.bind.ControlifyBindApi;
import dev.isxander.controlify.api.bind.InputBindingBuilder;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint;
import dev.isxander.controlify.api.entrypoint.InitContext;
import dev.isxander.controlify.api.entrypoint.PreInitContext;
import dev.isxander.controlify.api.event.ControlifyEvents;
import dev.isxander.controlify.bindings.BindContext;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.screenop.ScreenProcessorProvider;
import dev.warehouse.Keybinds;
import dev.warehouse.ui.ContainerOverlay;
import dev.warehouse.ui.SearchScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlify integration for the Warehouse mod.
 * <ul>
 *   <li>Registers the four warehouse keys as proper controller bindings (own category, radial-menu candidates).</li>
 *   <li>Adds two container-screen bindings: take misplaced, deposit matching.</li>
 *   <li>Makes the Warehouse screen navigable with the d-pad/stick: rows are selectable, tabs switch with the bumpers.</li>
 * </ul>
 */
public final class WarehouseControlify implements ControlifyEntrypoint, ClientModInitializer {
    public static final String MOD_ID = "warehouse_controlify";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    static final Component CATEGORY = Component.translatable("warehouse_controlify.category");

    public static @Nullable InputBindingSupplier openSearch, findHeldItem, clearInventoryMode, toggleOutlines, takeMisplaced, depositMatching;

    @Override
    public void onInitializeClient() {
        // Everything is driven from the Controlify entrypoint below.
    }

    @Override
    public void onControlifyPreInit(PreInitContext ctx) {
        ControlifyBindApi api = ctx.bindings();
        openSearch = inGame(api, "open_search", Keybinds.openSearch);
        findHeldItem = inGame(api, "find_held_item", Keybinds.findHeldItem);
        clearInventoryMode = inGame(api, "clear_inventory_mode", Keybinds.clearInventoryMode);
        toggleOutlines = inGame(api, "toggle_outlines", Keybinds.toggleOutlines);
        takeMisplaced = api.registerBinding(b -> b.id(MOD_ID, "take_misplaced").category(CATEGORY).allowedContexts(BindContext.CONTAINER));
        depositMatching = api.registerBinding(b -> b.id(MOD_ID, "deposit_matching").category(CATEGORY).allowedContexts(BindContext.CONTAINER));

        ScreenProcessorProvider.registerProvider(SearchScreen.class, SearchScreenProcessor::new);
        LOGGER.info("Warehouse Controlify bindings registered");
    }

    /** An in-game binding that presses the matching keyboard mapping, so the mod's own key handling keeps working unchanged. */
    @SuppressWarnings("removal") // radialCandidate(boolean) is what the Identifier overload delegates to as well
    private static InputBindingSupplier inGame(ControlifyBindApi api, String path, @Nullable KeyMapping key) {
        return api.registerBinding(b -> {
            InputBindingBuilder bb = b.id(MOD_ID, path).category(CATEGORY).allowedContexts(BindContext.IN_GAME).radialCandidate(true);
            if (key != null) bb = bb.keyEmulation(key);
            else LOGGER.warn("Warehouse key mapping for {} not registered yet; controller binding will do nothing", path);
            return bb;
        });
    }

    @Override
    public void onControlifyInit(InitContext ctx) {
        SearchScreen.autoFocusQuery = ctx.controlify().currentInputMode() != InputMode.CONTROLLER;
        ControlifyEvents.INPUT_MODE_CHANGED.register(e -> SearchScreen.autoFocusQuery = e.mode() != InputMode.CONTROLLER);
        ControlifyEvents.ACTIVE_CONTROLLER_TICKED.register(e -> onControllerTick(e.controller()));
    }

    @Override
    public void onControllersDiscovered(ControlifyApi controlify) {
    }

    private static void onControllerTick(ControllerEntity controller) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;
        if (takeMisplaced != null && takeMisplaced.on(controller).justPressed()) ContainerOverlay.takeMisplaced(mc);
        if (depositMatching != null && depositMatching.on(controller).justPressed()) ContainerOverlay.depositMatching(mc);
    }
}
