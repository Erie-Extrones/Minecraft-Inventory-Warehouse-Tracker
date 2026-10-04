package dev.warehouse.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.claims.ClaimAnchor;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /warehouse ... client commands. Sub-trees for later phases are added by their own helper classes. */
public final class WarehouseCommands {
    private WarehouseCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(WarehouseCommands::build);
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher, net.minecraft.commands.CommandBuildContext ctx) {
        var root = literal("warehouse");

        // ---- regions ----
        root.then(literal("region")
                .then(literal("list").executes(c -> regionList(c)))
                .then(literal("rename")
                        .then(argument("old", StringArgumentType.string())
                                .then(argument("new", StringArgumentType.greedyString()).executes(c -> {
                                    String o = StringArgumentType.getString(c, "old");
                                    String n = StringArgumentType.getString(c, "new").trim();
                                    if (WarehouseClient.regions().byName(n).isPresent()) {
                                        Chat.error("A region named '" + n + "' already exists.");
                                        return 0;
                                    }
                                    if (WarehouseClient.regions().rename(o, n)) {
                                        Chat.info("Renamed '" + o + "' to '" + n + "'.");
                                        return 1;
                                    }
                                    Chat.error("No region named '" + o + "'.");
                                    return 0;
                                }))))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.greedyString()).executes(c -> {
                            String n = StringArgumentType.getString(c, "name").trim();
                            if (WarehouseClient.regions().delete(n)) {
                                Chat.info("Deleted region '" + n + "'.");
                                WarehouseClient.onRegionsChanged();
                                return 1;
                            }
                            Chat.error("No region named '" + n + "'.");
                            return 0;
                        })))
                .then(literal("outlines")
                        .executes(c -> toggleOutlines(null))
                        .then(literal("on").executes(c -> toggleOutlines(true)))
                        .then(literal("off").executes(c -> toggleOutlines(false))))
                .then(literal("cancel").executes(c -> {
                    WarehouseClient.wand().selection.clear();
                    Chat.info("Selection cleared.");
                    return 1;
                })));

        // ---- wand ----
        root.then(literal("wand")
                .executes(c -> {
                    Chat.info("Wand item: " + ConfigIO.get().wandItem);
                    return 1;
                })
                .then(argument("item", StringArgumentType.greedyString()).executes(c -> {
                    String raw = StringArgumentType.getString(c, "item").trim();
                    Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
                    if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                        Chat.error("Unknown item '" + raw + "'.");
                        return 0;
                    }
                    ConfigIO.get().wandItem = id.toString();
                    ConfigIO.save();
                    Chat.info("Wand item set to " + id + ".");
                    return 1;
                })));

        // ---- claims ----
        root.then(literal("claims")
                .then(literal("import").executes(c -> {
                    WarehouseClient.claimImporter().run(c.getSource().getClient());
                    return 1;
                }))
                .then(literal("reimport").executes(c -> {
                    WarehouseClient.claimImporter().run(c.getSource().getClient());
                    return 1;
                }))
                .then(literal("list").executes(c -> claimsList(c)))
                .then(literal("clear").executes(c -> {
                    int n = WarehouseClient.storage().claims.get().size();
                    WarehouseClient.storage().claims.get().clear();
                    WarehouseClient.storage().claims.markDirty();
                    Chat.info("Removed " + n + " claim anchor(s). Claim regions were kept; delete them with /warehouse region delete.");
                    return 1;
                })));

        ExtraCommands.attach(root);
        dispatcher.register(root);
    }

    private static int toggleOutlines(Boolean value) {
        boolean v = value != null ? value : !ConfigIO.get().showRegionOutlines;
        ConfigIO.get().showRegionOutlines = v;
        ConfigIO.save();
        Chat.info("Region outlines " + (v ? "on" : "off") + ".");
        return 1;
    }

    private static int regionList(CommandContext<FabricClientCommandSource> c) {
        var list = WarehouseClient.regions().all();
        if (list.isEmpty()) {
            Chat.info("No regions. Hold the wand (" + ConfigIO.get().wandItem + ") and right-click two corners, or /warehouse claims import.");
            return 1;
        }
        Chat.info(list.size() + " region(s):");
        for (Region r : list) {
            Chat.send(Component.literal("  " + r.name).withStyle(r.type == dev.warehouse.region.RegionType.WAREHOUSE ? ChatFormatting.AQUA : ChatFormatting.GREEN)
                    .append(Component.literal("  " + r.type.name().toLowerCase() + "  " + r.dimension + "  ("
                            + r.minX + "," + r.minY + "," + r.minZ + ") -> (" + r.maxX + "," + r.maxY + "," + r.maxZ + ")").withStyle(ChatFormatting.GRAY)));
        }
        return 1;
    }

    private static int claimsList(CommandContext<FabricClientCommandSource> c) {
        var anchors = WarehouseClient.storage().claims.get();
        if (anchors.isEmpty()) {
            Chat.info("No claim anchors. Run /warehouse claims import.");
            return 1;
        }
        Chat.info(anchors.size() + " claim anchor(s):");
        for (ClaimAnchor a : anchors) {
            Region r = WarehouseClient.regions().byId(a.boundRegionId);
            String bounds = r != null ? "bounds: " + r.name : "bounds: not captured (right-click a block inside with a stick)";
            Chat.send(Component.literal("  " + a.world + " x" + a.lesserX + ", z" + a.lesserZ + " (" + a.area + " blocks)  ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(bounds).withStyle(r != null ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        }
        return 1;
    }
}
