package dev.warehouse.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.craft.CraftPlanner;
import dev.warehouse.index.ContainerEntry;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /warehouse craft <count> [item] | show | route | clear | hud on|off */
public final class CraftCommands {
    private CraftCommands() {}

    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext ctx) {
        root.then(literal("craft")
                .then(literal("show").executes(c -> show()))
                .then(literal("clear").executes(c -> {
                    WarehouseClient.craftPlanner().clear();
                    Chat.info("Craft plan cleared.");
                    return 1;
                }))
                .then(literal("route").executes(c -> route(c.getSource().getClient())))
                .then(literal("hud")
                        .then(literal("on").executes(c -> hud(true)))
                        .then(literal("off").executes(c -> hud(false))))
                .then(argument("count", IntegerArgumentType.integer(1, 100000))
                        .executes(c -> plan(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "count"), null))
                        .then(argument("item", ItemArgument.item(ctx)).executes(c -> plan(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "count"), ItemArgument.getItem(c, "item").createItemStack(1))))));
    }

    private static int plan(Minecraft mc, int count, ItemStack item) {
        if (item == null) {
            item = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
            if (item.isEmpty()) {
                Chat.error("Hold the item to craft, or name it: /warehouse craft <count> <item>.");
                return 0;
            }
        }
        return WarehouseClient.craftPlanner().start(mc, item, count) ? 1 : 0;
    }

    private static int show() {
        CraftPlanner.Plan p = WarehouseClient.craftPlanner().active();
        if (p == null) {
            Chat.info("No craft plan. /warehouse craft <count> [item].");
            return 1;
        }
        Chat.info("Crafting " + p.count() + " × " + p.target().getHoverName().getString() + ":");
        for (CraftPlanner.Line l : p.lines()) {
            String where = l.where().isEmpty() ? "" : "  @ " + l.where().get(0).posString() + (l.where().size() > 1 ? " +" + (l.where().size() - 1) : "");
            ChatFormatting f = l.satisfiedByInventory() ? ChatFormatting.GREEN : l.covered() ? ChatFormatting.YELLOW : ChatFormatting.RED;
            Chat.send(Component.literal("  " + l.name() + ": need " + l.need() + ", on you " + l.inventory() + ", warehouse " + l.warehouse() + (l.missing() > 0 ? ", MISSING " + l.missing() : "") + where).withStyle(f));
        }
        for (CraftPlanner.Step s : p.steps()) Chat.send(Component.literal("  " + s.kind() + " " + s.crafts() + "× → " + s.makes() + " " + s.name()).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int route(Minecraft mc) {
        CraftPlanner planner = WarehouseClient.craftPlanner();
        if (planner.active() == null) {
            Chat.error("No craft plan to gather for.");
            return 0;
        }
        var fetch = planner.toFetch();
        if (fetch.isEmpty()) {
            Chat.info(planner.active().gathered() ? "You already carry everything." : "Nothing to gather: the warehouse has none of what is missing.");
            return 1;
        }
        WarehouseClient.clearMode().startFetch(mc, fetch, planner.fetchNames(), "Gather for " + planner.active().target().getHoverName().getString());
        return 1;
    }

    private static int hud(boolean on) {
        WarehouseClient.craftPlanner().setHudVisible(on);
        Chat.info("Shopping list " + (on ? "shown" : "hidden") + ".");
        return 1;
    }
}
