package dev.warehouse.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Categorizer;
import dev.warehouse.region.Region;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Sub-commands: items, debug (+ plan/export/clear added by later phases). */
public final class ExtraCommands {
    private ExtraCommands() {}

    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        PlanCommands.attach(root);
        DataCommands.attach(root);
        root.then(literal("items")
                .then(literal("list").executes(c -> itemsList()))
                .then(literal("rename")
                        .then(argument("group", StringArgumentType.string())
                                .then(argument("name", StringArgumentType.greedyString()).executes(c -> {
                                    Optional<ItemGroup> g = WarehouseClient.itemGroups().byIdOrName(StringArgumentType.getString(c, "group"));
                                    if (g.isEmpty()) return noGroup(StringArgumentType.getString(c, "group"));
                                    WarehouseClient.itemGroups().rename(g.get(), StringArgumentType.getString(c, "name").trim());
                                    Chat.info("Renamed group " + g.get().groupId + " to '" + g.get().displayName + "'.");
                                    return 1;
                                }))))
                .then(literal("merge")
                        .then(argument("groupA", StringArgumentType.string())
                                .then(argument("groupB", StringArgumentType.string()).executes(c -> {
                                    Optional<ItemGroup> a = WarehouseClient.itemGroups().byIdOrName(StringArgumentType.getString(c, "groupA"));
                                    Optional<ItemGroup> b = WarehouseClient.itemGroups().byIdOrName(StringArgumentType.getString(c, "groupB"));
                                    if (a.isEmpty()) return noGroup(StringArgumentType.getString(c, "groupA"));
                                    if (b.isEmpty()) return noGroup(StringArgumentType.getString(c, "groupB"));
                                    WarehouseClient.itemGroups().merge(a.get(), b.get());
                                    WarehouseClient.categories().invalidate();
                                    Chat.info("Merged " + b.get().groupId + " into " + a.get().groupId + ".");
                                    return 1;
                                }))))
                .then(literal("prune").executes(c -> {
                    var mc = c.getSource().getClient();
                    java.util.Set<ItemKey> seen = new java.util.HashSet<>();
                    for (ContainerEntry e : WarehouseClient.index().all()) {
                        for (StackRecord s : e.contents) {
                            seen.add(s.key);
                            if (s.nested != null) for (StackRecord n : s.nested) seen.add(n.key);
                        }
                    }
                    if (mc.player != null) {
                        var inv = mc.player.getInventory();
                        for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty()) seen.add(dev.warehouse.items.Fingerprinter.key(inv.getItem(i)));
                    }
                    var groups = WarehouseClient.itemGroups().all();
                    int before = groups.size();
                    groups.removeIf(g -> g.categoryOverride == null && g.members.stream().noneMatch(seen::contains));
                    WarehouseClient.storage().items.markDirty();
                    WarehouseClient.itemGroups().invalidate();
                    WarehouseClient.categories().invalidate();
                    Chat.info("Pruned " + (before - groups.size()) + " item group(s) with no known items; " + groups.size() + " remain.");
                    return 1;
                }))
                .then(literal("category")
                        .then(argument("group", StringArgumentType.string())
                                .then(argument("category", StringArgumentType.greedyString()).executes(c -> {
                                    Optional<ItemGroup> g = WarehouseClient.itemGroups().byIdOrName(StringArgumentType.getString(c, "group"));
                                    if (g.isEmpty()) return noGroup(StringArgumentType.getString(c, "group"));
                                    String cat = StringArgumentType.getString(c, "category").trim();
                                    if (cat.equalsIgnoreCase("none") || cat.equalsIgnoreCase("auto")) cat = null;
                                    WarehouseClient.itemGroups().setCategory(g.get(), cat);
                                    WarehouseClient.categories().invalidate();
                                    Chat.info("Group " + g.get().groupId + " category: " + (cat == null ? "auto" : cat) + ".");
                                    return 1;
                                })))));

        root.then(literal("debug")
                .then(literal("index").executes(c -> debugIndex(false)))
                .then(literal("index").then(literal("full").executes(c -> debugIndex(true))))
                .then(literal("hand").executes(c -> {
                    var mc = c.getSource().getClient();
                    if (mc.player == null) return 0;
                    var stack = mc.player.getMainHandItem();
                    ItemKey key = dev.warehouse.items.Fingerprinter.key(stack);
                    Chat.info("Key: " + key.asString());
                    Chat.info("Category: " + WarehouseClient.categories().categoryOf(key) + "  sub-family: " + WarehouseClient.categories().subFamilyOf(key));
                    ItemGroup g = WarehouseClient.itemGroups().groupFor(key);
                    if (g != null) Chat.info("Group: " + g.groupId + " ('" + g.displayName + "', " + g.members.size() + " fingerprints)");
                    Chat.info("Stored: " + WarehouseClient.index().totalCount(key) + " across " + WarehouseClient.index().holding(key).size() + " container(s)");
                    return 1;
                }))
                .then(literal("samples").executes(c -> {
                    int groups = 0, samples = 0;
                    for (ItemGroup g : WarehouseClient.itemGroups().all()) {
                        if (g.samples != null && !g.samples.isEmpty()) {
                            groups++;
                            samples += g.samples.size();
                        }
                    }
                    Chat.info(samples + " component sample(s) across " + groups + " custom item group(s); included in /warehouse export.");
                    return 1;
                }))
                .then(literal("inventory").executes(c -> {
                    Chat.info("Unexplained inventory loss events this session: " + WarehouseClient.inventoryTracker().unexplainedLossEvents());
                    Chat.info("Lost log entries: " + WarehouseClient.lostLog().entries().size());
                    return 1;
                }))
                .then(literal("categories").executes(c -> {
                    Chat.info("Categories: " + String.join(", ", Categorizer.DEFAULT_ORDER));
                    return 1;
                })));
    }

    private static int noGroup(String s) {
        Chat.error("No item group '" + s + "'. Use /warehouse items list.");
        return 0;
    }

    private static int itemsList() {
        var groups = WarehouseClient.itemGroups().all();
        if (groups.isEmpty()) {
            Chat.info("No custom item groups yet. Open containers holding custom items to discover them.");
            return 1;
        }
        Chat.info(groups.size() + " custom item group(s):");
        for (ItemGroup g : groups) {
            String cat = g.categoryOverride != null ? g.categoryOverride : WarehouseClient.categories().categoryOf(g.members.isEmpty() ? new ItemKey("minecraft:air", "x") : g.members.get(0)) + " (auto)";
            Chat.send(Component.literal("  " + g.groupId).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("  '" + g.displayName + "'  " + g.members.size() + " variant(s)  " + cat
                            + (g.loreHint != null ? "  — " + g.loreHint : "")).withStyle(ChatFormatting.GRAY)));
        }
        return 1;
    }

    private static int debugIndex(boolean full) {
        var entries = WarehouseClient.index().all();
        if (entries.isEmpty()) {
            Chat.info("Index is empty. Open a chest inside a region (or pin one).");
            return 1;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        Chat.info(entries.size() + " indexed container(s):");
        for (ContainerEntry e : entries) {
            Region r = WarehouseClient.regions().byId(e.regionId);
            int items = 0;
            for (StackRecord s : e.contents) items += s.count;
            Chat.send(Component.literal("  " + e.label()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("  " + e.contents.size() + "/" + e.slotCount + " slots, " + items + " items, seen " + fmt.format(new Date(e.lastSeenEpochMs))
                            + (r != null ? ", " + r.name : "") + (e.manualPin ? ", pinned" : "")).withStyle(ChatFormatting.GRAY)));
            if (full) {
                for (StackRecord s : e.contents) {
                    Chat.send(Component.literal("      [" + s.slot + "] " + s.displayName + " x" + s.count
                            + (s.nested != null ? " (+" + s.nested.size() + " nested)" : "")).withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
        return 1;
    }
}
