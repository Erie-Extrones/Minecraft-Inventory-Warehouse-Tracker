package dev.warehouse.ui;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.StackRecord;
import dev.warehouse.inventory.LostEntry;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemGroup;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.organizer.PlanDiff;
import dev.warehouse.region.Region;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Tabbed warehouse UI: Search, Unsorted, Lost, Misplaced, Plan, Condense. */
public final class SearchScreen extends Screen {
    public enum Tab { OVERVIEW, SEARCH, UNSORTED, LOST, MISPLACED, PLAN, CONDENSE, SHOPS }

    private static String lastQuery = "";
    private static Tab lastTab = Tab.OVERVIEW;
    private static boolean landedThisSession;
    private static java.util.@Nullable UUID selectedShop;
    /** When false the search box is not focused automatically (controller input: an add-on opens the on-screen keyboard on demand). */
    public static boolean autoFocusQuery = true;

    private interface RowAction { void run(boolean shift); }

    private record Row(@Nullable ItemStack icon, String name, String detail, String right, int rightColor, @Nullable RowAction onClick, List<Component> tooltip, boolean header) {}

    private static final int ROW_H = 20;
    private Tab tab = lastTab;
    private EditBox query;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    /** Row selected by keyboard/controller navigation; -1 for none. Only drawn while {@link #listFocused}. */
    private int selected = -1;
    private boolean listFocused;
    private int listTop, listBottom, listLeft, listRight;
    private int misplacedMin = ConfigIO.get().misplacedMinCount;
    private String footer = "";
    private int rebuildIn;
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> planButtons = new ArrayList<>();
    private Button minMinus, minPlus, essentialsToggle, clearLostButton, clearHighlights, sortRoute;
    private Button shopInventory, shopRestock, shopBack, stockCheckButton;

    public SearchScreen() {
        super(Component.literal("Warehouse"));
    }

    @Override
    protected void init() {
        tabButtons.clear();
        planButtons.clear();
        int x = 20;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.literal(label(t)), btn -> switchTab(t)).bounds(x, 22, 60, 18).build();
            tabButtons.add(addRenderableWidget(b));
            x += 62;
        }
        query = new EditBox(getFont(), 20, 44, Math.min(260, width - 140), 18, Component.literal("Search"));
        query.setMaxLength(100);
        query.setHint(Component.literal("name, id, group or category").withStyle(ChatFormatting.DARK_GRAY));
        query.setValue(lastQuery);
        query.setResponder(s -> {
            lastQuery = s;
            scroll = 0;
            rebuild();
        });
        addRenderableWidget(query);

        clearHighlights = addRenderableWidget(Button.builder(Component.literal("Clear highlights"), b -> WarehouseClient.highlights().clear())
                .bounds(width - 120, 44, 100, 18).build());

        int px = 20;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Run"), b -> {
            WarehouseClient.organizer().run(minecraft, false);
            rebuild();
        }).bounds(px, 44, 50, 18).build()));
        px += 52;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Replan"), b -> {
            WarehouseClient.organizer().run(minecraft, true);
            rebuild();
        }).bounds(px, 44, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Accept"), b -> {
            if (WarehouseClient.organizer().accept()) Chat.info("Plan accepted.");
            rebuild();
        }).bounds(px, 44, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Reject"), b -> {
            WarehouseClient.organizer().reject();
            rebuild();
        }).bounds(px, 44, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Preview"), b -> {
            WarehouseClient.organizer().setPreview(!WarehouseClient.organizer().previewEnabled());
            rebuild();
        }).bounds(px, 44, 58, 18).build()));
        px += 60;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Export"), b -> {
            String name = WarehouseClient.exporter().export(null, false);
            if (name != null) Chat.info("Exported to " + name);
        }).bounds(px, 44, 54, 18).build()));

        minMinus = addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            misplacedMin = Math.max(1, misplacedMin - 1);
            rebuild();
        }).bounds(20, 44, 18, 18).build());
        minPlus = addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            misplacedMin = Math.min(64, misplacedMin + (misplacedMin >= 16 ? 16 : 1));
            rebuild();
        }).bounds(100, 44, 18, 18).build());
        sortRoute = addRenderableWidget(Button.builder(Component.literal("Sort route"), b -> {
            WarehouseClient.clearMode().toggle(minecraft, dev.warehouse.modes.ClearInventoryMode.Kind.SORT);
            onClose();
        }).bounds(126, 44, 80, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Guide me chest to chest to take misplaced items and put them where they belong"))).build());
        essentialsToggle = addRenderableWidget(Button.builder(Component.literal("Show essentials"), b -> {
            WarehouseClient.clearMode().setShowEssentials(!WarehouseClient.clearMode().showEssentials());
            rebuild();
        }).bounds(20, 44, 110, 18).build());
        clearLostButton = addRenderableWidget(Button.builder(Component.literal("Clear lost log"), b -> {
            WarehouseClient.lostLog().clear();
            rebuild();
        }).bounds(20, 44, 100, 18).build());
        shopBack = addRenderableWidget(Button.builder(Component.literal("All shops"), b -> {
            selectedShop = null;
            scroll = 0;
            rebuild();
        }).bounds(20, 44, 70, 18).build());
        shopInventory = addRenderableWidget(Button.builder(Component.literal("Do inventory"), b -> {
            var shop = WarehouseClient.shops().byId(selectedShop);
            if (shop != null) {
                WarehouseClient.stockCheck().start(minecraft, dev.warehouse.shops.StockCheck.Kind.SHOP, shop);
                onClose();
            }
        }).bounds(92, 44, 86, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Light up every container in the shop; each one unhighlights when you open it. The report opens when all are counted."))).build());
        shopRestock = addRenderableWidget(Button.builder(Component.literal("Restock route"), b -> {
            var shop = WarehouseClient.shops().byId(selectedShop);
            if (shop != null) {
                WarehouseClient.clearMode().startRestock(minecraft, shop);
                onClose();
            }
        }).bounds(180, 44, 90, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Pick up low items from the warehouse and deliver them to the shop"))).build());
        stockCheckButton = addRenderableWidget(Button.builder(Component.literal("Stock check"), b -> {
            WarehouseClient.stockCheck().start(minecraft, dev.warehouse.shops.StockCheck.Kind.WAREHOUSE, null);
            onClose();
        }).bounds(20, 44, 90, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Light up every warehouse container and walk them; each unhighlights when opened"))).build());

        listLeft = 20;
        listRight = width - 20;
        listTop = 68;
        listBottom = height - 24;
        switchTab(tab);
    }

    private static String label(Tab t) {
        return switch (t) {
            case SEARCH -> "Search";
            case UNSORTED -> "Unsorted";
            case LOST -> "Lost";
            case MISPLACED -> "Misplaced";
            case PLAN -> "Plan";
            case CONDENSE -> "Condense";
            case OVERVIEW -> "Overview";
            case SHOPS -> "Shops";
        };
    }

    private void switchTab(Tab t) {
        tab = t;
        lastTab = t;
        scroll = 0;
        for (int i = 0; i < tabButtons.size(); i++) tabButtons.get(i).active = Tab.values()[i] != t;
        query.visible = t == Tab.SEARCH;
        clearHighlights.visible = t != Tab.PLAN;
        for (Button b : planButtons) b.visible = t == Tab.PLAN;
        minMinus.visible = minPlus.visible = sortRoute.visible = t == Tab.MISPLACED;
        essentialsToggle.visible = t == Tab.UNSORTED;
        clearLostButton.visible = t == Tab.LOST;
        shopBack.visible = shopInventory.visible = shopRestock.visible = t == Tab.SHOPS && selectedShop != null;
        stockCheckButton.visible = t == Tab.OVERVIEW;
        if (t == Tab.SEARCH && autoFocusQuery && !listFocused) setFocused(query);
        rebuild();
        if (listFocused) selected = nextSelectable(-1, 1);
    }

    // ------------------------------------------------------------ keyboard / controller navigation API

    public Tab tab() {
        return tab;
    }

    public EditBox queryBox() {
        return query;
    }

    /** Give the search box focus (switching to the Search tab if needed) so typing or an on-screen keyboard reaches it. */
    public void focusQuery() {
        listFocused = false;
        if (tab != Tab.SEARCH) switchTab(Tab.SEARCH);
        setFocused(query);
    }

    public boolean listFocused() {
        return listFocused;
    }

    public int rowCount() {
        return rows == null ? 0 : rows.size(); // null while the Screen base constructor runs
    }

    public int selectedRow() {
        return listFocused ? selected : -1;
    }

    /** Move keyboard/controller focus into or out of the result list. Entering selects the first clickable row. */
    public void setListFocused(boolean focused) {
        listFocused = focused;
        if (focused && rows != null) {
            clearFocus();
            if (selected < 0 || selected >= rows.size() || rows.get(selected).onClick == null) selected = nextSelectable(-1, 1);
            ensureSelectedVisible();
        }
    }

    /** Move the selection by {@code delta} clickable rows. Returns false when already at the end. */
    public boolean moveSelection(int delta) {
        if (!listFocused || rows == null || rows.isEmpty()) return false;
        int next = nextSelectable(selected, delta < 0 ? -1 : 1);
        if (next < 0) return false;
        for (int i = 1; i < Math.abs(delta); i++) {
            int n = nextSelectable(next, delta < 0 ? -1 : 1);
            if (n < 0) break;
            next = n;
        }
        selected = next;
        ensureSelectedVisible();
        return true;
    }

    /** Page up/down: as many rows as fit in the list. */
    public boolean pageSelection(int pages) {
        int perPage = Math.max(1, (listBottom - listTop) / ROW_H - 1);
        return moveSelection(pages * perPage);
    }

    /** Click the selected row. */
    public void activateSelected(boolean shift) {
        if (!listFocused || selected < 0 || selected >= rows.size()) return;
        Row r = rows.get(selected);
        if (r.onClick != null) r.onClick.run(shift);
    }

    /** Switch to the previous/next tab (wraps). */
    public void switchTabBy(int dir) {
        Tab[] all = Tab.values();
        switchTab(all[Math.floorMod(tab.ordinal() + dir, all.length)]);
    }

    private int nextSelectable(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) if (rows.get(i).onClick != null) return i;
        return -1;
    }

    private void ensureSelectedVisible() {
        if (selected < 0) return;
        int top = selected * ROW_H;
        int viewH = listBottom - listTop;
        if (top < scroll) scroll = top;
        else if (top + ROW_H > scroll + viewH) scroll = top + ROW_H - viewH;
        int maxScroll = Math.max(0, rows.size() * ROW_H - viewH);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    @Override
    public void tick() {
        super.tick();
        if (++rebuildIn >= 20) {
            rebuildIn = 0;
            if (tab != Tab.SEARCH) rebuild();
        }
    }

    // ------------------------------------------------------------ data

    private void rebuild() {
        rows.clear();
        footer = "";
        switch (tab) {
            case SEARCH -> buildSearch();
            case UNSORTED -> buildUnsorted();
            case LOST -> buildLost();
            case MISPLACED -> buildMisplaced();
            case PLAN -> buildPlan();
            case CONDENSE -> buildCondense();
            case OVERVIEW -> buildOverview();
            case SHOPS -> buildShops();
        }
        int maxScroll = Math.max(0, rows.size() * ROW_H - (listBottom - listTop));
        scroll = Math.min(scroll, maxScroll);
        if (selected >= rows.size()) selected = nextSelectable(rows.size(), -1);
    }

    private record Agg(ItemKey key, String name, int total, Map<ContainerEntry, Integer> where, long newest) {}

    private List<Agg> aggregate() {
        Map<ItemKey, Agg> map = new LinkedHashMap<>();
        for (ContainerEntry e : WarehouseClient.index().all()) {
            for (StackRecord s : e.contents) {
                add(map, s, e);
                if (s.nested != null) for (StackRecord n : s.nested) add(map, n, e);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static void add(Map<ItemKey, Agg> map, StackRecord s, ContainerEntry e) {
        Agg a = map.get(s.key);
        if (a == null) {
            a = new Agg(s.key, s.displayName != null ? s.displayName : s.key.itemId, 0, new HashMap<>(), 0);
            map.put(s.key, a);
        }
        a.where().merge(e, s.count, Integer::sum);
        map.put(s.key, new Agg(a.key(), a.name(), a.total() + s.count, a.where(), Math.max(a.newest(), e.lastSeenEpochMs)));
    }

    private void buildSearch() {
        String q = lastQuery.trim().toLowerCase(Locale.ROOT);
        List<Agg> all = aggregate();
        CategoryResolver cats = WarehouseClient.categories();
        List<Agg> hits = new ArrayList<>();
        for (Agg a : all) {
            if (q.isEmpty()) {
                hits.add(a);
                continue;
            }
            String cat = cats.categoryOf(a.key()).toLowerCase(Locale.ROOT);
            ItemGroup g = cats.groupFor(a.key());
            if (a.name().toLowerCase(Locale.ROOT).contains(q) || a.key().itemId.contains(q) || cat.contains(q)
                    || (g != null && (g.groupId.contains(q) || g.displayName.toLowerCase(Locale.ROOT).contains(q)))) hits.add(a);
        }
        hits.sort(Comparator.comparingInt(Agg::total).reversed().thenComparing(Agg::name));
        for (Agg a : hits) {
            ContainerEntry best = null;
            int bestCount = -1;
            for (Map.Entry<ContainerEntry, Integer> en : a.where().entrySet()) {
                if (en.getValue() > bestCount || (en.getValue() == bestCount && en.getKey().lastSeenEpochMs > best.lastSeenEpochMs)) {
                    best = en.getKey();
                    bestCount = en.getValue();
                }
            }
            final ContainerEntry target = best;
            final int targetCount = bestCount;
            Region r = best != null ? WarehouseClient.regions().byId(best.regionId) : null;
            String loc = best == null ? "" : (r != null ? r.name + " @ " : "") + best.posString() + (a.where().size() > 1 ? "  (+" + (a.where().size() - 1) + " more)" : "");
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(a.name()).withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal(a.key().asString()).withStyle(ChatFormatting.DARK_GRAY));
            tip.add(Component.literal("Category: " + cats.categoryOf(a.key())).withStyle(ChatFormatting.GRAY));
            List<Map.Entry<ContainerEntry, Integer>> places = new ArrayList<>(a.where().entrySet());
            places.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
            int shown = 0;
            for (Map.Entry<ContainerEntry, Integer> en : places) {
                if (shown++ >= 8) {
                    tip.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                tip.add(Component.literal("  " + en.getKey().label() + "  ×" + en.getValue() + "  " + Staleness.describe(en.getKey().lastSeenEpochMs)).withStyle(Staleness.formatting(en.getKey().lastSeenEpochMs)));
            }
            Organizer.Resolution res = WarehouseClient.organizer().resolve(a.key());
            if (res != null) tip.add(Component.literal("Belongs in: " + res.category() + " @ " + res.container().posString()).withStyle(ChatFormatting.GREEN));
            tip.add(Component.literal("Click: highlight best location. Shift-click: highlight all.").withStyle(ChatFormatting.DARK_GRAY));
            rows.add(new Row(icon(a.key()), a.name(), loc, "×" + a.total() + "  " + Staleness.describe(a.newest()), Staleness.color(a.newest()), shift -> {
                if (target == null) return;
                if (shift) {
                    for (ContainerEntry e : a.where().keySet()) WarehouseClient.highlights().container(e, a.name() + " ×" + a.where().get(e));
                } else {
                    WarehouseClient.highlights().container(target, a.name() + " ×" + targetCount);
                }
                Chat.info("Highlighted " + a.name() + " for " + ConfigIO.get().highlightSeconds + " s.");
                onClose();
            }, tip, false));
        }
        footer = hits.size() + " item type(s) across " + WarehouseClient.index().all().size() + " containers";
    }

    private void buildUnsorted() {
        if (minecraft == null || minecraft.player == null) return;
        var inv = minecraft.player.getInventory();
        var mode = WarehouseClient.clearMode();
        boolean planActive = WarehouseClient.organizer().plan().isActive();
        int shown = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            ItemKey key = Fingerprinter.key(st);
            boolean essential = mode.isEssential(minecraft, st, i);
            if (essential && !mode.showEssentials()) continue;
            List<ContainerEntry> holders = WarehouseClient.index().holding(key);
            Organizer.Resolution res = WarehouseClient.organizer().resolve(key);
            if (!essential && (!holders.isEmpty() || res != null) && !mode.showEssentials()) {
                // has a home -> not "unsorted"; still list it when the user asked to see everything
                continue;
            }
            String detail = essential ? "essential (kept)" : holders.isEmpty() && res == null ? (planActive ? "no home: " + WarehouseClient.categories().categoryOf(key) + " zone has no room" : "no known home") : "has a home";
            final ContainerEntry target = res != null ? res.container() : (holders.isEmpty() ? null : holders.get(0));
            List<Component> tip = List.of(Component.literal(key.asString()).withStyle(ChatFormatting.DARK_GRAY), Component.literal("Category: " + WarehouseClient.categories().categoryOf(key)).withStyle(ChatFormatting.GRAY));
            rows.add(new Row(st, Fingerprinter.displayName(st), detail, "×" + st.getCount(), essential ? 0xFF909090 : 0xFFE0A050, target == null ? null : shift -> {
                WarehouseClient.highlights().container(target, Fingerprinter.displayName(st));
                onClose();
            }, tip, false));
            shown++;
        }
        footer = shown + " stack(s) listed" + (mode.showEssentials() ? " (including essentials and sorted items)" : "") + (planActive ? "" : "  — no accepted plan");
    }

    private void buildLost() {
        List<LostEntry> entries = WarehouseClient.lostLog().entries();
        for (LostEntry e : entries) {
            String where = e.dimension.replace("minecraft:", "") + " " + (int) e.pos.x + ", " + (int) e.pos.y + ", " + (int) e.pos.z;
            List<Component> tip = List.of(Component.literal(e.key.asString()).withStyle(ChatFormatting.DARK_GRAY), Component.literal("Click to highlight the spot").withStyle(ChatFormatting.GRAY));
            rows.add(new Row(icon(e.key), e.displayName, e.reason.toLowerCase(Locale.ROOT).replace('_', ' ') + " @ " + where, "×" + e.count + "  " + Staleness.describe(e.whenEpochMs), Staleness.color(e.whenEpochMs), shift -> {
                WarehouseClient.highlights().spot(e.dimension, e.pos, 0xFFFF6060, ConfigIO.get().highlightSeconds, e.displayName + " ×" + e.count);
                onClose();
            }, tip, false));
        }
        footer = entries.size() + " lost entr" + (entries.size() == 1 ? "y" : "ies") + "; entries expire after " + ConfigIO.get().lostExpiryMinutes + " min (" + ConfigIO.get().deathLostExpiryMinutes + " for deaths)";
    }

    private void buildMisplaced() {
        PlanDiff diff = WarehouseClient.planDiff();
        if (!WarehouseClient.organizer().plan().isActive()) {
            rows.add(new Row(null, "No accepted plan", "Use the Plan tab: Run, then Accept.", "", 0xFFFFFFFF, null, List.of(), true));
            return;
        }
        List<PlanDiff.Misplaced> list = diff.entries();
        UUID lastSource = null;
        int count = 0;
        for (PlanDiff.Misplaced m : list) {
            if (m.count() < misplacedMin) continue;
            count++;
            if (!m.source().id.equals(lastSource)) {
                lastSource = m.source().id;
                final ContainerEntry src = m.source();
                rows.add(new Row(null, src.label() + "  [" + m.sourceZone() + "]", "", "", 0xFFFFFFFF, shift -> {
                    WarehouseClient.highlights().container(src, "source");
                    onClose();
                }, List.of(Component.literal("Click to highlight this chest")), true));
            }
            List<Component> tip = List.of(Component.literal(m.key().asString()).withStyle(ChatFormatting.DARK_GRAY), Component.literal("Click: highlight both chests with an arrow between them").withStyle(ChatFormatting.GRAY));
            rows.add(new Row(icon(m.key()), m.displayName(), "→ " + m.destZone() + " @ " + m.dest().posString(), "×" + m.count(), 0xFFFF8080, shift -> {
                WarehouseClient.highlights().pair(m.source(), m.dest(), m.displayName());
                onClose();
            }, tip, false));
        }
        footer = count + " misplaced stack(s), min count " + misplacedMin + "   |   " + diff.unopenedSincePlan() + " chest(s) not opened since plan";
    }

    private void buildPlan() {
        Organizer org = WarehouseClient.organizer();
        Plan pending = org.pending();
        Plan active = org.plan();
        if (pending != null) {
            rows.add(new Row(null, "Pending plan (not accepted)", pending.zones.size() + " zones, " + pending.chestBudget + " chests", "", 0xFFFFD040, null, List.of(), true));
            addPlanRows(org, pending);
        }
        if (active.isActive()) {
            rows.add(new Row(null, "Active plan", active.zones.size() + " zones, " + active.chestBudget + " chests, created " + Staleness.describe(active.createdEpochMs), "", 0xFF80FF80, null, List.of(), true));
            addPlanRows(org, active);
        } else if (pending == null) {
            rows.add(new Row(null, "No plan yet", "Stand near your warehouse entrance and press Run.", "", 0xFFFFFFFF, null, List.of(), true));
        }
        footer = "Preview " + (org.previewEnabled() ? "on" : "off") + "   |   manual chest count: " + (active.manualChestCount > 0 ? active.manualChestCount : "auto") + "   |   /warehouse plan ... for commands";
    }

    private void addPlanRows(Organizer org, Plan p) {
        for (Organizer.ZoneSummary z : org.summarize(p)) {
            int color = org.categoryColor(z.category());
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(z.category()).withStyle(ChatFormatting.WHITE));
            for (UUID id : p.zones.get(z.category()).containerIds) {
                ContainerEntry e = WarehouseClient.index().byId(id);
                if (e != null) tip.add(Component.literal("  " + e.label() + "  " + e.usedSlots() + "/" + (e.slotCount > 0 ? e.slotCount : "?") + " slots").withStyle(ChatFormatting.GRAY));
                if (tip.size() > 14) {
                    tip.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
            }
            tip.add(Component.literal("Click to highlight this zone").withStyle(ChatFormatting.DARK_GRAY));
            rows.add(new Row(null, "■ " + z.category(), z.chests() + " chest(s), " + z.slotsUsed() + "/" + z.slotsTotal() + " slots used" + (z.unopened() > 0 ? ", " + z.unopened() + " unopened" : ""),
                    z.slotsTotal() > 0 ? (100 * z.slotsUsed() / z.slotsTotal()) + "%" : "", color, shift -> {
                for (UUID id : p.zones.get(z.category()).containerIds) {
                    ContainerEntry e = WarehouseClient.index().byId(id);
                    if (e != null) WarehouseClient.highlights().container(e, color, ConfigIO.get().highlightSeconds, null, false, "zone");
                }
                onClose();
            }, tip, false));
        }
    }

    private void buildCondense() {
        var condenser = WarehouseClient.condenser();
        List<dev.warehouse.organizer.Condenser.ZoneReport> reports = condenser.reports();
        int zonesLow = 0, totalFreed = 0;
        for (var r : reports) {
            if (r.suggestions().isEmpty() && !r.lowOnSpace()) continue;
            if (r.lowOnSpace()) zonesLow++;
            totalFreed += r.totalSlotsFreed();
            String state = r.lowOnSpace() ? "low on space" : "ok";
            String detail = r.slotsUsed() + "/" + r.slotsTotal() + " slots used, " + state
                    + (r.suggestions().isEmpty() ? "; nothing worth packing" : "; packing " + r.suggestions().size() + " item type(s) would free " + r.totalSlotsFreed() + " slots");
            rows.add(new Row(null, r.zone() + "  " + r.percent() + "% full", detail, "", r.lowOnSpace() ? 0xFFFFD040 : 0xFF80FF80, null, List.of(), true));
            for (var sg : r.suggestions()) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(sg.displayName()).withStyle(ChatFormatting.WHITE));
                tip.add(Component.literal(sg.key().asString()).withStyle(ChatFormatting.DARK_GRAY));
                tip.add(Component.literal(sg.slotsNow() + " slots across " + sg.sources().size() + " chest(s) → " + sg.shulkersNeeded() + " shulker box" + (sg.shulkersNeeded() == 1 ? "" : "es") + ", frees " + sg.slotsFreed() + " slots").withStyle(ChatFormatting.GRAY));
                int shown = 0;
                for (ContainerEntry e : sg.sources()) {
                    if (shown++ >= 6) {
                        tip.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
                        break;
                    }
                    tip.add(Component.literal("  " + e.label() + "  " + sg.slotsPerSource().getOrDefault(e.id, 0) + " slot(s)").withStyle(Staleness.formatting(e.lastSeenEpochMs)));
                }
                if (sg.putBack() != null) tip.add(Component.literal("Then put the shulker back in " + sg.putBack().label()).withStyle(ChatFormatting.GREEN));
                tip.add(Component.literal("Click: highlight those chests. Shift-click: only the biggest one.").withStyle(ChatFormatting.DARK_GRAY));
                String where = sg.sources().isEmpty() ? "" : sg.sources().get(0).posString() + (sg.sources().size() > 1 ? "  (+" + (sg.sources().size() - 1) + " more)" : "");
                rows.add(new Row(icon(sg.key()), sg.displayName(), sg.slotsNow() + " slots → " + sg.shulkersNeeded() + " shulker" + (sg.shulkersNeeded() == 1 ? "" : "s") + ", frees " + sg.slotsFreed() + "   " + where,
                        "×" + sg.total(), 0xFFE0C060, shift -> {
                    int secs = ConfigIO.get().highlightSeconds;
                    List<ContainerEntry> targets = shift ? sg.sources().subList(0, Math.min(1, sg.sources().size())) : sg.sources();
                    for (ContainerEntry e : targets) {
                        WarehouseClient.highlights().containerForItem(e, sg.key(), ConfigIO.get().highlightColor, secs, sg.displayName() + "  " + sg.slotsPerSource().getOrDefault(e.id, 0) + " slot(s)", e == targets.get(0), "condense");
                    }
                    if (sg.putBack() != null && !targets.contains(sg.putBack())) WarehouseClient.highlights().container(sg.putBack(), 0xFF60FF80, secs, "put shulker here", false, "condense");
                    Chat.info("Highlighted " + targets.size() + " chest(s) holding " + sg.displayName() + ". Pack it into " + sg.shulkersNeeded() + " shulker" + (sg.shulkersNeeded() == 1 ? "" : "s") + (sg.putBack() != null ? " and put it back in " + sg.putBack().label() : "") + ".");
                    onClose();
                }, tip, false));
            }
        }
        if (rows.isEmpty()) {
            rows.add(new Row(null, "Nothing worth packing into shulkers", "Zones are under " + ConfigIO.get().condenseAtFillPercent + "% full or no item takes up " + ConfigIO.get().condenseMinStacks + "+ slots.", "", 0xFFFFFFFF, null, List.of(), true));
        }
        int carried = minecraft != null ? condenser.emptyShulkersInInventory(minecraft) : 0;
        var stored = condenser.emptyShulkersIndexed();
        footer = zonesLow + " zone(s) low on space, " + totalFreed + " slots recoverable   |   empty shulkers: " + carried + " on you, " + stored.count() + " indexed"
                + (stored.where() != null && stored.count() > 0 ? " (" + stored.where().posString() + ")" : "");
    }

    // ------------------------------------------------------------ overview

    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(20, Math.round(percent / 5f)));
        return "\u2588".repeat(filled) + "\u2591".repeat(20 - filled);
    }

    private void link(String name, String detail, String right, int rightColor, Tab target, @Nullable ItemStack icon) {
        rows.add(new Row(icon, name, detail, right, rightColor, shift -> switchTab(target), List.of(Component.literal("Open the " + label(target) + " tab").withStyle(ChatFormatting.DARK_GRAY)), false));
    }

    private void header(String name, String detail, int color) {
        rows.add(new Row(null, name, detail, "", color, null, List.of(), true));
    }

    private void buildOverview() {
        var index = WarehouseClient.index();
        var val = WarehouseClient.valuation();
        long now = System.currentTimeMillis();
        int containers = 0, unopened = 0, stale = 0;
        for (ContainerEntry e : index.all()) {
            Region r = WarehouseClient.regions().byId(e.regionId);
            if (r == null || r.type != dev.warehouse.region.RegionType.WAREHOUSE) continue;
            containers++;
            if (e.lastSeenEpochMs == 0) unopened++;
            else if (now - e.lastSeenEpochMs > 7L * 86_400_000L) stale++;
        }
        Map<ItemKey, Long> counts = val.regionCounts(dev.warehouse.region.RegionType.WAREHOUSE);
        long items = 0;
        for (long c : counts.values()) items += c;
        header("Warehouse at a glance", WarehouseClient.regions().ofType(dev.warehouse.region.RegionType.WAREHOUSE).size() + " region(s)", 0xFF40C8FF);
        link(containers + " containers", (unopened > 0 ? unopened + " never opened, " : "") + stale + " not seen in 7 days", counts.size() + " types, " + items + " items", 0xFFDDDDDD, Tab.SEARCH, null);
        var check = WarehouseClient.stockCheck();
        if (check.isActive()) link(check.label(), "open the highlighted containers", "", 0xFFFFD060, Tab.OVERVIEW, null);

        Organizer org = WarehouseClient.organizer();
        Plan plan = org.plan();
        if (plan.isActive()) {
            List<Organizer.ZoneSummary> zones = new ArrayList<>(org.summarize(plan));
            zones.sort((a, b) -> Integer.compare(pct(b), pct(a)));
            header("Zones", zones.size() + " zones over " + plan.chestBudget + " chests, plan from " + Staleness.describe(plan.createdEpochMs), 0xFF80FF80);
            for (Organizer.ZoneSummary z : zones) {
                int p = pct(z);
                int color = org.categoryColor(z.category());
                rows.add(new Row(null, "\u25a0 " + z.category(), bar(p) + "  " + z.slotsUsed() + "/" + z.slotsTotal() + " slots, " + z.chests() + " chest(s)", p + "%",
                        p >= 90 ? 0xFFFF6060 : p >= ConfigIO.get().condenseAtFillPercent ? 0xFFFFD040 : color, shift -> switchTab(Tab.PLAN),
                        List.of(Component.literal(z.category()).withStyle(ChatFormatting.WHITE), Component.literal("Open the Plan tab").withStyle(ChatFormatting.DARK_GRAY)), false));
            }
        } else {
            link("No accepted plan", org.pending() != null ? "a pending plan is waiting for Accept" : "Plan tab: Run, then Accept", "", 0xFFFFD040, Tab.PLAN, null);
        }

        header("Needs attention", "", 0xFFFFD040);
        int misplaced = plan.isActive() ? WarehouseClient.planDiff().entries().size() : 0;
        link("Misplaced stacks", plan.isActive() ? WarehouseClient.planDiff().unopenedSincePlan() + " chest(s) not opened since the plan" : "needs an accepted plan", String.valueOf(misplaced), misplaced > 0 ? 0xFFFF8080 : 0xFF80FF80, Tab.MISPLACED, null);
        int lost = WarehouseClient.lostLog().entries().size();
        link("Lost items", "drops and deaths still remembered", String.valueOf(lost), lost > 0 ? 0xFFFF8080 : 0xFF80FF80, Tab.LOST, null);
        var urgent = WarehouseClient.condenser().urgent();
        int freeable = 0;
        for (var r : urgent) freeable += r.totalSlotsFreed();
        link("Zones low on space", urgent.isEmpty() ? "nothing to condense" : "packing into shulkers would free " + freeable + " slots", String.valueOf(urgent.size()), urgent.isEmpty() ? 0xFF80FF80 : 0xFFFFD040, Tab.CONDENSE, null);
        int unsorted = 0;
        if (minecraft != null && minecraft.player != null) {
            var inv = minecraft.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty() || WarehouseClient.clearMode().isEssential(minecraft, st, i)) continue;
                ItemKey k = Fingerprinter.key(st);
                if (WarehouseClient.index().holding(k).isEmpty() && org.resolve(k) == null) unsorted++;
            }
        }
        link("Inventory stacks without a home", "", String.valueOf(unsorted), unsorted > 0 ? 0xFFFFD040 : 0xFF80FF80, Tab.UNSORTED, null);

        header("Value", "median " + ConfigIO.get().valuationSide + " prices; /warehouse price to add some", 0xFFFFD060);
        dev.warehouse.prices.Valuation.Total wh = val.total(counts);
        List<dev.warehouse.prices.Valuation.Unpriced> unpriced = val.unpriced(counts, 3);
        StringBuilder up = new StringBuilder();
        for (var u : unpriced) up.append(up.length() == 0 ? "" : ", ").append(u.name()).append(" ×").append(u.count());
        link("Warehouse", wh.pricedTypes() + " priced type(s)" + (wh.unpricedTypes() > 0 ? ", " + wh.unpricedTypes() + " unpriced: " + up : ""), dev.warehouse.prices.Valuation.money(wh.amount()), 0xFFFFD060, Tab.SEARCH, null);
        dev.warehouse.prices.Valuation.Total inv = minecraft != null ? val.inventoryValue(minecraft) : new dev.warehouse.prices.Valuation.Total(0, 0, 0, 0);
        link("Your inventory", inv.unpricedTypes() > 0 ? inv.unpricedTypes() + " unpriced type(s)" : "", dev.warehouse.prices.Valuation.money(inv.amount()), 0xFFFFD060, Tab.UNSORTED, null);
        int priceCount = WarehouseClient.prices().all().size();
        link("Prices known", priceCount + " observation(s); shop chat and signs are recorded automatically", "", 0xFFAAAAAA, Tab.SEARCH, null);

        var shops = WarehouseClient.shops().all();
        if (!shops.isEmpty()) {
            header("Shops", shops.size() + " shop(s)", ConfigIO.get().shopColor);
            for (var shop : shops) {
                var rep = WarehouseClient.shops().report(shop);
                final java.util.UUID id = shop.regionId;
                String detail = rep.items().size() + " item type(s), last inventory " + (shop.lastInventoryEpochMs == 0 ? "never" : Staleness.describe(shop.lastInventoryEpochMs)) + (WarehouseClient.shops().overdue(shop) ? "  (inventory due)" : "");
                rows.add(new Row(null, WarehouseClient.shops().name(shop), detail, dev.warehouse.prices.Valuation.money(rep.value()) + (rep.low() + rep.out() > 0 ? "  " + rep.low() + " low, " + rep.out() + " out" : ""),
                        rep.out() > 0 ? 0xFFFF8080 : rep.low() > 0 ? 0xFFFFD040 : 0xFF80FF80, shift -> {
                    selectedShop = id;
                    switchTab(Tab.SHOPS);
                }, List.of(Component.literal("Open this shop's report").withStyle(ChatFormatting.DARK_GRAY)), false));
            }
        }
        footer = "Click a line to open its tab   |   Stock check lights up every warehouse container until you open it";
    }

    private static int pct(Organizer.ZoneSummary z) {
        return z.slotsTotal() > 0 ? 100 * z.slotsUsed() / z.slotsTotal() : 0;
    }

    // ------------------------------------------------------------ shops

    private void buildShops() {
        var shops = WarehouseClient.shops();
        var shop = shops.byId(selectedShop);
        if (shop == null) {
            selectedShop = null;
            shopBack.visible = shopInventory.visible = shopRestock.visible = false;
            var all = shops.all();
            if (all.isEmpty()) {
                header("No shops yet", "/warehouse shop wand, then draw the shop with the wand like a warehouse region. Chests inside are indexed as you open them.", 0xFFFFFFFF);
                footer = "Shops are regions of type shop; /warehouse region type <name> shop converts an existing one";
                return;
            }
            for (var s : all) {
                var rep = shops.report(s);
                final java.util.UUID id = s.regionId;
                String detail = rep.containers() + " container(s), " + rep.items().size() + " item type(s), last inventory " + (s.lastInventoryEpochMs == 0 ? "never" : Staleness.describe(s.lastInventoryEpochMs)) + (shops.overdue(s) ? "  (inventory due)" : "");
                rows.add(new Row(null, shops.name(s), detail, dev.warehouse.prices.Valuation.money(rep.value()) + (rep.low() + rep.out() > 0 ? "  " + rep.low() + " low, " + rep.out() + " out" : ""),
                        rep.out() > 0 ? 0xFFFF8080 : rep.low() > 0 ? 0xFFFFD040 : 0xFF80FF80, shift -> {
                    selectedShop = id;
                    scroll = 0;
                    rebuild();
                    shopBack.visible = shopInventory.visible = shopRestock.visible = true;
                }, List.of(Component.literal("Click for the stock report").withStyle(ChatFormatting.DARK_GRAY)), false));
            }
            footer = all.size() + " shop(s)   |   /warehouse shop inventory while standing in one starts the walk-through count";
            return;
        }
        shopBack.visible = shopInventory.visible = shopRestock.visible = true;
        var rep = shops.report(shop);
        String since = rep.sinceEpochMs() == 0 ? "no inventory recorded yet" : "since the inventory " + Staleness.describe(rep.sinceEpochMs()) + ": about " + dev.warehouse.prices.Valuation.money(rep.revenueSinceLast()) + " sold";
        header(shops.name(shop) + "  \u2014  " + dev.warehouse.prices.Valuation.money(rep.value()), rep.containers() + " container(s), " + rep.items().size() + " item type(s), " + rep.low() + " low, " + rep.out() + " out  \u00b7  " + since, ConfigIO.get().shopColor);
        for (var i : rep.items()) {
            String status = switch (i.status()) {
                case OUT -> "OUT";
                case LOW -> "LOW";
                case OK -> "ok";
            };
            int color = switch (i.status()) {
                case OUT -> 0xFFFF6060;
                case LOW -> 0xFFFFD040;
                case OK -> 0xFF80FF80;
            };
            String price = i.sellPrice() == null ? "no price" : dev.warehouse.prices.Valuation.money(i.sellPrice()) + (i.marketPrice() ? " (market)" : "");
            String detail = "stock " + i.count() + " / min " + i.threshold() + "  \u00b7  " + price + (i.soldSinceLast() > 0 ? "  \u00b7  sold " + i.soldSinceLast() + " since last" : "");
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(i.name()).withStyle(ChatFormatting.WHITE));
            tip.add(Component.literal("Most ever stocked: " + i.maxSeen() + "; threshold " + i.threshold() + (shop.restockMin.containsKey(dev.warehouse.prices.PriceKeys.of(i.key())) ? " (set by you)" : " (25% of max; /warehouse shop min <n> while holding it)")).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal(i.sellPrice() == null ? "Set the shop price: hold it and /warehouse shop price <amount>" : "Worth " + dev.warehouse.prices.Valuation.money(i.value()) + " at " + price).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("Click: highlight the shop chests holding it. Shift-click: also the warehouse chests.").withStyle(ChatFormatting.DARK_GRAY));
            final var key = i.key();
            final String name = i.name();
            rows.add(new Row(icon(key), i.name(), detail, status + "  " + dev.warehouse.prices.Valuation.money(i.value()), color, sh -> {
                int secs = ConfigIO.get().highlightSeconds;
                int n = 0;
                for (ContainerEntry e : shops.containers(shop)) {
                    if (e.totalCount(key) > 0) {
                        WarehouseClient.highlights().containerForItem(e, key, ConfigIO.get().shopColor, secs, name + " ×" + e.totalCount(key), n++ == 0, "shop");
                    }
                }
                if (sh) for (ContainerEntry e : WarehouseClient.index().holding(key)) {
                    Region r = WarehouseClient.regions().byId(e.regionId);
                    if (r != null && r.type == dev.warehouse.region.RegionType.WAREHOUSE) WarehouseClient.highlights().containerForItem(e, key, ConfigIO.get().highlightColor, secs, "warehouse ×" + e.totalCount(key), false, "shop");
                }
                Chat.info(n == 0 ? "No shop chest holds " + name + " right now." : "Highlighted " + n + " shop chest(s) holding " + name + ".");
                onClose();
            }, tip, false));
        }
        footer = "Do inventory: walk every container; Restock route: fetch low items from the warehouse   |   /warehouse shop price <amount> sets a sell price for the held item";
    }

    private static @Nullable ItemStack icon(ItemKey key) {
        return CategoryResolver.sampleStack(key);
    }

    // ------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.text(getFont(), "Warehouse", 20, 8, 0xFFFFFFFF);
        String server = WarehouseClient.storage().serverKey();
        if (server != null) g.text(getFont(), server, 20 + getFont().width("Warehouse") + 8, 8, 0xFF808080);
        if (tab == Tab.MISPLACED) g.text(getFont(), "min ×" + misplacedMin, 42, 49, 0xFFFFFFFF);

        g.fill(listLeft, listTop - 1, listRight, listBottom + 1, 0x60000000);
        g.enableScissor(listLeft, listTop, listRight, listBottom);
        int y = listTop - scroll;
        Row hovered = null;
        int selectedY = Integer.MIN_VALUE;
        for (int idx = 0; idx < rows.size(); idx++) {
            Row r = rows.get(idx);
            if (y + ROW_H >= listTop && y <= listBottom) {
                boolean hover = mouseX >= listLeft && mouseX < listRight && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);
                if (hover && r.onClick != null) g.fill(listLeft, y, listRight, y + ROW_H, 0x40FFFFFF);
                if (r.header) g.fill(listLeft, y, listRight, y + ROW_H, 0x30FFFFFF);
                if (listFocused && idx == selected) {
                    g.fill(listLeft, y, listRight, y + ROW_H, 0x50FFFFFF);
                    g.outline(listLeft, y, listRight - listLeft, ROW_H, 0xFFFFFFFF);
                    selectedY = y;
                }
                int x = listLeft + 4;
                if (r.icon != null) {
                    g.item(r.icon, x, y + 2);
                    g.itemDecorations(getFont(), r.icon, x, y + 2, "");
                }
                x += 22;
                int nameColor = r.header ? r.rightColor : 0xFFFFFFFF;
                g.text(getFont(), getFont().plainSubstrByWidth(r.name, 200), x, y + 2, nameColor);
                if (!r.detail.isEmpty()) g.text(getFont(), getFont().plainSubstrByWidth(r.detail, listRight - x - 120), x, y + 11, 0xFFA0A0A0);
                if (!r.right.isEmpty()) g.text(getFont(), r.right, listRight - 6 - getFont().width(r.right), y + 6, r.rightColor);
                if (hover) hovered = r;
            }
            y += ROW_H;
        }
        g.disableScissor();
        if (rows.isEmpty()) g.text(getFont(), tab == Tab.SEARCH && lastQuery.isEmpty() ? "Nothing indexed yet. Open chests inside a region." : "No results.", listLeft + 6, listTop + 6, 0xFF909090);
        if (hovered != null && !hovered.tooltip.isEmpty()) g.setComponentTooltipForNextFrame(getFont(), hovered.tooltip, mouseX, mouseY);
        else if (listFocused && selectedY != Integer.MIN_VALUE && selected >= 0 && selected < rows.size() && !rows.get(selected).tooltip.isEmpty()) {
            g.setComponentTooltipForNextFrame(getFont(), rows.get(selected).tooltip, listLeft + 240, Math.max(listTop, Math.min(selectedY, listBottom - ROW_H)) + ROW_H);
        }
        g.text(getFont(), footer, 20, height - 16, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        if (mx >= listLeft && mx < listRight && my >= listTop && my < listBottom) {
            int idx = (int) ((my - listTop + scroll) / ROW_H);
            if (idx >= 0 && idx < rows.size() && rows.get(idx).onClick != null) {
                selected = idx;
                rows.get(idx).onClick.run(event.hasShiftDown());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (super.mouseScrolled(x, y, scrollX, scrollY)) return true;
        int maxScroll = Math.max(0, rows.size() * ROW_H - (listBottom - listTop));
        scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * ROW_H * 2));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Open the screen; the first time per session (or always, by config) it lands on the Overview tab. */
    public static void open(Minecraft mc) {
        if (!landedThisSession || ConfigIO.get().alwaysOpenOverview) lastTab = Tab.OVERVIEW;
        landedThisSession = true;
        mc.gui.setScreen(new SearchScreen());
    }

    public static void openShop(Minecraft mc, java.util.UUID shopRegionId) {
        selectedShop = shopRegionId;
        landedThisSession = true;
        lastTab = Tab.SHOPS;
        mc.gui.setScreen(new SearchScreen());
    }

    public static void open(Minecraft mc, Tab t) {
        lastTab = t;
        landedThisSession = true;
        mc.gui.setScreen(new SearchScreen());
    }

    public static void setQuery(String q) {
        lastQuery = q;
        if (Minecraft.getInstance().gui.screen() instanceof SearchScreen s) {
            s.query.setValue(q);
            s.rebuild();
        }
    }
}
