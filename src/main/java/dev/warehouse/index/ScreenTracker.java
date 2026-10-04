package dev.warehouse.index;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.ui.ContainerPinButton;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks the currently open container screen: resolves which block it belongs to, snapshots its
 * contents when the server sends them and when the screen closes, and applies the region/pin gate.
 */
public final class ScreenTracker {
    private final ContainerIndex index;
    private final RegionManager regions;
    private final Snapshotter snapshotter;

    /** Current session, or null. */
    private @Nullable Session session;

    public static final class Session {
        public final AbstractContainerScreen<?> screen;
        public final AbstractContainerMenu menu;
        public final ContainerResolver.Resolved resolved;
        public @Nullable ContainerEntry entry;
        public boolean indexingAllowed;
        public boolean receivedContents;
        public int ticksOpen;
        public final List<Integer> clickedSlots = new ArrayList<>();

        Session(AbstractContainerScreen<?> screen, ContainerResolver.Resolved resolved) {
            this.screen = screen;
            this.menu = screen.getMenu();
            this.resolved = resolved;
        }
    }

    public ScreenTracker(ContainerIndex index, RegionManager regions, Snapshotter snapshotter) {
        this.index = index;
        this.regions = regions;
        this.snapshotter = snapshotter;
    }

    public void register() {
        ScreenEvents.AFTER_INIT.register(this::onScreenInit);
    }

    public @Nullable Session session() {
        return session;
    }

    private void onScreenInit(Minecraft mc, Screen screen, int w, int h) {
        if (!(screen instanceof ContainerScreen) && !(screen instanceof ShulkerBoxScreen)) {
            return;
        }
        AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) screen;
        if (session != null && session.screen == screen) return; // re-init (resize)
        if (!WarehouseClient.storage().isBound()) return;
        ContainerResolver.Resolved resolved = ContainerResolver.resolveRecentlyUsedBlock(mc);
        if (resolved == null) return; // Unknown source (e.g. plugin GUI); do nothing rather than index wrongly.

        Session s = new Session(cs, resolved);
        if (resolved.kind() == ContainerKind.ENDER_CHEST) {
            s.entry = index.enderChest();
            s.indexingAllowed = true;
        } else {
            s.entry = index.atBlock(resolved.dimension(), resolved.primary());
            Region region = regions.regionAt(resolved.dimension(), resolved.primary());
            s.indexingAllowed = region != null || (s.entry != null && s.entry.manualPin);
        }
        session = s;
        ContainerPinButton.attach(mc, cs, this);
        dev.warehouse.ui.ContainerOverlay.attach(mc, cs, s);
        ScreenEvents.remove(screen).register(sc -> onScreenRemoved(mc, s));
        ScreenEvents.afterTick(screen).register(sc -> {
            s.ticksOpen++;
            // Fallback: if we never saw a content packet but slots are populated after a tick, snapshot once.
            if (!s.receivedContents && s.ticksOpen == 2 && hasAnyItem(s.menu)) {
                s.receivedContents = true;
                snapshot(mc, s);
            }
        });
    }

    /** Called from the packet mixin after the menu contents were updated. */
    public void onContentPacket(int containerId) {
        Session s = session;
        if (s == null || s.menu.containerId != containerId) return;
        s.receivedContents = true;
        snapshot(Minecraft.getInstance(), s);
    }

    private void onScreenRemoved(Minecraft mc, Session s) {
        if (session == s) {
            snapshot(mc, s);
            session = null;
        }
    }

    private static boolean hasAnyItem(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) if (slot.hasItem()) return true;
        return false;
    }

    public void snapshot(Minecraft mc, Session s) {
        if (!s.indexingAllowed || mc.player == null) return;
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : s.menu.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            stacks.add(slot.getItem());
        }
        if (stacks.isEmpty()) return;
        ContainerEntry e = s.entry;
        if (e == null) {
            e = new ContainerEntry(UUID.randomUUID(), s.resolved.kind(), s.resolved.dimension(), s.resolved.primary());
            e.secondaryPos = s.resolved.secondary();
            s.entry = e;
        } else {
            e.kind = s.resolved.kind();
            e.pos = s.resolved.primary();
            e.secondaryPos = s.resolved.secondary();
        }
        Region region = regions.regionAt(s.resolved.dimension(), s.resolved.primary());
        e.regionId = region != null ? region.id : null;
        e.contents = snapshotter.snapshot(stacks);
        e.slotCount = stacks.size();
        e.lastSeenEpochMs = System.currentTimeMillis();
        String title = s.screen.getTitle().getString();
        e.customName = isDefaultTitle(title) ? null : title;
        index.upsert(e);
    }

    private static boolean isDefaultTitle(String title) {
        return switch (title) {
            case "Chest", "Large Chest", "Barrel", "Shulker Box", "Ender Chest", "Trapped Chest" -> true;
            default -> title.endsWith("Shulker Box");
        };
    }

    /** Toggle the manual pin for the open container and index immediately when pinning. */
    public void togglePin(Minecraft mc) {
        Session s = session;
        if (s == null) return;
        if (s.resolved.kind() == ContainerKind.ENDER_CHEST) return;
        boolean nowPinned = !(s.entry != null && s.entry.manualPin);
        if (nowPinned) {
            if (s.entry == null) {
                s.entry = new ContainerEntry(UUID.randomUUID(), s.resolved.kind(), s.resolved.dimension(), s.resolved.primary());
                s.entry.secondaryPos = s.resolved.secondary();
            }
            s.entry.manualPin = true;
            s.indexingAllowed = true;
            snapshot(mc, s);
            if (s.entry.contents.isEmpty()) index.upsert(s.entry);
            Chat.info("Pinned " + s.entry.label() + ".");
        } else {
            s.entry.manualPin = false;
            Region region = regions.regionAt(s.resolved.dimension(), s.resolved.primary());
            if (region == null) {
                index.remove(s.entry.id);
                s.indexingAllowed = false;
                Chat.info("Unpinned and forgot " + s.entry.label() + ".");
                s.entry = null;
            } else {
                index.fireChanged();
                Chat.info("Unpinned (still indexed: inside " + region.name + ").");
            }
        }
    }

    public boolean isPinned() {
        Session s = session;
        return s != null && s.entry != null && s.entry.manualPin;
    }

    public boolean isInsideRegion() {
        Session s = session;
        return s != null && regions.regionAt(s.resolved.dimension(), s.resolved.primary()) != null;
    }
}
