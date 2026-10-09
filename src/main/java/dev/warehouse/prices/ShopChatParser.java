package dev.warehouse.prices;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.region.RegionManager;
import dev.warehouse.shops.Shop;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Records prices from shop chat. Built in: QuickShop-style "Shop Information" blocks (owner, item, price per item, selling/buying)
 * and "Successfully purchased/sold: N Item for $X" confirmations. Extra single-line patterns come from the config.
 * A block whose owner is you sets your own shop's sell price instead of a market observation.
 */
public final class ShopChatParser {
    private static final Pattern COLOR = Pattern.compile("§.");
    private static final Pattern DECOR = Pattern.compile("^[\\s|+=~*#>\\-]+|[\\s|+=~*#<\\-]+$");
    private static final Pattern OWNER = Pattern.compile("(?i)^owner:\\s*(.+)$");
    private static final Pattern ITEM = Pattern.compile("(?i)^item:\\s*(.+?)(?:\\s*\\[item preview\\])?\\s*$");
    private static final Pattern PRICE = Pattern.compile("(?i)^price per (.+?)\\s*[-:–]\\s*\\$?\\s*([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern MODE = Pattern.compile("(?i)^this shop is (selling|buying) items");
    private static final Pattern TX_HEAD = Pattern.compile("(?i)^successfully (purchased|sold):?\\s*(.*)$");
    private static final Pattern TX_LINE = Pattern.compile("(?i)^(\\d[\\d,]*)\\s*[x×]?\\s+(.+?)\\s+for\\s+\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*$");
    private static final long BLOCK_WINDOW_MS = 5000, TX_WINDOW_MS = 3000, OWNER_MEMORY_MS = 120_000;

    private long blockStartMs;
    private @Nullable String owner, item;
    private @Nullable Double price;
    private long txAtMs;
    private boolean txPurchase;
    private @Nullable String lastOwner;
    private long lastOwnerAtMs;
    private List<Pattern> custom = List.of();
    private List<ModConfig.PricePattern> customSource;

    public void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay && ConfigIO.get().capturePricesFromChat && WarehouseClient.storage().isBound()) {
                try {
                    handle(message.getString());
                } catch (Exception e) {
                    WarehouseClient.LOGGER.debug("Shop chat parse failed", e);
                }
            }
            return true;
        });
    }

    void handle(String raw) {
        String line = DECOR.matcher(COLOR.matcher(raw).replaceAll("")).replaceAll("").trim();
        if (line.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (line.toLowerCase(Locale.ROOT).startsWith("shop information")) {
            blockStartMs = now;
            owner = null;
            item = null;
            price = null;
            return;
        }
        Matcher m;
        if (now - blockStartMs < BLOCK_WINDOW_MS) {
            if ((m = OWNER.matcher(line)).find()) {
                owner = m.group(1).trim();
                lastOwner = owner;
                lastOwnerAtMs = now;
                return;
            }
            if ((m = ITEM.matcher(line)).find()) {
                item = m.group(1).trim();
                return;
            }
            if ((m = PRICE.matcher(line)).find()) {
                if (item == null) item = m.group(1).trim();
                price = parse(m.group(2));
                return;
            }
            if ((m = MODE.matcher(line)).find()) {
                if (item != null && price != null) record(item, price, 1, m.group(1).equalsIgnoreCase("buying"), owner, "chat");
                blockStartMs = 0;
                return;
            }
        }
        if ((m = TX_HEAD.matcher(line)).find()) {
            txPurchase = m.group(1).equalsIgnoreCase("purchased");
            txAtMs = now;
            String rest = m.group(2);
            if (rest != null && !rest.isBlank()) transaction(rest, now);
            return;
        }
        if (now - txAtMs < TX_WINDOW_MS && TX_LINE.matcher(line).find()) {
            transaction(line, now);
            return;
        }
        customPatterns(line);
    }

    private void transaction(String s, long now) {
        Matcher m = TX_LINE.matcher(s);
        if (!m.find()) return;
        int qty = Math.max(1, (int) parse(m.group(1)));
        double total = parse(m.group(3));
        String shop = now - lastOwnerAtMs < OWNER_MEMORY_MS ? lastOwner : null;
        record(m.group(2).trim(), total / qty, qty, !txPurchase, shop, "chat");
        txAtMs = 0;
    }

    private void customPatterns(String line) {
        ModConfig cfg = ConfigIO.get();
        if (cfg.priceChatPatterns == null || cfg.priceChatPatterns.isEmpty()) return;
        if (customSource != cfg.priceChatPatterns) {
            List<Pattern> ps = new ArrayList<>();
            for (ModConfig.PricePattern p : cfg.priceChatPatterns) {
                try {
                    ps.add(p.regex == null ? null : Pattern.compile(p.regex, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    ps.add(null);
                    WarehouseClient.LOGGER.warn("Bad price chat regex '{}': {}", p.regex, e.getMessage());
                }
            }
            custom = ps;
            customSource = cfg.priceChatPatterns;
        }
        for (int i = 0; i < custom.size(); i++) {
            Pattern p = custom.get(i);
            if (p == null) continue;
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            ModConfig.PricePattern spec = cfg.priceChatPatterns.get(i);
            try {
                String name = m.group(spec.itemGroup).trim();
                double price = parse(m.group(spec.priceGroup));
                int qty = spec.qtyGroup > 0 ? Math.max(1, (int) parse(m.group(spec.qtyGroup))) : 1;
                record(name, price / qty, qty, "sell".equalsIgnoreCase(spec.side), null, "chat");
            } catch (Exception ignored) {
            }
            return;
        }
    }

    /** Store a price; {@code sell} is from your point of view (true = you receive this). */
    public void record(String itemName, double unit, int qty, boolean sell, @Nullable String shop, String source) {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player != null ? mc.player.getGameProfile().name() : null;
        if (shop != null && me != null && shop.equalsIgnoreCase(me) && mc.level != null) {
            // Our own shop: a SELLING shop's price is what customers pay us.
            Shop own = WarehouseClient.shops().nearest(RegionManager.dimensionId(mc.level), mc.player.blockPosition(), 64);
            if (own != null && !sell) {
                WarehouseClient.shops().setSellPrice(own, PriceKeys.fromName(itemName), unit);
                if (ConfigIO.get().announcePriceCapture) Chat.info(WarehouseClient.shops().name(own) + ": " + itemName + " sells for " + Valuation.money(unit) + " (recorded as your shop price).");
            }
            return;
        }
        boolean fresh = WarehouseClient.prices().add(new PriceObservation(PriceKeys.fromName(itemName), itemName, unit, qty, sell, shop, source));
        if (fresh && ConfigIO.get().announcePriceCapture) {
            Chat.info("Price noted: " + itemName + " " + Valuation.money(unit) + " " + (sell ? "sell" : "buy") + (shop != null ? " at " + shop : "") + ".");
        }
    }

    static double parse(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }
}
