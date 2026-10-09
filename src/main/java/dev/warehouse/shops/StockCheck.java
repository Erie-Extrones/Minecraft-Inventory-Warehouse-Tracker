package dev.warehouse.shops;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import dev.warehouse.ui.SearchScreen;
import dev.warehouse.ui.Staleness;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Walk-through inventory: every container of a shop (or of all warehouse regions) is highlighted and drops out as you open it.
 * When the last one is opened the shop's stock is recorded and its report opens. Also nags about overdue shop inventories.
 */
public final class StockCheck {
    public enum Kind { SHOP, WAREHOUSE }

    public static final String GROUP = "stockcheck";

    private boolean active;
    private Kind kind = Kind.SHOP;
    private @Nullable UUID shopId;
    private final Set<UUID> pending = new LinkedHashSet<>();
    private int total;
    private final Set<UUID> remindedThisSession = new HashSet<>();
    private @Nullable UUID lastShopInside;
    private int ticks;

    public boolean isActive() {
        return active;
    }

    public Kind kind() {
        return kind;
    }

    public int remaining() {
        return pending.size();
    }

    public int total() {
        return total;
    }

    public String label() {
        if (!active) return "";
        if (kind == Kind.SHOP) {
            Shop s = WarehouseClient.shops().byId(shopId);
            return "Inventory at " + (s != null ? WarehouseClient.shops().name(s) : "shop") + ": " + (total - pending.size()) + "/" + total;
        }
        return "Warehouse stock check: " + (total - pending.size()) + "/" + total;
    }

    public void start(Minecraft mc, Kind kind, @Nullable Shop shop) {
        if (mc.level == null || mc.player == null) return;
        if (active) cancel("Previous stock check cancelled.");
        String dim = RegionManager.dimensionId(mc.level);
        List<Region> regions = new ArrayList<>();
        if (kind == Kind.SHOP) {
            Region r = shop != null ? WarehouseClient.shops().region(shop) : null;
            if (r == null) {
                Chat.error("No shop region. Stand inside a Shop region or name one: /warehouse shop inventory <name>.");
                return;
            }
            regions.add(r);
        } else {
            for (Region r : WarehouseClient.regions().ofType(RegionType.WAREHOUSE)) if (r.dimension.equals(dim)) regions.add(r);
        }
        if (regions.isEmpty()) {
            Chat.error("No regions to check in this dimension.");
            return;
        }
        WarehouseClient.organizer().discovery().run(mc, regions);
        pending.clear();
        for (Region r : regions) {
            if (!r.dimension.equals(dim)) continue;
            for (ContainerEntry e : WarehouseClient.index().inRegion(r.id)) if (!e.kind.isEntity()) pending.add(e.id);
        }
        if (pending.isEmpty()) {
            Chat.error("No containers known in " + (kind == Kind.SHOP ? WarehouseClient.shops().name(shop) : "the warehouse") + ". Walk the area so its chunks are loaded and try again.");
            return;
        }
        this.kind = kind;
        this.shopId = shop != null ? shop.regionId : null;
        total = pending.size();
        active = true;
        highlightAll();
        Chat.info((kind == Kind.SHOP ? "Inventory at " + WarehouseClient.shops().name(shop) : "Warehouse stock check") + ": open each of the " + total
                + " highlighted container(s). Each one unhighlights as you open it; /warehouse stockcheck cancel to stop.");
    }

    private void highlightAll() {
        var hl = WarehouseClient.highlights();
        hl.clearGroup(GROUP);
        int color = ConfigIO.get().stockCheckColor;
        for (UUID id : pending) {
            ContainerEntry e = WarehouseClient.index().byId(id);
            if (e != null) hl.container(e, color, 24 * 3600, null, false, GROUP);
        }
    }

    /** Called whenever a container's contents are snapshotted. */
    public void onContainerSeen(ContainerEntry e) {
        if (!active || !pending.remove(e.id)) return;
        WarehouseClient.highlights().removeContainer(GROUP, e.id);
        int done = total - pending.size();
        if (pending.isEmpty()) {
            finish();
        } else if (done % 5 == 0 || pending.size() <= 3) {
            Chat.info("Checked " + done + "/" + total + ", " + pending.size() + " to go.");
        }
    }

    private void finish() {
        active = false;
        WarehouseClient.highlights().clearGroup(GROUP);
        Minecraft mc = Minecraft.getInstance();
        if (kind == Kind.SHOP) {
            Shop shop = WarehouseClient.shops().byId(shopId);
            if (shop != null) {
                WarehouseClient.shops().recordInventory(shop);
                ShopManager.Report rep = WarehouseClient.shops().report(shop);
                Chat.info("Inventory done at " + WarehouseClient.shops().name(shop) + ": " + rep.items().size() + " item type(s), worth "
                        + dev.warehouse.prices.Valuation.money(rep.value()) + "; " + rep.low() + " low, " + rep.out() + " out.");
                SearchScreen.openShop(mc, shop.regionId);
                return;
            }
        }
        Chat.info("Stock check complete: every container was opened. The index is fresh.");
        SearchScreen.open(mc, SearchScreen.Tab.OVERVIEW);
    }

    public void cancel(String msg) {
        if (!active) return;
        active = false;
        pending.clear();
        WarehouseClient.highlights().clearGroup(GROUP);
        Chat.info(msg);
    }

    public void onBind(Minecraft mc) {
        remindedThisSession.clear();
        lastShopInside = null;
        List<String> overdue = new ArrayList<>();
        for (Shop s : WarehouseClient.shops().all()) if (WarehouseClient.shops().overdue(s)) overdue.add(WarehouseClient.shops().name(s));
        if (!overdue.isEmpty()) Chat.info("Shop inventory due: " + String.join(", ", overdue) + ". Walk in and run /warehouse shop inventory.");
    }

    /** Reminder when entering an overdue shop. */
    public void tick(Minecraft mc) {
        if (++ticks % 40 != 0 || mc.player == null || mc.level == null || !WarehouseClient.storage().isBound()) return;
        Shop here = WarehouseClient.shops().shopAt(RegionManager.dimensionId(mc.level), mc.player.blockPosition());
        UUID id = here != null ? here.regionId : null;
        if (id == null || id.equals(lastShopInside)) {
            lastShopInside = id;
            return;
        }
        lastShopInside = id;
        if (!active && WarehouseClient.shops().overdue(here) && remindedThisSession.add(id)) {
            Chat.info(WarehouseClient.shops().name(here) + ": last inventory " + (here.lastInventoryEpochMs == 0 ? "never" : Staleness.describe(here.lastInventoryEpochMs))
                    + ". Run /warehouse shop inventory to count it now.");
        }
    }
}
