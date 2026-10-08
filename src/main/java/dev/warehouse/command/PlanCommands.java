package dev.warehouse.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerResolver;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.ui.FindHeldItem;
import dev.warehouse.ui.SearchScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /warehouse plan ..., /warehouse search, /warehouse misplaced, /warehouse lost, /warehouse clearmode */
public final class PlanCommands {
    private PlanCommands() {}

    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        root.then(literal("plan")
                .then(literal("run").executes(c -> {
                    WarehouseClient.organizer().run(c.getSource().getClient(), false);
                    return 1;
                }))
                .then(literal("replan").executes(c -> {
                    WarehouseClient.organizer().run(c.getSource().getClient(), true);
                    return 1;
                }))
                .then(literal("accept").executes(c -> {
                    if (WarehouseClient.organizer().accept()) {
                        Chat.info("Plan accepted. Hover items to see where they belong; Misplaced tab lists wrong-chest items.");
                        return 1;
                    }
                    Chat.error("No pending plan. Run /warehouse plan run first.");
                    return 0;
                }))
                .then(literal("reject").executes(c -> {
                    WarehouseClient.organizer().reject();
                    Chat.info("Pending plan discarded.");
                    return 1;
                }))
                .then(literal("show").executes(c -> show()))
                .then(literal("clear").executes(c -> {
                    WarehouseClient.organizer().clearPlan();
                    Chat.info("Plan cleared.");
                    return 1;
                }))
                .then(literal("count")
                        .then(argument("n", IntegerArgumentType.integer(0, 10000)).executes(c -> {
                            int n = IntegerArgumentType.getInteger(c, "n");
                            WarehouseClient.organizer().setManualCount(n);
                            Chat.info("Manual chest count: " + (n == 0 ? "auto (discovery)" : n) + ". Takes effect on the next run/replan.");
                            return 1;
                        })))
                .then(literal("preview")
                        .executes(c -> preview(!WarehouseClient.organizer().previewEnabled()))
                        .then(literal("on").executes(c -> preview(true)))
                        .then(literal("off").executes(c -> preview(false))))
                .then(literal("override")
                        .then(literal("here").executes(c -> overrideHere(c.getSource().getClient())))
                        .then(literal("clear").executes(c -> {
                            Minecraft mc = c.getSource().getClient();
                            if (mc.player == null) return 0;
                            ItemKey key = Fingerprinter.key(mc.player.getMainHandItem());
                            WarehouseClient.organizer().setOverride(key, null);
                            Chat.info("Override removed for " + key.asString() + ".");
                            return 1;
                        })))
                .then(literal("category")
                        .then(argument("itemOrGroup", StringArgumentType.string())
                                .then(argument("category", StringArgumentType.greedyString()).executes(c -> {
                                    String id = StringArgumentType.getString(c, "itemOrGroup");
                                    String cat = StringArgumentType.getString(c, "category").trim();
                                    if (cat.equalsIgnoreCase("auto") || cat.equalsIgnoreCase("none")) cat = null;
                                    WarehouseClient.organizer().setCategoryOverride(id, cat);
                                    Chat.info("Category for " + id + ": " + (cat == null ? "auto" : cat) + ".");
                                    return 1;
                                })))));

        root.then(literal("search").executes(c -> {
            Minecraft mc = c.getSource().getClient();
            mc.schedule(() -> SearchScreen.open(mc, SearchScreen.Tab.SEARCH));
            return 1;
        }).then(argument("query", StringArgumentType.greedyString()).executes(c -> {
            Minecraft mc = c.getSource().getClient();
            String q = StringArgumentType.getString(c, "query");
            mc.schedule(() -> {
                SearchScreen.open(mc, SearchScreen.Tab.SEARCH);
                SearchScreen.setQuery(q);
            });
            return 1;
        })));
        root.then(literal("misplaced").executes(c -> openTab(c.getSource().getClient(), SearchScreen.Tab.MISPLACED)));
        root.then(literal("condense").executes(c -> openTab(c.getSource().getClient(), SearchScreen.Tab.CONDENSE))
                .then(literal("list").executes(c -> condenseList(c.getSource().getClient()))));
        root.then(literal("lost").executes(c -> openTab(c.getSource().getClient(), SearchScreen.Tab.LOST)));
        root.then(literal("unsorted").executes(c -> openTab(c.getSource().getClient(), SearchScreen.Tab.UNSORTED)));
        root.then(literal("clearmode").executes(c -> {
            WarehouseClient.clearMode().toggle(c.getSource().getClient(), dev.warehouse.modes.ClearInventoryMode.Kind.CLEAR);
            return 1;
        }));
        root.then(literal("sort").executes(c -> {
            WarehouseClient.clearMode().toggle(c.getSource().getClient(), dev.warehouse.modes.ClearInventoryMode.Kind.SORT);
            return 1;
        }));
        root.then(literal("forget")
                .then(literal("here").executes(c -> forgetHere(c.getSource().getClient()))));
        root.then(literal("find")
                .executes(c -> {
                    FindHeldItem.run(c.getSource().getClient(), false);
                    return 1;
                })
                .then(literal("all").executes(c -> {
                    FindHeldItem.run(c.getSource().getClient(), true);
                    return 1;
                })));
        root.then(literal("guide")
                .executes(c -> guide(!dev.warehouse.config.ConfigIO.get().heldItemGuide))
                .then(literal("on").executes(c -> guide(true)))
                .then(literal("off").executes(c -> guide(false))));
        root.then(literal("highlight")
                .then(literal("clear").executes(c -> {
                    WarehouseClient.highlights().clear();
                    return 1;
                })));
    }

    private static int guide(boolean on) {
        dev.warehouse.config.ConfigIO.get().heldItemGuide = on;
        dev.warehouse.config.ConfigIO.save();
        Chat.info("Held-item guide " + (on ? "on" : "off") + ".");
        return 1;
    }

    private static int condenseList(Minecraft mc) {
        var condenser = WarehouseClient.condenser();
        var reports = condenser.reports();
        if (reports.isEmpty()) {
            Chat.info("Nothing worth packing into shulkers right now.");
            return 1;
        }
        for (var r : reports) {
            Chat.send(Chat.prefix().append(Component.literal(r.zone() + "  " + r.percent() + "% full" + (r.lowOnSpace() ? "  (low on space)" : ""))
                    .withStyle(r.lowOnSpace() ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
            int shown = 0;
            for (var s : r.suggestions()) {
                if (shown++ >= 5) {
                    Chat.send(Component.literal("  ... " + (r.suggestions().size() - 5) + " more in /warehouse condense").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                Chat.send(Component.literal("  " + s.displayName() + "  ×" + s.total() + "  " + s.slotsNow() + " slots → " + s.shulkersNeeded() + " shulker" + (s.shulkersNeeded() == 1 ? "" : "s")
                        + ", frees " + s.slotsFreed() + (s.putBack() != null ? "; put it back in " + s.putBack().label() : "")).withStyle(ChatFormatting.GRAY));
            }
        }
        int carried = condenser.emptyShulkersInInventory(mc);
        var stored = condenser.emptyShulkersIndexed();
        Chat.send(Component.literal("  Empty shulkers: " + carried + " on you, " + stored.count() + " indexed" + (stored.where() != null && stored.count() > 0 ? " (most in " + stored.where().label() + ")" : "")).withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int openTab(Minecraft mc, SearchScreen.Tab t) {
        mc.schedule(() -> SearchScreen.open(mc, t));
        return 1;
    }

    private static int preview(boolean on) {
        WarehouseClient.organizer().setPreview(on);
        Chat.info("Plan preview " + (on ? "on" : "off") + ".");
        return 1;
    }

    private static int forgetHere(Minecraft mc) {
        if (mc.player == null || mc.level == null) return 0;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            Chat.error("Look at the container to forget.");
            return 0;
        }
        ContainerResolver.Resolved res = ContainerResolver.resolveAt(mc, bhr.getBlockPos());
        ContainerEntry e = res != null ? WarehouseClient.index().atBlock(res.dimension(), res.primary()) : null;
        if (e == null) {
            Chat.error("That block is not an indexed container.");
            return 0;
        }
        WarehouseClient.index().remove(e.id);
        Chat.info("Forgot " + e.label() + ". It will be re-indexed the next time you open it inside a region.");
        return 1;
    }

    private static int overrideHere(Minecraft mc) {
        if (mc.player == null || mc.level == null) return 0;
        if (mc.player.getMainHandItem().isEmpty()) {
            Chat.error("Hold the item you want to pin to a chest.");
            return 0;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            Chat.error("Look at the chest the item should live in.");
            return 0;
        }
        ContainerResolver.Resolved res = ContainerResolver.resolveAt(mc, bhr.getBlockPos());
        if (res == null) {
            Chat.error("That block is not a chest, barrel or shulker box.");
            return 0;
        }
        ContainerEntry e = WarehouseClient.index().atBlock(res.dimension(), res.primary());
        if (e == null) {
            Chat.error("That container is not indexed yet. Open it once (inside a region or pinned).");
            return 0;
        }
        ItemKey key = Fingerprinter.key(mc.player.getMainHandItem());
        WarehouseClient.organizer().setOverride(key, e.id);
        Chat.info(Fingerprinter.displayName(mc.player.getMainHandItem()) + " now always belongs in " + e.label() + ".");
        return 1;
    }

    private static int show() {
        Organizer org = WarehouseClient.organizer();
        Plan pending = org.pending();
        Plan active = org.plan();
        if (pending == null && !active.exists()) {
            Chat.info("No plan. Stand near your warehouse entrance and run /warehouse plan run.");
            return 1;
        }
        if (pending != null) {
            Chat.send(Chat.prefix().append(Component.literal("Pending plan (" + pending.chestBudget + " chests):").withStyle(ChatFormatting.YELLOW)));
            for (Organizer.ZoneSummary z : org.summarize(pending)) line(z);
            Chat.info("/warehouse plan accept  or  /warehouse plan reject");
        }
        if (active.isActive()) {
            Chat.send(Chat.prefix().append(Component.literal("Active plan (" + active.chestBudget + " chests, " + dev.warehouse.ui.Staleness.describe(active.createdEpochMs) + "):").withStyle(ChatFormatting.GREEN)));
            for (Organizer.ZoneSummary z : org.summarize(active)) line(z);
        }
        return 1;
    }

    private static void line(Organizer.ZoneSummary z) {
        Chat.send(Component.literal("  " + z.category()).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + z.chests() + " chest(s), " + z.slotsUsed() + "/" + z.slotsTotal() + " slots" + (z.unopened() > 0 ? ", " + z.unopened() + " unopened" : "")).withStyle(ChatFormatting.GRAY)));
    }
}
