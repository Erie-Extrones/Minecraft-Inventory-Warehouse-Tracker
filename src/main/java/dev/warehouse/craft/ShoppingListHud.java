package dev.warehouse.craft;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** The pinned shopping list for the active craft plan, top right of the screen. */
public final class ShoppingListHud {
    private record Row(net.minecraft.world.item.ItemStack icon, String text, int color, String sub) {}

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(WarehouseClient.MOD_ID, "shopping_list"), this::render);
    }

    private void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        CraftPlanner planner = WarehouseClient.craftPlanner();
        CraftPlanner.Plan plan = planner != null ? planner.active() : null;
        if (plan == null || !planner.hudVisible() || !ConfigIO.get().shoppingListHud || mc.player == null) return;
        if (mc.gui.screen() instanceof dev.warehouse.ui.SearchScreen) return;
        Font font = mc.font;
        String title = "Craft " + plan.count() + " × " + plan.target().getHoverName().getString();
        List<Row> rows = new ArrayList<>();
        int maxRows = Math.max(4, ConfigIO.get().shoppingListMaxRows);
        for (CraftPlanner.Line l : plan.lines()) {
            if (rows.size() >= maxRows) {
                rows.add(new Row(null, "+" + (plan.lines().size() - maxRows) + " more", 0xFF909090, null));
                break;
            }
            String text;
            int color;
            String sub = null;
            if (l.satisfiedByInventory()) {
                text = l.name() + "  " + l.need() + "/" + l.need() + " ✓";
                color = 0xFF80FF80;
            } else if (l.covered()) {
                text = l.name() + "  " + l.inventory() + "/" + l.need();
                color = 0xFFFFD040;
                ContainerEntry e = l.where().isEmpty() ? null : l.where().get(0);
                if (e != null) sub = "→ " + e.posString() + " ×" + l.perChest().getOrDefault(e.id, 0) + (l.where().size() > 1 ? " +" + (l.where().size() - 1) + " more" : "");
            } else {
                text = l.name() + "  " + l.inventory() + "/" + l.need() + "  missing " + l.missing();
                color = 0xFFFF7070;
                ContainerEntry e = l.where().isEmpty() ? null : l.where().get(0);
                sub = e != null ? "→ " + e.posString() + " ×" + l.perChest().getOrDefault(e.id, 0) + " (warehouse " + l.warehouse() + ")" : "not in the warehouse";
            }
            rows.add(new Row(l.icon(), text, color, sub));
        }
        int pad = 5;
        int w = font.width(title) + 24;
        for (Row r : rows) {
            w = Math.max(w, (r.icon != null ? 20 : 0) + font.width(r.text) + 2 * pad);
            if (r.sub != null) w = Math.max(w, 20 + font.width(r.sub) + 2 * pad);
        }
        int lineH = 0;
        for (Row r : rows) lineH += r.sub != null ? 20 : 12;
        String foot = plan.gathered() ? "Everything on you. Steps: " + plan.steps().size() : plan.complete() ? "/warehouse craft route to gather" : plan.missingLines() + " item(s) missing";
        w = Math.max(w, font.width(foot) + 2 * pad);
        int h = pad + 20 + lineH + 12 + pad;
        int x = g.guiWidth() - w - 6;
        int y = 6;
        g.fill(x, y, x + w, y + h, 0xB0101014);
        g.outline(x, y, w, h, 0x80FFFFFF);
        g.item(plan.target(), x + pad, y + pad);
        g.text(font, title, x + pad + 20, y + pad + 4, 0xFFFFFFFF);
        int yy = y + pad + 20;
        for (Row r : rows) {
            if (r.icon != null) {
                g.item(r.icon, x + pad, yy - 2);
                g.text(font, r.text, x + pad + 20, yy, r.color);
            } else {
                g.text(font, r.text, x + pad, yy, r.color);
            }
            if (r.sub != null) {
                g.text(font, r.sub, x + pad + 20, yy + 10, 0xFFA0A0A0);
                yy += 20;
            } else {
                yy += 12;
            }
        }
        g.text(font, foot, x + pad, y + h - pad - 9, plan.gathered() ? 0xFF80FF80 : plan.complete() ? 0xFFFFD040 : 0xFFFF7070);
    }
}
