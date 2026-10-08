package dev.warehouse.controlify;

import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.bind.InputBinding;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.warehouse.ui.SearchScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Controller handling for the Warehouse screen.
 * <p>
 * The result list is not made of widgets, so Controlify's default navigation cannot reach it. This processor keeps a
 * "list focus": d-pad/stick down from the bottom row of controls enters the list, up/down move the selection (with
 * hold-to-repeat), up from the first row returns to the controls. In the list: press selects the row, abstract action 1
 * does the shift-click variant, abstract action 2 opens the on-screen keyboard on the search box, bumpers switch tabs.
 */
public final class SearchScreenProcessor extends ScreenProcessor<SearchScreen> {

    public SearchScreenProcessor(SearchScreen screen) {
        super(screen);
        if (controllerMode()) enterList();
    }

    private static boolean controllerMode() {
        return ControlifyApi.get().currentInputMode() == InputMode.CONTROLLER;
    }

    private void enterList() {
        if (screen.rowCount() > 0) screen.setListFocused(true);
    }

    @Override
    public void onWidgetRebuild() {
        super.onWidgetRebuild();
        if (controllerMode() && screen.listFocused()) screen.setListFocused(true);
    }

    @Override
    public void onInputModeChanged(InputMode mode) {
        super.onInputModeChanged(mode);
        if (mode == InputMode.CONTROLLER) enterList();
        else screen.setListFocused(false);
    }

    @Override
    protected void handleComponentNavigation(ControllerEntity controller) {
        InputBinding up = ControlifyBindings.GUI_NAVI_UP.on(controller);
        InputBinding down = ControlifyBindings.GUI_NAVI_DOWN.on(controller);
        InputBinding left = ControlifyBindings.GUI_NAVI_LEFT.on(controller);
        InputBinding right = ControlifyBindings.GUI_NAVI_RIGHT.on(controller);

        if (screen.listFocused()) {
            if (holdRepeatHelper.shouldAction(down)) {
                if (screen.moveSelection(1)) playFocusChangeSound();
                holdRepeatHelper.onNavigate();
            } else if (holdRepeatHelper.shouldAction(up)) {
                if (screen.moveSelection(-1)) {
                    playFocusChangeSound();
                } else {
                    leaveListUpwards();
                }
                holdRepeatHelper.onNavigate();
            } else if (left.justPressed()) {
                screen.switchTabBy(-1);
                playFocusChangeSound();
            } else if (right.justPressed()) {
                screen.switchTabBy(1);
                playFocusChangeSound();
            }
            return;
        }

        GuiEventListener before = screen.getFocused();
        super.handleComponentNavigation(controller);
        // Down with nothing below the focused control: step into the result list.
        if (down.justPressed() && screen.getFocused() == before && screen.rowCount() > 0) {
            screen.setListFocused(true);
            playFocusChangeSound();
        }
    }

    private void leaveListUpwards() {
        screen.setListFocused(false);
        screen.clearFocus();
        // From no focus, an upward arrow navigation lands on the control nearest the bottom edge: the row above the list.
        createScreenNavigationFunc(ScreenDirection.UP).get();
        playFocusChangeSound();
    }

    @Override
    protected void handleButtons(ControllerEntity controller) {
        if (ControlifyBindings.GUI_ABSTRACT_ACTION_2.on(controller).justPressed()) {
            screen.focusQuery();
            tryOpenKeyboard(controller, screen.queryBox());
            return;
        }
        if (screen.listFocused()) {
            if (ControlifyBindings.GUI_PRESS.on(controller).guiPressed().get()) {
                playClackSound();
                screen.activateSelected(false);
            } else if (ControlifyBindings.GUI_ABSTRACT_ACTION_1.on(controller).justPressed()) {
                playClackSound();
                screen.activateSelected(true);
            } else if (ControlifyBindings.GUI_BACK.on(controller).guiPressed().get()) {
                playClackSound();
                screen.onClose();
            }
            return;
        }
        super.handleButtons(controller);
    }

    @Override
    protected void handleTabNavigation(ControllerEntity controller) {
        if (ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed()) {
            screen.switchTabBy(1);
            playFocusChangeSound();
        } else if (ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed()) {
            screen.switchTabBy(-1);
            playFocusChangeSound();
        }
    }

    @Override
    public void render(ControllerEntity controller, GuiGraphicsExtractor g, float delta) {
        super.render(controller, g, delta);
        if (!controllerMode()) return;
        Font font = screen.getFont();
        MutableComponent legend = Component.empty();
        if (screen.listFocused()) {
            add(legend, controller, ControlifyBindings.GUI_PRESS, screen.tab() == SearchScreen.Tab.PLAN ? "Highlight zone" : "Highlight");
            add(legend, controller, ControlifyBindings.GUI_ABSTRACT_ACTION_1, "All locations");
        } else {
            add(legend, controller, ControlifyBindings.GUI_NAVI_DOWN, "List");
        }
        add(legend, controller, ControlifyBindings.GUI_ABSTRACT_ACTION_2, "Type");
        add(legend, controller, ControlifyBindings.GUI_PREV_TAB, "");
        add(legend, controller, ControlifyBindings.GUI_NEXT_TAB, "Tabs");
        add(legend, controller, ControlifyBindings.GUI_BACK, "Close");
        int w = font.width(legend);
        int x = screen.width - 20 - w;
        int y = screen.height - 16;
        g.fill(x - 4, y - 3, screen.width - 16, y + 11, 0xA0000000);
        g.text(font, legend, x, y, 0xFFE0E0E0);
    }

    private static void add(MutableComponent legend, ControllerEntity controller, InputBindingSupplier binding, String label) {
        InputBinding b = binding.onOrNull(controller);
        if (b == null || b.isUnbound()) return;
        if (!legend.getSiblings().isEmpty() && !label.isEmpty()) legend.append(Component.literal("   "));
        legend.append(b.inputGlyph());
        if (!label.isEmpty()) legend.append(Component.literal(" " + label));
    }
}
