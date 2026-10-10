package dev.warehouse;

import dev.warehouse.claims.ClaimImporter;
import dev.warehouse.claims.ClaimListParser;
import dev.warehouse.claims.VisualizationCapture;
import dev.warehouse.command.WarehouseCommands;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.index.EntityScanner;
import dev.warehouse.index.ScreenTracker;
import dev.warehouse.index.Snapshotter;
import dev.warehouse.index.StaleContainerSweeper;
import dev.warehouse.items.CategoryResolver;
import dev.warehouse.items.ItemGroups;
import dev.warehouse.inventory.DropDetector;
import dev.warehouse.inventory.InventoryTracker;
import dev.warehouse.inventory.LostLog;
import dev.warehouse.modes.ClearInventoryMode;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.PlanDiff;
import dev.warehouse.render.GuidePath;
import dev.warehouse.render.HighlightRenderer;
import dev.warehouse.render.PlanPreviewRenderer;
import dev.warehouse.ui.FindHeldItem;
import dev.warehouse.ui.HeldItemGuide;
import dev.warehouse.ui.InventoryButton;
import dev.warehouse.ui.LookHud;
import dev.warehouse.ui.SearchScreen;
import dev.warehouse.ui.TooltipProvider;
import dev.warehouse.region.RegionManager;
import dev.warehouse.render.RegionRenderer;
import dev.warehouse.storage.StorageManager;
import dev.warehouse.wand.WandHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WarehouseClient implements ClientModInitializer {
    public static final String MOD_ID = "warehouse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static WarehouseClient instance;

    private StorageManager storage;
    private RegionManager regions;
    private WandHandler wand;
    private RegionRenderer regionRenderer;
    private ClaimListParser claimParser;
    private ClaimImporter claimImporter;
    private VisualizationCapture visualizationCapture;
    private ItemGroups itemGroups;
    private CategoryResolver categories;
    private ContainerIndex index;
    private Snapshotter snapshotter;
    private ScreenTracker screenTracker;
    private EntityScanner entityScanner;
    private Organizer organizer;
    private PlanDiff planDiff;
    private HighlightRenderer highlights;
    private PlanPreviewRenderer planPreview;
    private LostLog lostLog;
    private ClearInventoryMode clearMode;
    private dev.warehouse.export.Exporter exporter;
    private dev.warehouse.export.Importer importer;
    private InventoryTracker inventoryTracker;
    private HeldItemGuide heldItemGuide;
    private GuidePath guidePath;
    private dev.warehouse.organizer.Condenser condenser;
    private dev.warehouse.export.ManifestExporter manifestExporter;
    private dev.warehouse.prices.PriceBook priceBook;
    private dev.warehouse.prices.Valuation valuation;
    private dev.warehouse.prices.ShopChatParser shopChatParser;
    private dev.warehouse.prices.SignPriceReader signPriceReader;
    private dev.warehouse.shops.ShopManager shops;
    private dev.warehouse.shops.StockCheck stockCheck;
    private dev.warehouse.craft.CraftPlanner craftPlanner;
    private StaleContainerSweeper staleSweeper;

    public static WarehouseClient get() {
        return instance;
    }

    public static StorageManager storage() {
        return instance.storage;
    }

    public static RegionManager regions() {
        return instance.regions;
    }

    public static WandHandler wand() {
        return instance.wand;
    }

    public static ClaimImporter claimImporter() {
        return instance.claimImporter;
    }

    public static VisualizationCapture visualizationCapture() {
        return instance.visualizationCapture;
    }

    public static ItemGroups itemGroups() {
        return instance.itemGroups;
    }

    public static CategoryResolver categories() {
        return instance.categories;
    }

    public static ContainerIndex index() {
        return instance.index;
    }

    public static Snapshotter snapshotter() {
        return instance.snapshotter;
    }

    public static ScreenTracker screenTracker() {
        return instance.screenTracker;
    }

    public static EntityScanner entityScanner() {
        return instance.entityScanner;
    }

    public static Organizer organizer() {
        return instance.organizer;
    }

    public static PlanDiff planDiff() {
        return instance.planDiff;
    }

    public static HighlightRenderer highlights() {
        return instance.highlights;
    }

    public static LostLog lostLog() {
        return instance.lostLog;
    }

    public static ClearInventoryMode clearMode() {
        return instance.clearMode;
    }

    public static dev.warehouse.export.Exporter exporter() {
        return instance.exporter;
    }

    public static dev.warehouse.export.Importer importer() {
        return instance.importer;
    }

    public static InventoryTracker inventoryTracker() {
        return instance.inventoryTracker;
    }

    public static GuidePath guidePath() {
        return instance.guidePath;
    }

    public static dev.warehouse.organizer.Condenser condenser() {
        return instance.condenser;
    }

    public static dev.warehouse.export.ManifestExporter manifestExporter() {
        return instance.manifestExporter;
    }

    public static dev.warehouse.prices.PriceBook prices() {
        return instance.priceBook;
    }

    public static dev.warehouse.prices.Valuation valuation() {
        return instance.valuation;
    }

    public static dev.warehouse.prices.ShopChatParser shopChatParser() {
        return instance.shopChatParser;
    }

    public static dev.warehouse.shops.ShopManager shops() {
        return instance.shops;
    }

    public static dev.warehouse.shops.StockCheck stockCheck() {
        return instance.stockCheck;
    }

    public static dev.warehouse.craft.CraftPlanner craftPlanner() {
        return instance.craftPlanner;
    }

    public static HeldItemGuide heldItemGuide() {
        return instance.heldItemGuide;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("Warehouse initializing");
        ConfigIO.load();

        storage = new StorageManager();
        regions = new RegionManager(storage.regions);
        wand = new WandHandler(regions);
        regionRenderer = new RegionRenderer(regions, wand.selection);
        claimParser = new ClaimListParser(storage.claims);
        claimImporter = new ClaimImporter(claimParser);
        visualizationCapture = new VisualizationCapture(storage.claims, regions);
        itemGroups = new ItemGroups(storage.items);
        categories = new CategoryResolver(itemGroups, () -> storage.plan.get());
        index = new ContainerIndex(storage.index);
        snapshotter = new Snapshotter(itemGroups);
        screenTracker = new ScreenTracker(index, regions, snapshotter);
        entityScanner = new EntityScanner(index, regions, snapshotter);
        organizer = new Organizer(storage.plan, index, regions, categories);
        planDiff = new PlanDiff(index, organizer);
        organizer.onChange(planDiff::invalidate);
        condenser = new dev.warehouse.organizer.Condenser(index, organizer, categories, regions);
        index.onChange(condenser::invalidate);
        organizer.onChange(condenser::invalidate);
        highlights = new HighlightRenderer();
        planPreview = new PlanPreviewRenderer(organizer, index);
        lostLog = new LostLog(storage.lost);
        clearMode = new ClearInventoryMode();
        exporter = new dev.warehouse.export.Exporter(storage);
        manifestExporter = new dev.warehouse.export.ManifestExporter();
        storage.onBind(manifestExporter::onBind);
        priceBook = new dev.warehouse.prices.PriceBook(storage.prices);
        valuation = new dev.warehouse.prices.Valuation(priceBook);
        shopChatParser = new dev.warehouse.prices.ShopChatParser();
        signPriceReader = new dev.warehouse.prices.SignPriceReader();
        shops = new dev.warehouse.shops.ShopManager(storage.shops, regions);
        stockCheck = new dev.warehouse.shops.StockCheck();
        craftPlanner = new dev.warehouse.craft.CraftPlanner();
        index.onChange(craftPlanner::invalidate);
        storage.onBind(() -> {
            valuation.invalidate();
            stockCheck.onBind(Minecraft.getInstance());
        });
        importer = new dev.warehouse.export.Importer(storage);
        inventoryTracker = new InventoryTracker();
        heldItemGuide = new HeldItemGuide();
        guidePath = new GuidePath();
        staleSweeper = new StaleContainerSweeper(index);
        organizer.onChange(heldItemGuide::invalidate);
        storage.onBind(() -> {
            itemGroups.invalidate();
            categories.invalidate();
            organizer.invalidate();
            highlights.clear();
        });

        Keybinds.register();
        wand.register();
        claimParser.register();
        shopChatParser.register();
        screenTracker.register();
        entityScanner.register();
        TooltipProvider.register();
        DropDetector.register();
        InventoryButton.register();
        new LookHud().register();
        new dev.warehouse.craft.ShoppingListHud().register();
        WarehouseCommands.register();

        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            storage.bind(client);
            if (ConfigIO.get().autoReimportClaimsOnJoin) {
                // Delay a few seconds so the server has finished sending the join burst.
                autoImportAtMs = System.currentTimeMillis() + 4000;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            wand.selection.clear();
            highlights.clear();
            storage.unbind();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private long autoImportAtMs;

    private void onEndTick(Minecraft mc) {
        if (!storage.isBound()) {
            if (mc.player != null && mc.level != null) storage.bind(mc);
            else return;
        }
        if (mc.level == null || mc.player == null) return;

        while (Keybinds.openSearch.consumeClick()) {
            if (mc.gui.screen() == null) SearchScreen.open(mc);
        }
        while (Keybinds.findHeldItem.consumeClick()) {
            FindHeldItem.run(mc, false);
        }
        while (Keybinds.clearInventoryMode.consumeClick()) {
            clearMode.toggle(mc);
        }
        while (Keybinds.toggleOutlines.consumeClick()) {
            ConfigIO.get().showRegionOutlines = !ConfigIO.get().showRegionOutlines;
            ConfigIO.save();
            Chat.info("Region outlines " + (ConfigIO.get().showRegionOutlines ? "on" : "off") + ".");
        }

        if (autoImportAtMs != 0 && System.currentTimeMillis() >= autoImportAtMs) {
            autoImportAtMs = 0;
            claimImporter.run(mc);
        }

        claimParser.tick();
        visualizationCapture.tick(mc);
        entityScanner.tick(mc);
        staleSweeper.tick(mc);
        regionRenderer.tick(mc);
        highlights.tick(mc);
        planPreview.tick(mc);
        clearMode.tick(mc);
        heldItemGuide.tick(mc);
        guidePath.tick(mc);
        inventoryTracker.tick(mc);
        condenser.tick(mc);
        manifestExporter.tick(mc);
        signPriceReader.tick(mc);
        stockCheck.tick(mc);
        craftPlanner.tick(mc);
        if (mc.level.getGameTime() % 20 == 0) lostLog.expire();
        storage.tick();
    }

    // ---- hooks called from mixins ----

    public static void onBlockUpdatePacket(BlockPos pos, BlockState state) {
        if (instance == null || instance.visualizationCapture == null) return;
        instance.visualizationCapture.onBlockUpdate(Minecraft.getInstance(), pos, state);
    }

    public static void onContainerContentPacket(int containerId) {
        if (instance == null || instance.screenTracker == null) return;
        instance.screenTracker.onContentPacket(containerId);
    }

    public static void onRegionsChanged() {
        if (instance == null) return;
        // Re-evaluate region membership of indexed containers.
        for (var e : instance.index.all()) {
            if (e.kind.isEntity()) continue;
            var r = instance.regions.regionAt(e.dimension, e.pos);
            e.regionId = r != null ? r.id : null;
        }
        instance.index.fireChanged();
    }
}
