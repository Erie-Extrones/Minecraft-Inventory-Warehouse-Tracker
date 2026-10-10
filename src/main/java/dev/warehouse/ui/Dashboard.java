package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.prices.Valuation;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionType;
import dev.warehouse.shops.Shop;
import dev.warehouse.shops.ShopManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Overview tab: a fixed, non-scrolling dashboard. Stat tiles across the top, a donut of items by category on the
 * left, zone fill bars on the right, attention chips along the bottom. Every element is clickable.
 */
public final class Dashboard {
    public record Tile(String label, String value, String sub, int color, SearchScreen.@Nullable Tab target, @Nullable UUID shop) {}

    public record Slice(String label, long count, int color) {}

    public record Bar(String label, int percent, String detail, int color) {}

    public record Chip(String text, int color, SearchScreen.Tab target) {}

    private record Hit(int x0, int y0, int x1, int y1, SearchScreen.@Nullable Tab target, @Nullable UUID shop, @Nullable String tooltip) {}

    public final List<Tile> tiles = new ArrayList<>();
    public final List<Slice> slices = new ArrayList<>();
    public long sliceTotal;
    public final List<Bar> bars = new ArrayList<>();
    public final List<Chip> chips = new ArrayList<>();
    public String planNote = "";
    private final List<Hit> hits = new ArrayList<>();

    // ------------------------------------------------------------ data

    public static Dashboard compute(@Nullable Minecraft mc) {
        Dashboard d = new Dashboard();
        var index = WarehouseClient.index();
        var val = WarehouseClient.valuation();
        var cats = WarehouseClient.categories();
        long now = System.currentTimeMillis();
        int containers = 0, unopened = 0, stale = 0;
        for (ContainerEntry e : index.all()) {
            Region r = WarehouseClient.regions().byId(e.regionId);
            if (r == null || r.type != RegionType.WAREHOUSE) continue;
            containers++;
            if (e.lastSeenEpochMs == 0) unopened++;
            else if (now - e.lastSeenEpochMs > 7L * 86_400_000L) stale++;
        }
        Map<ItemKey, Long> counts = val.regionCounts(RegionType.WAREHOUSE);
        long items = 0;
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> en : counts.entrySet()) {
            items += en.getValue();
            byCategory.merge(cats.categoryOf(en.getKey()), en.getValue(), Long::sum);
        }
        Valuation.Total wh = val.total(counts);
        Valuation.Total inv = mc != null && mc.player != null ? val.inventoryValue(mc) : new Valuation.Total(0, 0, 0, 0);

        d.tiles.add(new Tile("Containers", String.valueOf(containers), unopened > 0 ? unopened + " never opened" : stale + " stale", 0xFF40C8FF, SearchScreen.Tab.SEARCH, null));
        d.tiles.add(new Tile("Item types", String.valueOf(counts.size()), items + " items", 0xFF80FF80, SearchScreen.Tab.SEARCH, null));
        d.tiles.add(new Tile("Warehouse value", Valuation.money(wh.amount()), wh.unpricedTypes() > 0 ? wh.unpricedTypes() + " unpriced types" : wh.pricedTypes() + " priced types", 0xFFFFD060, SearchScreen.Tab.SEARCH, null));
        d.tiles.add(new Tile("Inventory value", Valuation.money(inv.amount()), inv.unpricedTypes() > 0 ? inv.unpricedTypes() + " unpriced" : "", 0xFFC0A040, SearchScreen.Tab.UNSORTED, null));
        Organizer org = WarehouseClient.organizer();
        Plan plan = org.plan();
        int misplaced = plan.isActive() ? WarehouseClient.planDiff().entries().size() : 0;
        d.tiles.add(new Tile("Misplaced", String.valueOf(misplaced), plan.isActive() ? WarehouseClient.planDiff().unopenedSincePlan() + " chests unchecked" : "no plan", misplaced > 0 ? 0xFFFF8080 : 0xFF80FF80, SearchScreen.Tab.MISPLACED, null));
        List<Shop> shops = WarehouseClient.shops().all();
        if (shops.isEmpty()) {
            int lost = WarehouseClient.lostLog().entries().size();
            d.tiles.add(new Tile("Lost items", String.valueOf(lost), "drops and deaths", lost > 0 ? 0xFFFF8080 : 0xFF80FF80, SearchScreen.Tab.LOST, null));
        } else {
            double shopValue = 0;
            int due = 0, low = 0;
            for (Shop s : shops) {
                ShopManager.Report rep = WarehouseClient.shops().report(s);
                shopValue += rep.value();
                low += rep.low() + rep.out();
                if (WarehouseClient.shops().overdue(s)) due++;
            }
            d.tiles.add(new Tile(shops.size() == 1 ? "Shop" : shops.size() + " shops", Valuation.money(shopValue), (low > 0 ? low + " low/out" : "stocked") + (due > 0 ? ", " + due + " due" : ""), due > 0 || low > 0 ? 0xFFFFD040 : ConfigIO.get().shopColor, SearchScreen.Tab.SHOPS, null));
        }

        // Donut: items by category, biggest first, the tail folded into "Other".
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(byCategory.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < 8) d.slices.add(new Slice(sorted.get(i).getKey(), sorted.get(i).getValue(), org.categoryColor(sorted.get(i).getKey()) | 0xFF000000));
            else other += sorted.get(i).getValue();
        }
        if (other > 0) d.slices.add(new Slice("Other", other, 0xFF808080));
        d.sliceTotal = items;

        // Bars: zone fill.
        if (plan.isActive()) {
            List<Organizer.ZoneSummary> zones = new ArrayList<>(org.summarize(plan));
            zones.sort((a, b) -> Integer.compare(pct(b), pct(a)));
            for (Organizer.ZoneSummary z : zones) {
                int p = pct(z);
                d.bars.add(new Bar(z.category(), p, z.slotsUsed() + "/" + z.slotsTotal() + " slots · " + z.chests() + " chests", org.categoryColor(z.category()) | 0xFF000000));
            }
            d.planNote = zones.size() + " zones · " + plan.chestBudget + " chests · plan " + Staleness.describe(plan.createdEpochMs);
        } else {
            d.planNote = org.pending() != null ? "Pending plan waiting for Accept (Plan tab)" : "No plan yet: Plan tab → Run, then Accept";
        }

        // Chips.
        int lost = WarehouseClient.lostLog().entries().size();
        var urgent = WarehouseClient.condenser().urgent();
        int freeable = 0;
        for (var r : urgent) freeable += r.totalSlotsFreed();
        int unsorted = 0;
        if (mc != null && mc.player != null) {
            var pinv = mc.player.getInventory();
            for (int i = 0; i < pinv.getContainerSize(); i++) {
                ItemStack st = pinv.getItem(i);
                if (st.isEmpty() || WarehouseClient.clearMode().isEssential(mc, st, i)) continue;
                ItemKey k = Fingerprinter.key(st);
                if (index.holding(k).isEmpty() && org.resolve(k) == null) unsorted++;
            }
        }
        d.chips.add(new Chip("Lost " + lost, lost > 0 ? 0xFFFF8080 : 0xFF60A060, SearchScreen.Tab.LOST));
        d.chips.add(new Chip(urgent.isEmpty() ? "No zone low on space" : urgent.size() + " zone(s) low · free " + freeable + " slots", urgent.isEmpty() ? 0xFF60A060 : 0xFFFFD040, SearchScreen.Tab.CONDENSE));
        d.chips.add(new Chip("Unsorted on you " + unsorted, unsorted > 0 ? 0xFFFFD040 : 0xFF60A060, SearchScreen.Tab.UNSORTED));
        d.chips.add(new Chip("Stale chests " + stale, stale > 0 ? 0xFFFFD040 : 0xFF60A060, SearchScreen.Tab.SEARCH));
        d.chips.add(new Chip(WarehouseClient.prices().all().size() + " prices known", 0xFF909090, SearchScreen.Tab.SEARCH));
        var check = WarehouseClient.stockCheck();
        if (check.isActive()) d.chips.add(new Chip(check.label(), 0xFFFFD060, SearchScreen.Tab.OVERVIEW));
        var craft = WarehouseClient.craftPlanner().active();
        if (craft != null) d.chips.add(new Chip("Crafting " + craft.count() + "× " + craft.target().getHoverName().getString() + (craft.missingLines() > 0 ? " · " + craft.missingLines() + " missing" : ""), 0xFFC0A0FF, SearchScreen.Tab.OVERVIEW));
        return d;
    }

    private static int pct(Organizer.ZoneSummary z) {
        return z.slotsTotal() > 0 ? 100 * z.slotsUsed() / z.slotsTotal() : 0;
    }

    // ------------------------------------------------------------ drawing

    public void draw(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        hits.clear();
        int w = x1 - x0, h = y1 - y0;
        int gap = 4;
        // Tiles
        int tileH = Math.min(40, Math.max(30, h / 7));
        int n = tiles.size();
        int tileW = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Tile t = tiles.get(i);
            int tx = x0 + i * (tileW + gap);
            boolean hover = inside(mouseX, mouseY, tx, y0, tx + tileW, y0 + tileH);
            g.fill(tx, y0, tx + tileW, y0 + tileH, hover ? 0x60FFFFFF : 0x40000000);
            g.fill(tx, y0, tx + tileW, y0 + 2, t.color());
            g.text(font, font.plainSubstrByWidth(t.label(), tileW - 8), tx + 4, y0 + 5, 0xFFA0A0A0);
            String v = font.plainSubstrByWidth(t.value(), tileW - 8);
            g.text(font, v, tx + 4, y0 + 15, t.color());
            if (tileH >= 36 && !t.sub().isEmpty()) g.text(font, font.plainSubstrByWidth(t.sub(), tileW - 8), tx + 4, y0 + 26, 0xFF808080);
            hits.add(new Hit(tx, y0, tx + tileW, y0 + tileH, t.target(), t.shop(), t.sub().isEmpty() ? null : t.sub()));
        }
        // Chips (bottom)
        int chipH = 14;
        int cy = y1 - chipH;
        int cx = x0;
        for (Chip c : chips) {
            int cw = font.width(c.text()) + 10;
            if (cx + cw > x1) break;
            boolean hover = inside(mouseX, mouseY, cx, cy, cx + cw, cy + chipH);
            g.fill(cx, cy, cx + cw, cy + chipH, hover ? 0x60FFFFFF : 0x40000000);
            g.fill(cx, cy, cx + 2, cy + chipH, c.color());
            g.text(font, c.text(), cx + 6, cy + 3, c.color());
            hits.add(new Hit(cx, cy, cx + cw, cy + chipH, c.target(), null, null));
            cx += cw + gap;
        }
        // Middle area
        int my0 = y0 + tileH + gap + 2;
        int my1 = cy - gap;
        int midH = my1 - my0;
        if (midH < 40) return;
        int leftW = Math.min(w * 2 / 5, 230);
        int rightX = x0 + leftW + gap;
        // Donut
        g.fill(x0, my0, x0 + leftW, my1, 0x30000000);
        g.text(font, "Items by category", x0 + 4, my0 + 3, 0xFFA0A0A0);
        int legendW = Math.min(110, leftW / 2);
        int radius = Math.max(14, Math.min((midH - 16) / 2 - 2, (leftW - legendW - 12) / 2));
        int ccx = x0 + 6 + radius;
        int ccy = my0 + 14 + (midH - 14) / 2;
        drawDonut(g, ccx, ccy, radius, Math.max(5, radius / 2), mouseX, mouseY);
        int ly = my0 + 14;
        int maxLegend = Math.max(1, (midH - 16) / 10);
        for (int i = 0; i < slices.size() && i < maxLegend; i++) {
            Slice s = slices.get(i);
            int lx = ccx + radius + 8;
            g.fill(lx, ly + 1, lx + 6, ly + 7, s.color());
            int pct = sliceTotal > 0 ? (int) Math.round(100.0 * s.count() / sliceTotal) : 0;
            g.text(font, font.plainSubstrByWidth(s.label() + " " + pct + "%", x0 + leftW - lx - 10), lx + 9, ly, 0xFFDDDDDD);
            ly += 10;
        }
        if (slices.isEmpty()) g.text(font, "nothing indexed yet", x0 + 6, my0 + 16, 0xFF808080);
        // Bars
        g.fill(rightX, my0, x1, my1, 0x30000000);
        g.text(font, "Zone fill", rightX + 4, my0 + 3, 0xFFA0A0A0);
        g.text(font, font.plainSubstrByWidth(planNote, x1 - rightX - 70), rightX + 60, my0 + 3, 0xFF707070);
        int barH = 11, barGap = 2;
        int by = my0 + 14;
        int maxBars = Math.max(1, (my1 - by - 2) / (barH + barGap));
        int labelW = Math.min(90, (x1 - rightX) / 3);
        for (int i = 0; i < bars.size() && i < maxBars; i++) {
            Bar b = bars.get(i);
            int bx = rightX + 4 + labelW;
            int bw = x1 - bx - 34;
            boolean hover = inside(mouseX, mouseY, rightX, by, x1, by + barH);
            if (hover) g.fill(rightX, by, x1, by + barH, 0x30FFFFFF);
            g.text(font, font.plainSubstrByWidth(b.label(), labelW - 4), rightX + 4, by + 2, 0xFFDDDDDD);
            g.fill(bx, by + 1, bx + bw, by + barH - 1, 0x60000000);
            int fillW = (int) Math.round(bw * Math.min(100, b.percent()) / 100.0);
            int fc = b.percent() >= 90 ? 0xFFFF5050 : b.percent() >= ConfigIO.get().condenseAtFillPercent ? 0xFFFFC040 : b.color();
            g.fill(bx, by + 1, bx + fillW, by + barH - 1, fc);
            g.text(font, b.percent() + "%", bx + bw + 4, by + 2, fc);
            hits.add(new Hit(rightX, by, x1, by + barH, SearchScreen.Tab.PLAN, null, b.label() + ": " + b.detail()));
            by += barH + barGap;
        }
        if (bars.isEmpty()) g.text(font, font.plainSubstrByWidth(planNote, x1 - rightX - 8), rightX + 4, my0 + 16, 0xFF808080);
        else if (bars.size() > maxBars) g.text(font, "+" + (bars.size() - maxBars) + " more zones on the Plan tab", rightX + 4, my1 - 10, 0xFF707070);
        // Hover tooltip
        for (Hit hit : hits) {
            if (hit.tooltip() != null && inside(mouseX, mouseY, hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
                g.setTooltipForNextFrame(net.minecraft.network.chat.Component.literal(hit.tooltip()), mouseX, mouseY);
                break;
            }
        }
    }

    /** Donut by horizontal runs: each row is split into same-slice spans so a full chart is a few hundred fills. */
    private void drawDonut(GuiGraphicsExtractor g, int cx, int cy, int R, int r, int mouseX, int mouseY) {
        if (sliceTotal <= 0 || slices.isEmpty()) {
            for (int y = -R; y <= R; y++) {
                for (int x = -R; x <= R; x++) {
                    int d2 = x * x + y * y;
                    if (d2 <= R * R && d2 >= r * r) g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, 0x40FFFFFF);
                }
            }
            return;
        }
        double[] cum = new double[slices.size()];
        double acc = 0;
        for (int i = 0; i < slices.size(); i++) {
            acc += slices.get(i).count() / (double) sliceTotal;
            cum[i] = acc;
        }
        int hoverSlice = -1;
        int mx = mouseX - cx, my = mouseY - cy;
        if (mx * mx + my * my <= R * R && mx * mx + my * my >= r * r) hoverSlice = sliceAt(mx, my, cum);
        for (int y = -R; y <= R; y++) {
            int runStart = Integer.MIN_VALUE, runSlice = -1;
            for (int x = -R; x <= R + 1; x++) {
                int d2 = x * x + y * y;
                int s = (x <= R && d2 <= R * R && d2 >= r * r) ? sliceAt(x, y, cum) : -1;
                if (s != runSlice) {
                    if (runSlice >= 0) g.fill(cx + runStart, cy + y, cx + x, cy + y + 1, runSlice == hoverSlice ? brighten(slices.get(runSlice).color()) : slices.get(runSlice).color());
                    runStart = x;
                    runSlice = s;
                }
            }
        }
        if (hoverSlice >= 0) {
            Slice s = slices.get(hoverSlice);
            g.setTooltipForNextFrame(net.minecraft.network.chat.Component.literal(s.label() + ": " + s.count() + " items (" + Math.round(100.0 * s.count() / sliceTotal) + "%)"), mouseX, mouseY);
        }
    }

    private static int sliceAt(int x, int y, double[] cum) {
        double a = Math.atan2(x, -y); // 0 at top, clockwise
        if (a < 0) a += 2 * Math.PI;
        double f = a / (2 * Math.PI);
        for (int i = 0; i < cum.length; i++) if (f <= cum[i]) return i;
        return cum.length - 1;
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 60), g = Math.min(255, ((argb >> 8) & 0xFF) + 60), b = Math.min(255, (argb & 0xFF) + 60);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean inside(int mx, int my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    /** What a click at the position should do: a tab to open, or a shop to show. */
    public @Nullable Object click(double mx, double my) {
        for (Hit h : hits) {
            if (inside((int) mx, (int) my, h.x0(), h.y0(), h.x1(), h.y1())) return h.shop() != null ? h.shop() : h.target();
        }
        return null;
    }
}
