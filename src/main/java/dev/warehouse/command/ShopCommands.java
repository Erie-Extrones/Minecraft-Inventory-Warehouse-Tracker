package dev.warehouse.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.prices.PriceBook;
import dev.warehouse.prices.PriceKeys;
import dev.warehouse.prices.PriceObservation;
import dev.warehouse.prices.Valuation;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import dev.warehouse.shops.Shop;
import dev.warehouse.shops.ShopManager;
import dev.warehouse.shops.StockCheck;
import dev.warehouse.ui.SearchScreen;
import dev.warehouse.ui.Staleness;
import dev.warehouse.wand.WandHandler;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /warehouse price ..., /warehouse value, /warehouse shop ..., /warehouse stockcheck, /warehouse region type */
public final class ShopCommands {
    private ShopCommands() {}

    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        root.then(literal("price")
                .then(literal("buy").then(argument("amount", DoubleArgumentType.doubleArg(0)).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), 1, false, null))
                        .then(argument("qty", IntegerArgumentType.integer(1)).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), IntegerArgumentType.getInteger(c, "qty"), false, null))
                                .then(argument("shop", StringArgumentType.greedyString()).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), IntegerArgumentType.getInteger(c, "qty"), false, StringArgumentType.getString(c, "shop")))))))
                .then(literal("sell").then(argument("amount", DoubleArgumentType.doubleArg(0)).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), 1, true, null))
                        .then(argument("qty", IntegerArgumentType.integer(1)).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), IntegerArgumentType.getInteger(c, "qty"), true, null))
                                .then(argument("shop", StringArgumentType.greedyString()).executes(c -> manual(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount"), IntegerArgumentType.getInteger(c, "qty"), true, StringArgumentType.getString(c, "shop")))))))
                .then(literal("list").executes(c -> list(c.getSource().getClient())))
                .then(literal("clear").executes(c -> clear(c.getSource().getClient()))));
        root.then(literal("value").executes(c -> value(c.getSource().getClient())));

        root.then(literal("shop")
                .then(literal("wand").executes(c -> {
                    WandHandler.nextType = WandHandler.nextType == RegionType.SHOP ? RegionType.WAREHOUSE : RegionType.SHOP;
                    Chat.info("The next region you draw will be a " + (WandHandler.nextType == RegionType.SHOP ? "Shop" : "Warehouse") + ".");
                    return 1;
                }))
                .then(literal("list").executes(c -> shopList()))
                .then(literal("report").executes(c -> report(c.getSource().getClient(), null))
                        .then(argument("name", StringArgumentType.greedyString()).executes(c -> report(c.getSource().getClient(), StringArgumentType.getString(c, "name")))))
                .then(literal("inventory").executes(c -> inventory(c.getSource().getClient(), null))
                        .then(argument("name", StringArgumentType.greedyString()).executes(c -> inventory(c.getSource().getClient(), StringArgumentType.getString(c, "name")))))
                .then(literal("restock").executes(c -> restock(c.getSource().getClient(), null))
                        .then(argument("name", StringArgumentType.greedyString()).executes(c -> restock(c.getSource().getClient(), StringArgumentType.getString(c, "name")))))
                .then(literal("price")
                        .then(literal("clear").executes(c -> shopPrice(c.getSource().getClient(), null)))
                        .then(argument("amount", DoubleArgumentType.doubleArg(0)).executes(c -> shopPrice(c.getSource().getClient(), DoubleArgumentType.getDouble(c, "amount")))))
                .then(literal("min")
                        .then(literal("clear").executes(c -> shopMin(c.getSource().getClient(), null)))
                        .then(argument("count", IntegerArgumentType.integer(0)).executes(c -> shopMin(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "count"))))));

        root.then(literal("keep")
                .executes(c -> keep(c.getSource().getClient(), true))
                .then(literal("remove").executes(c -> keep(c.getSource().getClient(), false)))
                .then(literal("list").executes(c -> {
                    var cfg = dev.warehouse.config.ConfigIO.get();
                    Chat.info("Always kept (" + cfg.alwaysKeep.size() + "): " + String.join(", ", cfg.alwaysKeep));
                    Chat.send(Component.literal("  Also kept: " + (cfg.essentialEquippedArmor ? "worn armor, " : "") + (cfg.essentialOffhand ? "off-hand, " : "") + (cfg.essentialHotbarTools ? "hotbar tools/weapons/armor, " : "")
                            + (cfg.essentialFood ? "food, " : "") + (cfg.essentialCustomGear ? "plugin gear" : "")).withStyle(ChatFormatting.GRAY));
                    return 1;
                })));

        root.then(literal("stockcheck").executes(c -> {
            WarehouseClient.stockCheck().start(c.getSource().getClient(), StockCheck.Kind.WAREHOUSE, null);
            return 1;
        }).then(literal("cancel").executes(c -> {
            WarehouseClient.stockCheck().cancel("Stock check cancelled.");
            return 1;
        })));
    }

    /** Appended under /warehouse region. */
    public static LiteralArgumentBuilder<FabricClientCommandSource> regionType() {
        return literal("type")
                .then(argument("name", StringArgumentType.string())
                        .then(argument("type", StringArgumentType.word()).executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            String t = StringArgumentType.getString(c, "type").toUpperCase();
                            RegionType type;
                            try {
                                type = RegionType.valueOf(t);
                            } catch (IllegalArgumentException e) {
                                Chat.error("Type must be warehouse, shop or claim.");
                                return 0;
                            }
                            Region r = WarehouseClient.regions().byName(name).orElse(null);
                            if (r == null) {
                                Chat.error("No region named '" + name + "'.");
                                return 0;
                            }
                            r.type = type;
                            WarehouseClient.regions().markDirty();
                            if (type == RegionType.SHOP) WarehouseClient.shops().forRegion(r);
                            WarehouseClient.onRegionsChanged();
                            WarehouseClient.organizer().invalidate();
                            Chat.info("Region '" + r.name + "' is now a " + type.name().toLowerCase() + " region.");
                            return 1;
                        })));
    }

    // ------------------------------------------------------------ essentials

    /** Add or remove the held item from the always-keep list: its group id for plugin items, else its item id. */
    private static int keep(Minecraft mc, boolean add) {
        ItemStack st = held(mc);
        if (st == null) return 0;
        ItemKey key = Fingerprinter.key(st);
        WarehouseClient.categories().learn(st, key);
        var group = WarehouseClient.categories().groupFor(key);
        String entry = group != null ? group.groupId : key.itemId;
        var cfg = dev.warehouse.config.ConfigIO.get();
        String name = Fingerprinter.displayName(st);
        if (add) {
            if (cfg.alwaysKeep.contains(entry)) {
                Chat.info(name + " is already always kept (" + entry + ").");
                return 1;
            }
            cfg.alwaysKeep.add(entry);
            dev.warehouse.config.ConfigIO.save();
            Chat.info(name + " is now always kept: clear-inventory and sort routes leave it in your inventory (" + entry + ").");
        } else {
            boolean removed = cfg.alwaysKeep.remove(entry) | cfg.alwaysKeep.remove(key.itemId) | cfg.alwaysKeep.remove(key.asString());
            if (!removed) {
                Chat.error(name + " was not on the always-keep list. /warehouse keep list shows it.");
                return 0;
            }
            dev.warehouse.config.ConfigIO.save();
            Chat.info(name + " removed from the always-keep list.");
        }
        return 1;
    }

    // ------------------------------------------------------------ prices

    private static @Nullable ItemStack held(Minecraft mc) {
        ItemStack st = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        if (st.isEmpty()) {
            Chat.error("Hold the item first.");
            return null;
        }
        return st;
    }

    private static int manual(Minecraft mc, double amount, int qty, boolean sell, @Nullable String shop) {
        ItemStack st = held(mc);
        if (st == null) return 0;
        ItemKey key = Fingerprinter.key(st);
        WarehouseClient.categories().learn(st, key);
        String name = Fingerprinter.displayName(st);
        double unit = amount / qty;
        WarehouseClient.prices().add(new PriceObservation(PriceKeys.of(key), name, unit, qty, sell, shop, "manual"));
        Chat.info("Noted " + name + " at " + Valuation.money(unit) + " each (" + (sell ? "sell" : "buy") + (shop != null ? ", " + shop : "") + "). Now valued at " + describe(key) + ".");
        return 1;
    }

    private static String describe(ItemKey key) {
        Valuation.Value v = WarehouseClient.valuation().unitValue(key);
        return v == null ? "nothing yet" : Valuation.describe(v);
    }

    private static int list(Minecraft mc) {
        ItemStack st = held(mc);
        if (st == null) return 0;
        ItemKey key = Fingerprinter.key(st);
        List<String> keys = PriceKeys.candidates(key);
        int n = 0;
        Chat.info(Fingerprinter.displayName(st) + ": valued at " + describe(key));
        for (String pk : keys) {
            for (PriceObservation o : WarehouseClient.prices().observations(pk)) {
                if (n++ >= 12) break;
                Chat.send(Component.literal("  " + Valuation.money(o.unitPrice) + " " + (o.sell ? "sell" : "buy") + (o.quantity > 1 ? " (per unit, ×" + o.quantity + ")" : "") + (o.shop != null ? " at " + o.shop : "")
                        + "  " + o.source + ", " + Staleness.describe(o.whenEpochMs)).withStyle(Staleness.formatting(o.whenEpochMs)));
            }
        }
        if (n == 0) Chat.send(Component.literal("  no prices recorded; /warehouse price buy|sell <amount> [qty] [shop]").withStyle(ChatFormatting.GRAY));
        PriceBook.Stats s = WarehouseClient.prices().stats(keys, true), b = WarehouseClient.prices().stats(keys, false);
        if (s != null || b != null) Chat.send(Component.literal("  sell: " + (s == null ? "-" : Valuation.money(s.median()) + " median of " + s.count()) + "   buy: " + (b == null ? "-" : Valuation.money(b.median()) + " median of " + b.count())).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int clear(Minecraft mc) {
        ItemStack st = held(mc);
        if (st == null) return 0;
        int n = 0;
        for (String pk : PriceKeys.candidates(Fingerprinter.key(st))) n += WarehouseClient.prices().clear(pk);
        Chat.info("Removed " + n + " price(s) for " + Fingerprinter.displayName(st) + ".");
        return 1;
    }

    private static int value(Minecraft mc) {
        Valuation val = WarehouseClient.valuation();
        Valuation.Total inv = val.inventoryValue(mc);
        Valuation.Total wh = val.regionValue(RegionType.WAREHOUSE);
        Chat.info("Inventory: " + Valuation.money(inv.amount()) + (inv.unpricedTypes() > 0 ? " (+" + inv.unpricedTypes() + " unpriced type(s))" : ""));
        Chat.info("Warehouse: " + Valuation.money(wh.amount()) + " across " + wh.pricedTypes() + " priced type(s)" + (wh.unpricedTypes() > 0 ? ", " + wh.unpricedTypes() + " unpriced" : ""));
        for (Shop s : WarehouseClient.shops().all()) {
            ShopManager.Report rep = WarehouseClient.shops().report(s);
            Chat.info(WarehouseClient.shops().name(s) + ": " + Valuation.money(rep.value()) + ", " + rep.low() + " low, " + rep.out() + " out");
        }
        return 1;
    }

    // ------------------------------------------------------------ shops

    private static @Nullable Shop shopFor(Minecraft mc, @Nullable String name) {
        ShopManager shops = WarehouseClient.shops();
        Shop s = name != null ? shops.byName(name.trim()) : null;
        if (s == null && name == null && mc.level != null && mc.player != null) s = shops.shopAt(RegionManager.dimensionId(mc.level), mc.player.blockPosition());
        if (s == null) Chat.error(name != null ? "No shop named '" + name + "'." : "Stand inside a Shop region or give its name. Draw one with /warehouse shop wand, or convert: /warehouse region type <name> shop.");
        return s;
    }

    private static int shopList() {
        var shops = WarehouseClient.shops().all();
        if (shops.isEmpty()) {
            Chat.info("No shops yet. /warehouse shop wand, then draw the shop with the wand.");
            return 1;
        }
        for (Shop s : shops) {
            ShopManager.Report rep = WarehouseClient.shops().report(s);
            Chat.info(WarehouseClient.shops().name(s) + ": " + rep.containers() + " container(s), " + rep.items().size() + " item type(s), " + Valuation.money(rep.value())
                    + ", last inventory " + (s.lastInventoryEpochMs == 0 ? "never" : Staleness.describe(s.lastInventoryEpochMs)) + (WarehouseClient.shops().overdue(s) ? " (due)" : ""));
        }
        return 1;
    }

    private static int report(Minecraft mc, @Nullable String name) {
        Shop s = shopFor(mc, name);
        if (s == null) return 0;
        SearchScreen.openShop(mc, s.regionId);
        return 1;
    }

    private static int inventory(Minecraft mc, @Nullable String name) {
        Shop s = shopFor(mc, name);
        if (s == null) return 0;
        WarehouseClient.stockCheck().start(mc, StockCheck.Kind.SHOP, s);
        return 1;
    }

    private static int restock(Minecraft mc, @Nullable String name) {
        Shop s = shopFor(mc, name);
        if (s == null) return 0;
        WarehouseClient.clearMode().startRestock(mc, s);
        return 1;
    }

    private static int shopPrice(Minecraft mc, @Nullable Double amount) {
        Shop s = shopFor(mc, null);
        if (s == null) return 0;
        ItemStack st = held(mc);
        if (st == null) return 0;
        ItemKey key = Fingerprinter.key(st);
        WarehouseClient.categories().learn(st, key);
        WarehouseClient.shops().setSellPrice(s, PriceKeys.of(key), amount);
        Chat.info(WarehouseClient.shops().name(s) + ": " + Fingerprinter.displayName(st) + (amount == null ? " price cleared." : " sells for " + Valuation.money(amount) + "."));
        return 1;
    }

    private static int shopMin(Minecraft mc, @Nullable Integer count) {
        Shop s = shopFor(mc, null);
        if (s == null) return 0;
        ItemStack st = held(mc);
        if (st == null) return 0;
        ItemKey key = Fingerprinter.key(st);
        WarehouseClient.shops().setRestockMin(s, PriceKeys.of(key), count);
        Chat.info(WarehouseClient.shops().name(s) + ": restock " + Fingerprinter.displayName(st) + (count == null ? " threshold back to automatic." : " when below " + count + "."));
        return 1;
    }
}
