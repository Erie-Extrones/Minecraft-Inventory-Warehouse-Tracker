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
    public enum Tab { SEARCH, UNSORTED, LOST, MISPLACED, PLAN, CONDENSE }

    private static String lastQuery = "";
    private static Tab lastTab = Tab.SEARCH;
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

    public SearchScreen() {
        super(Component.literal("Warehouse"));
    }

    @Override
    protected void init() {
        tabButtons.clear();
        planButtons.clear();
        int x = 20;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.literal(label(t)), btn -> switchTab(t)).bounds(x, 22, 64, 18).build();
            tabButtons.add(addRenderableWidget(b));
            x += 66;
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

    public static void open(Minecraft mc) {
        mc.gui.setScreen(new SearchScreen());
    }

    public static void open(Minecraft mc, Tab t) {
        lastTab = t;
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
