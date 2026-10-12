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
    public enum Tab { OVERVIEW, SEARCH, CRAFT, PRICES, UNSORTED, LOST, MISPLACED, PLAN, CONDENSE, SHOPS, TOOLS }

    private static String lastQuery = "";
    private static Tab lastTab = Tab.OVERVIEW;
    private static boolean landedThisSession;
    private static java.util.@Nullable UUID selectedShop;
    /** When false the search box is not focused automatically (controller input: an add-on opens the on-screen keyboard on demand). */
    public static boolean autoFocusQuery = true;

    private interface RowAction { void run(boolean shift); }

    /** A small button drawn at the right end of a row. */
    private record RowButton(String label, RowAction action) {}

    private record Row(@Nullable ItemStack icon, String name, String detail, String right, int rightColor, @Nullable RowAction onClick, List<Component> tooltip, boolean header, List<RowButton> buttons) {
        Row(@Nullable ItemStack icon, String name, String detail, String right, int rightColor, @Nullable RowAction onClick, List<Component> tooltip, boolean header) {
            this(icon, name, detail, right, rightColor, onClick, tooltip, header, List.of());
        }

        Row withButtons(RowButton... b) {
            return new Row(icon, name, detail, right, rightColor, onClick, tooltip, header, List.of(b));
        }
    }

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
    private EditBox craftItemBox, craftCountBox, priceSearchBox, priceAmountBox, priceQtyBox, priceShopBox, toolsBox, shopAmountBox;
    private Button craftPlan, craftGather, craftClear, craftHud, priceBuy, priceSell, priceClear;
    private int controlsY = 44;
    private static @Nullable ItemStack craftSelected, priceSubject;

    public SearchScreen() {
        super(Component.literal("Warehouse"));
    }

    @Override
    protected void init() {
        tabButtons.clear();
        planButtons.clear();
        int perRow = Math.max(1, (width - 40) / 62);
        int tabRows = (int) Math.ceil(Tab.values().length / (double) perRow);
        controlsY = 22 + tabRows * 20 + 2;
        int i = 0;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.literal(label(t)), btn -> switchTab(t)).bounds(20 + (i % perRow) * 62, 22 + (i / perRow) * 20, 60, 18).build();
            tabButtons.add(addRenderableWidget(b));
            i++;
        }
        query = new EditBox(getFont(), 20, controlsY, Math.min(260, width - 140), 18, Component.literal("Search"));
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
                .bounds(width - 120, controlsY, 100, 18).build());

        int px = 20;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Run"), b -> {
            WarehouseClient.organizer().run(minecraft, false);
            rebuild();
        }).bounds(px, controlsY, 50, 18).build()));
        px += 52;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Replan"), b -> {
            WarehouseClient.organizer().run(minecraft, true);
            rebuild();
        }).bounds(px, controlsY, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Accept"), b -> {
            if (WarehouseClient.organizer().accept()) Chat.info("Plan accepted.");
            rebuild();
        }).bounds(px, controlsY, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Reject"), b -> {
            WarehouseClient.organizer().reject();
            rebuild();
        }).bounds(px, controlsY, 54, 18).build()));
        px += 56;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Preview"), b -> {
            WarehouseClient.organizer().setPreview(!WarehouseClient.organizer().previewEnabled());
            rebuild();
        }).bounds(px, controlsY, 58, 18).build()));
        px += 60;
        planButtons.add(addRenderableWidget(Button.builder(Component.literal("Export"), b -> {
            String name = WarehouseClient.exporter().export(null, false);
            if (name != null) Chat.info("Exported to " + name);
        }).bounds(px, controlsY, 54, 18).build()));

        minMinus = addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            misplacedMin = Math.max(1, misplacedMin - 1);
            rebuild();
        }).bounds(20, controlsY, 18, 18).build());
        minPlus = addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            misplacedMin = Math.min(64, misplacedMin + (misplacedMin >= 16 ? 16 : 1));
            rebuild();
        }).bounds(100, controlsY, 18, 18).build());
        sortRoute = addRenderableWidget(Button.builder(Component.literal("Sort route"), b -> {
            WarehouseClient.clearMode().toggle(minecraft, dev.warehouse.modes.ClearInventoryMode.Kind.SORT);
            onClose();
        }).bounds(126, controlsY, 80, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Guide me chest to chest to take misplaced items and put them where they belong"))).build());
        essentialsToggle = addRenderableWidget(Button.builder(Component.literal("Show essentials"), b -> {
            WarehouseClient.clearMode().setShowEssentials(!WarehouseClient.clearMode().showEssentials());
            rebuild();
        }).bounds(20, controlsY, 110, 18).build());
        clearLostButton = addRenderableWidget(Button.builder(Component.literal("Clear lost log"), b -> {
            WarehouseClient.lostLog().clear();
            rebuild();
        }).bounds(20, controlsY, 100, 18).build());
        shopBack = addRenderableWidget(Button.builder(Component.literal("All shops"), b -> {
            selectedShop = null;
            scroll = 0;
            rebuild();
        }).bounds(20, controlsY, 70, 18).build());
        shopInventory = addRenderableWidget(Button.builder(Component.literal("Do inventory"), b -> {
            var shop = WarehouseClient.shops().byId(selectedShop);
            if (shop != null) {
                WarehouseClient.stockCheck().start(minecraft, dev.warehouse.shops.StockCheck.Kind.SHOP, shop);
                onClose();
            }
        }).bounds(92, controlsY, 86, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Light up every container in the shop; each one unhighlights when you open it. The report opens when all are counted."))).build());
        shopRestock = addRenderableWidget(Button.builder(Component.literal("Restock route"), b -> {
            var shop = WarehouseClient.shops().byId(selectedShop);
            if (shop != null) {
                WarehouseClient.clearMode().startRestock(minecraft, shop);
                onClose();
            }
        }).bounds(180, controlsY, 90, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Pick up low items from the warehouse and deliver them to the shop"))).build());
        stockCheckButton = addRenderableWidget(Button.builder(Component.literal("Stock check"), b -> {
            WarehouseClient.stockCheck().start(minecraft, dev.warehouse.shops.StockCheck.Kind.WAREHOUSE, null);
            onClose();
        }).bounds(20, controlsY, 90, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Light up every warehouse container and walk them; each unhighlights when opened"))).build());

        listLeft = 20;
        listRight = width - 20;
        // ---- Craft tab
        craftItemBox = addRenderableWidget(new EditBox(getFont(), 20, controlsY, 150, 18, Component.literal("Item")));
        craftItemBox.setHint(Component.literal("item name to craft").withStyle(ChatFormatting.DARK_GRAY));
        craftItemBox.setResponder(v -> {
            scroll = 0;
            rebuild();
        });
        craftCountBox = addRenderableWidget(new EditBox(getFont(), 174, controlsY, 44, 18, Component.literal("Count")));
        craftCountBox.setHint(Component.literal("count").withStyle(ChatFormatting.DARK_GRAY));
        craftCountBox.setValue("64");
        craftPlan = addRenderableWidget(Button.builder(Component.literal("Plan"), b -> planCraft(null)).bounds(222, controlsY, 44, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Plan the selected item, or the one in your hand, in the given count"))).build());
        craftGather = addRenderableWidget(Button.builder(Component.literal("Gather"), b -> gatherCraft()).bounds(268, controlsY, 54, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Route through the warehouse chests holding what you still lack"))).build());
        craftClear = addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            WarehouseClient.craftPlanner().clear();
            craftSelected = null;
            rebuild();
        }).bounds(324, controlsY, 46, 18).build());
        craftHud = addRenderableWidget(Button.builder(Component.literal("HUD"), b -> {
            WarehouseClient.craftPlanner().setHudVisible(!WarehouseClient.craftPlanner().hudVisible());
            rebuild();
        }).bounds(372, controlsY, 40, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Show or hide the pinned shopping list"))).build());

        // ---- Prices tab
        priceSearchBox = addRenderableWidget(new EditBox(getFont(), 20, controlsY, 110, 18, Component.literal("Item")));
        priceSearchBox.setHint(Component.literal("pick an item").withStyle(ChatFormatting.DARK_GRAY));
        priceSearchBox.setResponder(v -> {
            scroll = 0;
            rebuild();
        });
        priceAmountBox = addRenderableWidget(new EditBox(getFont(), 134, controlsY, 56, 18, Component.literal("Amount")));
        priceAmountBox.setHint(Component.literal("amount").withStyle(ChatFormatting.DARK_GRAY));
        priceQtyBox = addRenderableWidget(new EditBox(getFont(), 194, controlsY, 34, 18, Component.literal("Qty")));
        priceQtyBox.setHint(Component.literal("qty").withStyle(ChatFormatting.DARK_GRAY));
        priceQtyBox.setValue("1");
        priceShopBox = addRenderableWidget(new EditBox(getFont(), 232, controlsY, 84, 18, Component.literal("Shop")));
        priceShopBox.setHint(Component.literal("shop (optional)").withStyle(ChatFormatting.DARK_GRAY));
        priceBuy = addRenderableWidget(Button.builder(Component.literal("Buy price"), b -> recordPrice(false)).bounds(320, controlsY, 62, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("What a shop charges you for the amount (per unit, or for the quantity given)"))).build());
        priceSell = addRenderableWidget(Button.builder(Component.literal("Sell price"), b -> recordPrice(true)).bounds(384, controlsY, 62, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("What a shop pays you"))).build());
        priceClear = addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            if (priceSubject != null) {
                int n = 0;
                for (String pk : dev.warehouse.prices.PriceKeys.candidates(Fingerprinter.key(priceSubject))) n += WarehouseClient.prices().clear(pk);
                Chat.info("Removed " + n + " price(s).");
                rebuild();
            }
        }).bounds(448, controlsY, 46, 18).build());

        // ---- Tools tab
        toolsBox = addRenderableWidget(new EditBox(getFont(), 20, controlsY, 170, 18, Component.literal("Name")));
        toolsBox.setHint(Component.literal("filter, or a new name for Rename").withStyle(ChatFormatting.DARK_GRAY));
        toolsBox.setResponder(v -> {
            scroll = 0;
            rebuild();
        });

        // ---- Shops tab
        shopAmountBox = addRenderableWidget(new EditBox(getFont(), 272, controlsY, 56, 18, Component.literal("Amount")));
        shopAmountBox.setHint(Component.literal("amount").withStyle(ChatFormatting.DARK_GRAY));

        listTop = controlsY + 24;
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
            case CRAFT -> "Craft";
            case PRICES -> "Prices";
            case TOOLS -> "Tools";
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
        shopBack.visible = shopInventory.visible = shopRestock.visible = shopAmountBox.visible = t == Tab.SHOPS && selectedShop != null;
        stockCheckButton.visible = t == Tab.OVERVIEW;
        craftItemBox.visible = craftCountBox.visible = craftPlan.visible = craftGather.visible = craftClear.visible = craftHud.visible = t == Tab.CRAFT;
        priceSearchBox.visible = priceAmountBox.visible = priceQtyBox.visible = priceShopBox.visible = priceBuy.visible = priceSell.visible = priceClear.visible = t == Tab.PRICES;
        toolsBox.visible = t == Tab.TOOLS;
        if (t == Tab.PRICES && priceSubject == null && minecraft != null && minecraft.player != null && !minecraft.player.getMainHandItem().isEmpty()) priceSubject = minecraft.player.getMainHandItem().copy();
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
            case CRAFT -> buildCraft();
            case PRICES -> buildPrices();
            case TOOLS -> buildTools();
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
            ItemStack rowIcon = icon(a.key());
            Row row = new Row(rowIcon, a.name(), loc, "×" + a.total() + "  " + Staleness.describe(a.newest()), Staleness.color(a.newest()), shift -> {
                if (target == null) return;
                if (shift) {
                    for (ContainerEntry e : a.where().keySet()) WarehouseClient.highlights().container(e, a.name() + " ×" + a.where().get(e));
                } else {
                    WarehouseClient.highlights().container(target, a.name() + " ×" + targetCount);
                }
                Chat.info("Highlighted " + a.name() + " for " + ConfigIO.get().highlightSeconds + " s.");
                onClose();
            }, tip, false);
            if (rowIcon != null) {
                row = a.key().isVanilla()
                        ? row.withButtons(new RowButton("Craft", sh -> {
                            craftSelected = rowIcon.copy();
                            switchTab(Tab.CRAFT);
                        }), new RowButton("Price", sh -> {
                            priceSubject = rowIcon.copy();
                            switchTab(Tab.PRICES);
                        }))
                        : row.withButtons(new RowButton("Price", sh -> {
                            priceSubject = rowIcon.copy();
                            switchTab(Tab.PRICES);
                        }));
            }
            rows.add(row);
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
            final boolean kept = essential;
            rows.add(new Row(st, Fingerprinter.displayName(st), detail, "×" + st.getCount(), essential ? 0xFF909090 : 0xFFE0A050, target == null ? null : shift -> {
                WarehouseClient.highlights().container(target, Fingerprinter.displayName(st));
                onClose();
            }, tip, false).withButtons(new RowButton(kept ? "Unkeep" : "Keep", sh -> {
                dev.warehouse.command.ShopCommands.setKeep(st, !kept);
                rebuild();
            })));
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

    private @Nullable Dashboard dashboard;

    private void buildOverview() {
        dashboard = Dashboard.compute(minecraft);
        footer = "Click a tile, slice, bar or chip to open its tab   |   Stock check lights up every warehouse container until you open it";
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
            tip.add(Component.literal("Most ever stocked: " + i.maxSeen() + "; threshold " + i.threshold() + (shop.restockMin.containsKey(dev.warehouse.shops.ShopManager.priceKey(i.unit())) ? " (set by you)" : " (25% of max; /warehouse shop min <n> while holding it)")).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal(i.sellPrice() == null ? "Set the shop price: hold it and /warehouse shop price <amount>" : "Worth " + dev.warehouse.prices.Valuation.money(i.value()) + " at " + price).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("Click: highlight the shop chests holding it. Shift-click: also the warehouse chests.").withStyle(ChatFormatting.DARK_GRAY));
            final var key = i.key();
            final String name = i.name();
            rows.add(new Row(icon(key), i.name(), detail, status + "  " + dev.warehouse.prices.Valuation.money(i.value()), color, sh -> {
                int secs = ConfigIO.get().highlightSeconds;
                int n = 0;
                for (ContainerEntry e : shops.containers(shop)) {
                    int here = i.unit().countIn(e, false);
                    if (here > 0) {
                        WarehouseClient.highlights().containerForItem(e, key, ConfigIO.get().shopColor, secs, name + " ×" + here, n++ == 0, "shop");
                    }
                }
                if (sh) for (ContainerEntry e : WarehouseClient.index().holding(key)) {
                    Region r = WarehouseClient.regions().byId(e.regionId);
                    if (r != null && r.type == dev.warehouse.region.RegionType.WAREHOUSE) WarehouseClient.highlights().containerForItem(e, key, ConfigIO.get().highlightColor, secs, "warehouse ×" + e.totalCount(key), false, "shop");
                }
                Chat.info(n == 0 ? "No shop chest holds " + name + " right now." : "Highlighted " + n + " shop chest(s) holding " + name + ".");
                onClose();
            }, tip, false).withButtons(
                    new RowButton("Price", sh -> {
                        double amount = parseDouble(shopAmountBox, -1);
                        if (amount < 0) {
                            Chat.error("Type the price in the amount box first.");
                            return;
                        }
                        shops.setSellPrice(shop, dev.warehouse.shops.ShopManager.priceKey(i.unit()), amount);
                        rebuild();
                    }),
                    new RowButton("Min", sh -> {
                        int min = parseInt(shopAmountBox, -1);
                        if (min < 0) {
                            Chat.error("Type the threshold in the amount box first.");
                            return;
                        }
                        shops.setRestockMin(shop, dev.warehouse.shops.ShopManager.priceKey(i.unit()), min);
                        rebuild();
                    }),
                    new RowButton("Plan", sh -> {
                        ItemStack ic = icon(key);
                        int want = Math.max(i.threshold() * 2, i.maxSeen()) - i.count();
                        if (ic == null || want <= 0) {
                            Chat.info(name + " does not need restocking.");
                            return;
                        }
                        WarehouseClient.craftPlanner().start(minecraft, "Restock " + want + " × " + name + " for " + shops.name(shop), ic,
                                List.of(new dev.warehouse.craft.CraftPlanner.Request(ic, want, i.unit().perBox, false)));
                        switchTab(Tab.CRAFT);
                    })));
        }
        footer = "Price / Min use the amount box; Plan lists what it takes to restock that item   |   Do inventory walks every container; Restock route fetches low items";
    }

    /** Screen x-ranges of a row's buttons, laid out right to left before the right-hand text. */
    private List<int[]> buttonRects(Row r) {
        if (r.buttons.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>();
        int bx = listRight - 6 - (r.right.isEmpty() ? 0 : getFont().width(r.right) + 8);
        for (int i = r.buttons.size() - 1; i >= 0; i--) {
            int bw = getFont().width(r.buttons.get(i).label()) + 8;
            bx -= bw;
            out.add(0, new int[]{bx, bx + bw});
            bx -= 4;
        }
        return out;
    }

    private static int parseInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(EditBox box, double fallback) {
        try {
            return Double.parseDouble(box.getValue().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ craft tab

    private void planCraft(@Nullable ItemStack explicit) {
        ItemStack target = explicit != null ? explicit : craftSelected != null ? craftSelected : minecraft != null && minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        if (target.isEmpty()) {
            Chat.error("Pick an item from the list, or hold it, then press Plan.");
            return;
        }
        int count = Math.max(1, parseInt(craftCountBox, 64));
        if (WarehouseClient.craftPlanner().start(minecraft, target, count)) {
            craftSelected = target.copy();
            craftItemBox.setValue("");
        }
        rebuild();
    }

    private void gatherCraft() {
        var planner = WarehouseClient.craftPlanner();
        if (planner.active() == null) {
            Chat.error("Plan something first.");
            return;
        }
        var fetch = planner.toFetch();
        if (fetch.isEmpty()) {
            Chat.info(planner.active().gathered() ? "You already carry everything." : "Nothing to gather: the warehouse has none of what is missing.");
            return;
        }
        WarehouseClient.clearMode().startFetch(minecraft, fetch, planner.fetchNames(), "Gather: " + planner.active().title());
        onClose();
    }

    private void buildCraft() {
        var planner = WarehouseClient.craftPlanner();
        var plan = planner.active();
        String q = craftItemBox.getValue().trim().toLowerCase(Locale.ROOT);
        craftHud.setMessage(Component.literal(planner.hudVisible() ? "HUD ✓" : "HUD"));
        if (q.length() >= 2) {
            int shown = 0;
            for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
                ItemStack st = new ItemStack(item);
                if (st.isEmpty()) continue;
                String name = st.getHoverName().getString();
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
                if (!name.toLowerCase(Locale.ROOT).contains(q) && !id.contains(q)) continue;
                if (shown++ >= 150) {
                    header("...", "keep typing to narrow the list", 0xFF808080);
                    break;
                }
                ItemKey key = new ItemKey(id, "");
                int stored = WarehouseClient.index().totalCount(key);
                int recipes = 0;
                for (var src : planner.sources()) recipes += src.recipesFor(item).size();
                rows.add(new Row(st, name, id + (recipes > 0 ? "  ·  " + recipes + " recipe(s)" : "  ·  no recipe known"), stored > 0 ? stored + " stored" : "", 0xFFA0A0A0, shift -> {
                    craftSelected = st;
                    planCraft(st);
                }, List.of(Component.literal("Click to plan " + craftCountBox.getValue().trim() + " of it").withStyle(ChatFormatting.DARK_GRAY)), false));
            }
            footer = "Click an item to plan it with the count above";
            return;
        }
        if (plan == null) {
            header("Plan a craft", "Type an item name above, or hold the item and press Plan. The count box says how many.", 0xFFC0A0FF);
            if (craftSelected != null) rows.add(new Row(craftSelected, "Selected: " + craftSelected.getHoverName().getString(), "press Plan", "", 0xFFFFFFFF, shift -> planCraft(craftSelected), List.of(), false));
            rows.add(new Row(null, "From a shop chest", "open the chest and press Plan refill to list everything needed to fill it, shulker contents included", "", 0xFFA0A0A0, null, List.of(), false));
            footer = "Recipes come from your recipe book" + (planner.sources().size() > 1 ? " and JEI" : "; the JEI add-on adds the rest");
            return;
        }
        String summary = plan.lines().size() + " item(s) to obtain, " + plan.steps().size() + " craft step(s)" + (plan.missingLines() > 0 ? ", " + plan.missingLines() + " missing" : plan.gathered() ? ", everything on you" : "");
        rows.add(new Row(plan.target(), plan.title(), summary, "", 0xFFC0A0FF, null, List.of(), true)
                .withButtons(new RowButton("Gather", sh -> gatherCraft()), new RowButton("Clear", sh -> {
                    planner.clear();
                    rebuild();
                })));
        for (var l : plan.lines()) {
            String where = l.where().isEmpty() ? "" : "  @ " + l.where().get(0).posString() + (l.where().size() > 1 ? " +" + (l.where().size() - 1) : "");
            String detail = "on you " + l.inventory() + " / need " + l.need() + (l.warehouse() > 0 ? "  ·  warehouse " + l.warehouse() + where : "");
            String right = l.missing() > 0 ? "missing " + l.missing() : l.satisfiedByInventory() ? "✓" : "fetch " + (l.need() - l.inventory());
            int color = l.missing() > 0 ? 0xFFFF7070 : l.satisfiedByInventory() ? 0xFF80FF80 : 0xFFFFD040;
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(l.name()).withStyle(ChatFormatting.WHITE));
            int shown = 0;
            for (ContainerEntry e : l.where()) {
                if (shown++ >= 6) break;
                tip.add(Component.literal("  " + e.label() + "  ×" + l.perChest().getOrDefault(e.id, 0)).withStyle(ChatFormatting.GRAY));
            }
            tip.add(Component.literal(l.where().isEmpty() ? "Not in the warehouse" : "Click: highlight these chests").withStyle(ChatFormatting.DARK_GRAY));
            ItemKey vk = new ItemKey(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(l.item()).toString(), "");
            rows.add(new Row(l.icon(), l.name(), detail, right, color, l.where().isEmpty() ? null : sh -> {
                int n = 0;
                for (ContainerEntry e : l.where()) WarehouseClient.highlights().containerForItem(e, vk, ConfigIO.get().highlightColor, ConfigIO.get().highlightSeconds, l.name() + " ×" + l.perChest().getOrDefault(e.id, 0), n++ == 0, "craft");
                onClose();
            }, tip, false).withButtons(new RowButton("Price", sh -> {
                priceSubject = l.icon().copy();
                switchTab(Tab.PRICES);
            })));
        }
        if (!plan.steps().isEmpty()) header("Craft steps", "", 0xFFA0A0A0);
        for (var st : plan.steps()) {
            rows.add(new Row(st.result(), st.crafts() + "× " + st.kind() + " → " + st.makes() + " " + st.name(), st.station().isEmpty() ? "" : "at " + st.station().getHoverName().getString(), "", 0xFFA0A0A0, null, List.of(), false));
        }
        footer = "Type an item name above to plan something else   |   the list stays pinned on screen until Clear";
    }

    // ------------------------------------------------------------ prices tab

    private void recordPrice(boolean sell) {
        if (priceSubject == null) {
            Chat.error("Pick an item first.");
            return;
        }
        double amount = parseDouble(priceAmountBox, -1);
        if (amount < 0) {
            Chat.error("Type the amount first.");
            return;
        }
        int qty = Math.max(1, parseInt(priceQtyBox, 1));
        String shop = priceShopBox.getValue().trim();
        ItemKey key = Fingerprinter.key(priceSubject);
        WarehouseClient.categories().learn(priceSubject, key);
        WarehouseClient.prices().add(new dev.warehouse.prices.PriceObservation(dev.warehouse.prices.PriceKeys.of(key), Fingerprinter.displayName(priceSubject), amount / qty, qty, sell, shop.isEmpty() ? null : shop, "manual"));
        priceAmountBox.setValue("");
        rebuild();
    }

    private void buildPrices() {
        String q = priceSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            java.util.Set<ItemKey> seen = new java.util.HashSet<>();
            if (minecraft != null && minecraft.player != null) {
                var inv = minecraft.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.isEmpty() || !Fingerprinter.displayName(st).toLowerCase(Locale.ROOT).contains(q)) continue;
                    ItemKey k = Fingerprinter.key(st);
                    if (!seen.add(k)) continue;
                    ItemStack copy = st.copy();
                    rows.add(new Row(copy, Fingerprinter.displayName(st), "in your inventory", "", 0xFFA0A0A0, sh -> {
                        priceSubject = copy;
                        priceSearchBox.setValue("");
                        rebuild();
                    }, List.of(), false));
                }
            }
            for (Agg a : aggregate()) {
                if (seen.contains(a.key()) || !a.name().toLowerCase(Locale.ROOT).contains(q)) continue;
                if (rows.size() > 120) break;
                ItemStack ic = icon(a.key());
                if (ic == null) continue;
                rows.add(new Row(ic, a.name(), "stored ×" + a.total(), "", 0xFFA0A0A0, sh -> {
                    priceSubject = ic;
                    priceSearchBox.setValue("");
                    rebuild();
                }, List.of(), false));
            }
            footer = "Click an item to make it the subject";
            return;
        }
        if (priceSubject == null) {
            header("Pick an item", "Hold it when opening this tab, type its name above, or use a Price button on another tab.", 0xFFFFD060);
            return;
        }
        ItemKey key = Fingerprinter.key(priceSubject);
        var val = WarehouseClient.valuation().unitValue(key);
        rows.add(new Row(priceSubject, Fingerprinter.displayName(priceSubject), val == null ? "no value yet" : "valued at " + dev.warehouse.prices.Valuation.describe(val), "", 0xFFFFD060, null, List.of(), true)
                .withButtons(new RowButton("Craft", sh -> {
                    craftSelected = priceSubject.copy();
                    switchTab(Tab.CRAFT);
                })));
        List<String> keys = dev.warehouse.prices.PriceKeys.candidates(key);
        var sst = WarehouseClient.prices().stats(keys, true);
        var bst = WarehouseClient.prices().stats(keys, false);
        rows.add(new Row(null, "Sell: " + (sst == null ? "-" : dev.warehouse.prices.Valuation.money(sst.median()) + " median of " + sst.count() + " (" + dev.warehouse.prices.Valuation.money(sst.min()) + " to " + dev.warehouse.prices.Valuation.money(sst.max()) + ")"),
                "Buy: " + (bst == null ? "-" : dev.warehouse.prices.Valuation.money(bst.median()) + " median of " + bst.count() + " (" + dev.warehouse.prices.Valuation.money(bst.min()) + " to " + dev.warehouse.prices.Valuation.money(bst.max()) + ")"), "", 0xFFDDDDDD, null, List.of(), false));
        int n = 0;
        for (String pk : keys) {
            for (var o : WarehouseClient.prices().observations(pk)) {
                n++;
                final var obs = o;
                rows.add(new Row(null, dev.warehouse.prices.Valuation.money(o.unitPrice) + "  " + (o.sell ? "sell" : "buy") + (o.shop != null ? "  at " + o.shop : ""),
                        o.source + (o.quantity > 1 ? ", per unit of " + o.quantity : "") + "  ·  " + Staleness.describe(o.whenEpochMs), "", Staleness.color(o.whenEpochMs), null, List.of(), false)
                        .withButtons(new RowButton("Delete", sh -> {
                            WarehouseClient.prices().remove(obs);
                            rebuild();
                        })));
            }
        }
        if (n == 0) rows.add(new Row(null, "No prices recorded yet", "Shop chat and signs are recorded automatically; or type an amount above and press Buy price or Sell price.", "", 0xFFA0A0A0, null, List.of(), false));
        footer = "Buy price = what a shop charges you; Sell price = what a shop pays you. Amount is per unit, or for the quantity given.";
    }

    // ------------------------------------------------------------ tools tab

    private void toggle(String label, String detail, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        boolean on = get.getAsBoolean();
        rows.add(new Row(null, label, detail, on ? "ON" : "OFF", on ? 0xFF80FF80 : 0xFF909090, sh -> {
            set.accept(!on);
            ConfigIO.save();
            rebuild();
        }, List.of(Component.literal("Click to switch " + (on ? "off" : "on")).withStyle(ChatFormatting.DARK_GRAY)), false));
    }

    private void action(String label, String detail, RowButton... buttons) {
        rows.add(new Row(null, label, detail, "", 0xFFDDDDDD, null, List.of(), false).withButtons(buttons));
    }

    private @Nullable ItemStack heldOrNull() {
        ItemStack st = minecraft != null && minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        if (st.isEmpty()) Chat.error("Hold the item first.");
        return st.isEmpty() ? null : st;
    }

    private @Nullable ContainerEntry lookedAtContainer() {
        if (minecraft == null || minecraft.level == null) return null;
        if (!(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) || minecraft.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            Chat.error("Look at the container before opening this screen.");
            return null;
        }
        var res = dev.warehouse.index.ContainerResolver.resolveAt(minecraft, bhr.getBlockPos());
        ContainerEntry e = res != null ? WarehouseClient.index().atBlock(res.dimension(), res.primary()) : null;
        if (e == null) Chat.error("That block is not an indexed container.");
        return e;
    }

    private void buildTools() {
        var cfg = ConfigIO.get();
        String filter = toolsBox.getValue().trim().toLowerCase(Locale.ROOT);
        header("Guides and display", "click a line to switch it", 0xFF40C8FF);
        toggle("Held-item guide", "keep the destination chest highlighted while holding an item in the warehouse", () -> cfg.heldItemGuide, v -> cfg.heldItemGuide = v);
        toggle("Region outlines", "draw region boxes in the world", () -> cfg.showRegionOutlines, v -> cfg.showRegionOutlines = v);
        toggle("Particle trail", "lead to the target with particles along a walkable path", () -> cfg.guideParticles, v -> cfg.guideParticles = v);
        toggle("Guide line", "also draw the straight line", () -> cfg.guideLine, v -> cfg.guideLine = v);
        toggle("Sneak stock card", "sneak while looking at a block or holding an item", () -> cfg.lookHud, v -> cfg.lookHud = v);
        toggle("Warehouse button in inventory", "", () -> cfg.inventoryButton, v -> cfg.inventoryButton = v);
        toggle("Shopping list HUD", "pin the craft plan to the screen", () -> cfg.shoppingListHud, v -> cfg.shoppingListHud = v);
        toggle("Always open on Overview", "otherwise only the first time per session", () -> cfg.alwaysOpenOverview, v -> cfg.alwaysOpenOverview = v);
        toggle("Record prices from shop chat", "", () -> cfg.capturePricesFromChat, v -> cfg.capturePricesFromChat = v);
        toggle("Record prices from signs", "", () -> cfg.capturePricesFromSigns, v -> cfg.capturePricesFromSigns = v);
        toggle("Announce recorded prices", "a chat line each time a price is noted", () -> cfg.announcePriceCapture, v -> cfg.announcePriceCapture = v);
        toggle("Condense suggestions", "chat hint when a zone runs low on slots", () -> cfg.condenseSuggestions, v -> cfg.condenseSuggestions = v);
        toggle("Custom items stay put", "a rule change never flags stored plugin items as misplaced", () -> cfg.customItemsStayPut, v -> cfg.customItemsStayPut = v);
        toggle("Inventory full alert", "chat message listing stacks unused for " + cfg.unusedAfterMinutes + "+ minutes", () -> cfg.inventoryFullAlert, v -> cfg.inventoryFullAlert = v);
        toggle("Mark unused items when full", "tint them in inventory screens until " + cfg.unusedHighlightClearAtFreeSlots + " slots are free", () -> cfg.highlightUnusedWhenFull, v -> cfg.highlightUnusedWhenFull = v);

        header("Actions", "", 0xFFFFD040);
        action("Find held item", "highlight where it is stored, or belongs", new RowButton("Find", sh -> {
            FindHeldItem.run(minecraft, false);
            onClose();
        }), new RowButton("All places", sh -> {
            FindHeldItem.run(minecraft, true);
            onClose();
        }));
        action("Routes", "clear your inventory into the warehouse, or fix misplaced items", new RowButton("Clear inventory", sh -> {
            WarehouseClient.clearMode().toggle(minecraft, dev.warehouse.modes.ClearInventoryMode.Kind.CLEAR);
            onClose();
        }), new RowButton("Sort", sh -> {
            WarehouseClient.clearMode().toggle(minecraft, dev.warehouse.modes.ClearInventoryMode.Kind.SORT);
            onClose();
        }));
        var check = WarehouseClient.stockCheck();
        action("Warehouse stock check", check.isActive() ? check.label() : "light up every container until opened", new RowButton("Start", sh -> {
            check.start(minecraft, dev.warehouse.shops.StockCheck.Kind.WAREHOUSE, null);
            onClose();
        }), new RowButton("Cancel", sh -> {
            check.cancel("Stock check cancelled.");
            rebuild();
        }));
        action("Highlights", WarehouseClient.highlights().active().size() + " active", new RowButton("Clear", sh -> {
            WarehouseClient.highlights().clear();
            rebuild();
        }));
        action("Pin held item to the chest you were looking at", "overrides the plan for that item", new RowButton("Pin", sh -> {
            ItemStack st = heldOrNull();
            ContainerEntry e = st == null ? null : lookedAtContainer();
            if (st != null && e != null) {
                WarehouseClient.organizer().setOverride(Fingerprinter.key(st), e.id);
                Chat.info(Fingerprinter.displayName(st) + " now belongs in " + e.label() + ".");
            }
        }), new RowButton("Unpin", sh -> {
            ItemStack st = heldOrNull();
            if (st != null) {
                WarehouseClient.organizer().setOverride(Fingerprinter.key(st), null);
                Chat.info("Override removed for " + Fingerprinter.displayName(st) + ".");
            }
        }));
        action("Forget the chest you were looking at", "drops it from the index; it is re-indexed when opened again", new RowButton("Forget", sh -> {
            ContainerEntry e = lookedAtContainer();
            if (e != null) {
                WarehouseClient.index().remove(e.id);
                Chat.info("Forgot " + e.label() + ".");
            }
        }));
        action("Keep held item", "always leave it in your inventory on routes", new RowButton("Keep", sh -> {
            ItemStack st = heldOrNull();
            if (st != null) dev.warehouse.command.ShopCommands.setKeep(st, true);
            rebuild();
        }), new RowButton("Remove", sh -> {
            ItemStack st = heldOrNull();
            if (st != null) dev.warehouse.command.ShopCommands.setKeep(st, false);
            rebuild();
        }));
        for (String keep : new ArrayList<>(cfg.alwaysKeep)) {
            rows.add(new Row(null, "  kept: " + keep, "", "", 0xFFA0A0A0, null, List.of(), false).withButtons(new RowButton("Remove", sh -> {
                cfg.alwaysKeep.remove(keep);
                ConfigIO.save();
                rebuild();
            })));
        }

        header("Regions", "wand: " + cfg.wandItem.replace("minecraft:", "") + "  ·  next region drawn: " + (dev.warehouse.wand.WandHandler.nextType == dev.warehouse.region.RegionType.SHOP ? "shop" : "warehouse"), 0xFF80FF80);
        action("Wand", "right-click two corners; type a name above and press Rename on a region", new RowButton(dev.warehouse.wand.WandHandler.nextType == dev.warehouse.region.RegionType.SHOP ? "Next: shop" : "Next: warehouse", sh -> {
            dev.warehouse.wand.WandHandler.nextType = dev.warehouse.wand.WandHandler.nextType == dev.warehouse.region.RegionType.SHOP ? dev.warehouse.region.RegionType.WAREHOUSE : dev.warehouse.region.RegionType.SHOP;
            rebuild();
        }), new RowButton("Cancel selection", sh -> {
            WarehouseClient.wand().selection.clear();
            rebuild();
        }));
        for (Region r : new ArrayList<>(WarehouseClient.regions().all())) {
            if (!filter.isEmpty() && !r.name.toLowerCase(Locale.ROOT).contains(filter) && !r.type.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
            String size = (r.maxX - r.minX + 1) + "×" + (r.maxY - r.minY + 1) + "×" + (r.maxZ - r.minZ + 1);
            int chests = WarehouseClient.index().inRegion(r.id).size();
            rows.add(new Row(null, r.name, r.type.name().toLowerCase(Locale.ROOT) + "  ·  " + size + " at " + r.minX + "," + r.minY + "," + r.minZ + "  ·  " + chests + " container(s)", "", 0xFFDDDDDD, sh -> {
                WarehouseClient.highlights().spot(r.dimension, new net.minecraft.world.phys.Vec3((r.minX + r.maxX) / 2.0 + 0.5, (r.minY + r.maxY) / 2.0 + 0.5, (r.minZ + r.maxZ) / 2.0 + 0.5), ConfigIO.get().warehouseColor, ConfigIO.get().highlightSeconds, r.name);
                onClose();
            }, List.of(Component.literal("Click: highlight the region centre").withStyle(ChatFormatting.DARK_GRAY)), false).withButtons(
                    new RowButton("Rename", sh -> {
                        String n = toolsBox.getValue().trim();
                        if (n.isEmpty()) Chat.error("Type the new name in the box first.");
                        else if (WarehouseClient.regions().rename(r.name, n)) {
                            toolsBox.setValue("");
                            rebuild();
                        }
                    }),
                    new RowButton("Type ▸", sh -> {
                        var types = dev.warehouse.region.RegionType.values();
                        r.type = types[(r.type.ordinal() + 1) % types.length];
                        WarehouseClient.regions().markDirty();
                        if (r.type == dev.warehouse.region.RegionType.SHOP) WarehouseClient.shops().forRegion(r);
                        WarehouseClient.onRegionsChanged();
                        WarehouseClient.organizer().invalidate();
                        rebuild();
                    }),
                    new RowButton("Delete", sh -> {
                        if (!sh) {
                            Chat.warn("Shift-click Delete to remove '" + r.name + "'.");
                            return;
                        }
                        WarehouseClient.regions().deleteById(r.id);
                        WarehouseClient.onRegionsChanged();
                        rebuild();
                    })));
        }

        header("Claims", WarehouseClient.storage().claims.get().size() + " GriefPrevention anchor(s)", 0xFF60FF80);
        action("Import claims", "runs /claimlist and reads the result; then capture each claim's bounds with the wand", new RowButton("Import", sh -> {
            WarehouseClient.claimImporter().run(minecraft);
            onClose();
        }), new RowButton("Clear anchors", sh -> {
            WarehouseClient.storage().claims.get().clear();
            WarehouseClient.storage().claims.markDirty();
            rebuild();
        }));

        var groups = WarehouseClient.itemGroups().all();
        int samples = 0;
        for (ItemGroup g : groups) samples += g.samples != null ? g.samples.size() : 0;
        header("Custom items", groups.size() + " group(s), " + samples + " sample(s)  ·  " + dev.warehouse.items.CustomItemRules.active().size() + " rule(s)", 0xFFC0A0FF);
        action("Rules and manifest", "reload custom_item_rules.json; write the manifest to the shared folder", new RowButton("Reload rules", sh -> {
            WarehouseClient.manifestExporter().reloadRules(true);
            rebuild();
        }), new RowButton("Export manifest", sh -> WarehouseClient.manifestExporter().export(true)));
        action("Prune groups that match nothing in the index", "", new RowButton("Prune", sh -> {
            java.util.Set<ItemKey> seen = new java.util.HashSet<>();
            for (ContainerEntry e : WarehouseClient.index().all()) for (StackRecord st : e.contents) {
                seen.add(st.key);
                if (st.nested != null) for (StackRecord n : st.nested) seen.add(n.key);
            }
            int removed = 0;
            for (ItemGroup g : new ArrayList<>(groups)) {
                boolean any = false;
                for (ItemKey k : g.members) if (seen.contains(k)) any = true;
                if (!any) {
                    WarehouseClient.storage().items.get().remove(g);
                    removed++;
                }
            }
            WarehouseClient.storage().items.markDirty();
            WarehouseClient.itemGroups().invalidate();
            WarehouseClient.categories().invalidate();
            Chat.info("Pruned " + removed + " group(s).");
            rebuild();
        }));
        int gShown = 0;
        var cats = WarehouseClient.categories();
        for (ItemGroup g : groups) {
            if (!filter.isEmpty() && !g.displayName.toLowerCase(Locale.ROOT).contains(filter) && !g.groupId.contains(filter)) continue;
            if (gShown++ >= 80) {
                header("...", "type part of a name above to filter the groups", 0xFF808080);
                break;
            }
            ItemKey first = g.members.isEmpty() ? null : g.members.get(0);
            String cat = first != null ? cats.categoryOf(first) : "?";
            rows.add(new Row(first != null ? icon(first) : null, g.displayName, g.groupId + "  ·  " + g.members.size() + " fingerprint(s)  ·  " + cat + (g.categoryOverride != null ? " (set by you)" : ""), "", 0xFFDDDDDD, null, List.of(), false).withButtons(
                    new RowButton("Category ▸", sh -> {
                        List<String> order = dev.warehouse.organizer.Categorizer.DEFAULT_ORDER;
                        int idx = g.categoryOverride == null ? -1 : order.indexOf(g.categoryOverride);
                        int next = sh ? idx - 1 : idx + 1;
                        if (next >= order.size()) next = -1;
                        if (next < -1) next = order.size() - 1;
                        WarehouseClient.itemGroups().setCategory(g, next < 0 ? null : order.get(next));
                        WarehouseClient.categories().invalidate();
                        WarehouseClient.organizer().invalidate();
                        rebuild();
                    }),
                    new RowButton("Rename", sh -> {
                        String n = toolsBox.getValue().trim();
                        if (n.isEmpty()) Chat.error("Type the new name in the box first.");
                        else if (WarehouseClient.itemGroups().rename(g, n)) {
                            toolsBox.setValue("");
                            rebuild();
                        }
                    })));
        }

        header("Data", "config/warehouse/" + WarehouseClient.storage().serverKey(), 0xFFFFA040);
        action("Export a bundle of this server's data", "to the exports folder", new RowButton("Export", sh -> {
            String f = WarehouseClient.exporter().export(null, false);
            if (f != null) Chat.info("Exported " + f);
        }), new RowButton("With lost log", sh -> {
            String f = WarehouseClient.exporter().export(null, true);
            if (f != null) Chat.info("Exported " + f);
        }));
        for (String file : WarehouseClient.importer().availableFiles()) {
            rows.add(new Row(null, "  " + file, "", "", 0xFFA0A0A0, null, List.of(), false).withButtons(new RowButton("Merge", sh -> {
                var r = WarehouseClient.importer().run(file, dev.warehouse.export.Importer.Mode.MERGE);
                if (r.ok()) Chat.info(r.message());
                else Chat.error(r.message());
                rebuild();
            }), new RowButton("Replace", sh -> {
                if (!sh) {
                    Chat.warn("Shift-click Replace to overwrite this server's data with " + file + ".");
                    return;
                }
                var r = WarehouseClient.importer().run(file, dev.warehouse.export.Importer.Mode.REPLACE);
                if (r.ok()) Chat.info(r.message());
                else Chat.error(r.message());
                rebuild();
            })));
        }
        for (String what : List.of("index", "lost", "plan", "items", "all")) {
            action("Clear " + what, "shift-click to confirm", new RowButton("Clear", sh -> {
                if (!sh) {
                    Chat.warn("Shift-click to clear " + what + ".");
                    return;
                }
                var st = WarehouseClient.storage();
                if (what.equals("index") || what.equals("all")) WarehouseClient.index().clear();
                if (what.equals("lost") || what.equals("all")) WarehouseClient.lostLog().clear();
                if (what.equals("plan") || what.equals("all")) WarehouseClient.organizer().clearPlan();
                if (what.equals("items") || what.equals("all")) {
                    st.items.get().clear();
                    st.items.markDirty();
                    WarehouseClient.itemGroups().invalidate();
                    WarehouseClient.categories().invalidate();
                }
                st.flushAll();
                Chat.info("Cleared " + what + ".");
                rebuild();
            }));
        }
        footer = "Everything here is also a /warehouse command";
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
        if (tab == Tab.MISPLACED) g.text(getFont(), "min ×" + misplacedMin, 42, controlsY + 5, 0xFFFFFFFF);

        if (tab == Tab.OVERVIEW && dashboard != null) {
            dashboard.draw(g, getFont(), listLeft, listTop, listRight, listBottom, mouseX, mouseY);
            g.text(getFont(), footer, 20, height - 16, 0xFFA0A0A0);
            return;
        }
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
                List<int[]> rects = buttonRects(r);
                int textLimit = rects.isEmpty() ? listRight - x - (r.right.isEmpty() ? 8 : getFont().width(r.right) + 12) : rects.get(0)[0] - x - 6;
                g.text(getFont(), getFont().plainSubstrByWidth(r.name, Math.min(260, textLimit)), x, y + 2, nameColor);
                if (!r.detail.isEmpty()) g.text(getFont(), getFont().plainSubstrByWidth(r.detail, Math.max(20, textLimit)), x, y + 11, 0xFFA0A0A0);
                if (!r.right.isEmpty()) g.text(getFont(), r.right, listRight - 6 - getFont().width(r.right), y + 6, r.rightColor);
                for (int b = 0; b < rects.size(); b++) {
                    int[] rc = rects.get(b);
                    boolean bh = mouseX >= rc[0] && mouseX < rc[1] && mouseY >= y + 3 && mouseY < y + ROW_H - 3;
                    g.fill(rc[0], y + 3, rc[1], y + ROW_H - 3, bh ? 0xA0FFFFFF : 0x50FFFFFF);
                    g.outline(rc[0], y + 3, rc[1] - rc[0], ROW_H - 6, 0x80FFFFFF);
                    g.text(getFont(), r.buttons.get(b).label(), rc[0] + 4, y + 6, bh ? 0xFF101010 : 0xFFFFFFFF);
                }
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
        if (tab == Tab.OVERVIEW && dashboard != null) {
            Object target = dashboard.click(mx, my);
            if (target instanceof java.util.UUID shopId) {
                selectedShop = shopId;
                switchTab(Tab.SHOPS);
                return true;
            }
            if (target instanceof Tab t && t != Tab.OVERVIEW) {
                switchTab(t);
                return true;
            }
            return false;
        }
        if (mx >= listLeft && mx < listRight && my >= listTop && my < listBottom) {
            int idx = (int) ((my - listTop + scroll) / ROW_H);
            if (idx >= 0 && idx < rows.size()) {
                Row r = rows.get(idx);
                List<int[]> rects = buttonRects(r);
                for (int b = 0; b < rects.size(); b++) {
                    if (mx >= rects.get(b)[0] && mx < rects.get(b)[1]) {
                        r.buttons.get(b).action().run(event.hasShiftDown());
                        return true;
                    }
                }
                if (r.onClick != null) {
                    selected = idx;
                    r.onClick.run(event.hasShiftDown());
                    return true;
                }
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
