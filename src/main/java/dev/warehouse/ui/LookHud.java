package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerResolver;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.organizer.Plan;
import dev.warehouse.region.Region;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sneak while looking at a block: a small card next to the crosshair shows the warehouse stock of that
 * block's item (total, best locations, where it belongs). Looking at an indexed chest shows the chest instead.
 */
public final class LookHud {
    private record Line(String text, int color) {}

    private BlockPos lastPos;
    private ItemStack lastHeld = ItemStack.EMPTY;
    private long lastComputeMs;
    private ItemStack icon = ItemStack.EMPTY;
    private String title = "";
    private final List<Line> lines = new ArrayList<>();

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(WarehouseClient.MOD_ID, "look_hud"), this::render);
    }

    private void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!ConfigIO.get().lookHud || mc.player == null || mc.level == null || mc.gui.screen() != null) return;
        if (!mc.player.isShiftKeyDown() || !WarehouseClient.storage().isBound()) {
            lastPos = null;
            return;
        }
        HitResult hit = mc.hitResult;
        BlockPos pos = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK ? bhr.getBlockPos() : null;
        ItemStack held = mc.player.getMainHandItem();
        if (pos == null && held.isEmpty()) {
            lastPos = null;
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = !java.util.Objects.equals(pos, lastPos) || held.getCount() != lastHeld.getCount() || !ItemStack.isSameItemSameComponents(held, lastHeld);
        if (changed || now - lastComputeMs > 500) {
            lastPos = pos != null ? pos.immutable() : null;
            lastHeld = held.copy();
            lastComputeMs = now;
            compute(mc, pos, held);
        }
        if (lines.isEmpty() && title.isEmpty()) return;
        draw(g, mc.font);
    }

    /** Priority: an indexed chest you look at, then the item in your hand, then the block you look at. */
    private void compute(Minecraft mc, BlockPos pos, ItemStack held) {
        lines.clear();
        title = "";
        icon = ItemStack.EMPTY;
        if (pos != null) {
            BlockState state = mc.level.getBlockState(pos);
            ContainerResolver.Resolved res = ContainerResolver.isAllowlisted(state) ? ContainerResolver.resolveAt(mc, pos) : null;
            if (res != null) {
                ContainerEntry e = WarehouseClient.index().atBlock(res.dimension(), res.primary());
                describeContainer(e, res);
                return;
            }
        }
        if (!held.isEmpty()) {
            describeItem(held);
            return;
        }
        if (pos != null) {
            Item item = mc.level.getBlockState(pos).getBlock().asItem();
            if (item == null || item == Items.AIR) return;
            describeItem(new ItemStack(item));
        }
    }

    private void describeContainer(ContainerEntry e, ContainerResolver.Resolved res) {
        icon = new ItemStack(Minecraft.getInstance().level.getBlockState(res.primary()).getBlock().asItem());
        if (e == null) {
            title = res.kind().label();
            lines.add(new Line("Not indexed yet", 0xFFAAAAAA));
            lines.add(new Line(WarehouseClient.regions().regionAt(res.dimension(), res.primary()) != null ? "Open it once to index it" : "Outside regions: open and pin it", 0xFF808080));
            return;
        }
        title = e.label();
        Plan plan = WarehouseClient.organizer().plan();
        String zone = plan.isActive() ? plan.zoneOf(e.id) : null;
        if (zone != null) lines.add(new Line("Zone: " + zone, WarehouseClient.organizer().categoryColor(zone) | 0xFF000000));
        int total = e.slotCount > 0 ? e.slotCount : 27;
        lines.add(new Line(e.usedSlots() + "/" + total + " slots  ·  seen " + Staleness.describe(e.lastSeenEpochMs), Staleness.color(e.lastSeenEpochMs)));
        List<StackRecord> top = new ArrayList<>(e.contents);
        top.sort(Comparator.comparingInt((StackRecord s) -> s.count).reversed());
        int shown = 0;
        for (StackRecord s : top) {
            if (shown++ >= 6) {
                lines.add(new Line("+" + (top.size() - 6) + " more stacks", 0xFF808080));
                break;
            }
            lines.add(new Line("  " + s.displayName + " ×" + s.count, 0xFFDDDDDD));
        }
        int misplaced = WarehouseClient.planDiff().forContainer(e.id).size();
        if (misplaced > 0) lines.add(new Line(misplaced + " misplaced stack(s) here", 0xFFFF8080));
    }

    private void describeItem(ItemStack stack) {
        icon = stack;
        ItemKey key = Fingerprinter.key(stack);
        title = Fingerprinter.displayName(stack);
        var index = WarehouseClient.index();
        List<ContainerEntry> holders = index.holding(key);
        int total = index.totalCount(key);
        int inInventory = 0;
        var inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && key.equals(Fingerprinter.key(s))) inInventory += s.getCount();
        }
        if (holders.isEmpty()) {
            lines.add(new Line("None stored in the warehouse", 0xFFAAAAAA));
        } else {
            lines.add(new Line(total + " stored in " + holders.size() + " container" + (holders.size() == 1 ? "" : "s"), 0xFF80FF80));
            int shown = 0;
            for (ContainerEntry e : holders) {
                if (shown++ >= 3) {
                    lines.add(new Line("  +" + (holders.size() - 3) + " more places", 0xFF808080));
                    break;
                }
                Region r = WarehouseClient.regions().byId(e.regionId);
                lines.add(new Line("  ×" + e.totalCount(key) + "  " + (r != null ? r.name + " " : "") + e.posString() + "  " + Staleness.describe(e.lastSeenEpochMs), Staleness.color(e.lastSeenEpochMs)));
            }
        }
        if (inInventory > 0) lines.add(new Line(inInventory + " in your inventory", 0xFFDDDDDD));
        Organizer.Resolution dest = WarehouseClient.organizer().resolve(stack);
        if (dest != null) {
            lines.add(new Line("Belongs in: " + dest.category() + " @ " + dest.container().posString(), WarehouseClient.organizer().categoryColor(dest.category()) | 0xFF000000));
        } else if (WarehouseClient.organizer().plan().isActive()) {
            lines.add(new Line("Category: " + WarehouseClient.categories().categoryOf(key), 0xFFAAAAAA));
        }
    }

    private void draw(GuiGraphicsExtractor g, Font font) {
        int pad = 5;
        int w = font.width(title) + 24;
        for (Line l : lines) w = Math.max(w, font.width(l.text) + 2 * pad);
        int h = pad + 18 + lines.size() * 10 + pad - 2;
        int x = g.guiWidth() / 2 + 14;
        int y = g.guiHeight() / 2 + 14;
        if (x + w > g.guiWidth() - 4) x = g.guiWidth() / 2 - 14 - w;
        if (y + h > g.guiHeight() - 4) y = g.guiHeight() / 2 - 14 - h;
        g.fill(x, y, x + w, y + h, 0xB0101014);
        g.outline(x, y, w, h, 0x80FFFFFF);
        int tx = x + pad;
        if (!icon.isEmpty()) {
            g.item(icon, tx, y + pad);
            tx += 20;
        }
        g.text(font, title, tx, y + pad + 4, 0xFFFFFFFF);
        int ly = y + pad + 18;
        for (Line l : lines) {
            g.text(font, l.text, x + pad, ly, l.color);
            ly += 10;
        }
    }
}
