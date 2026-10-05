package dev.warehouse.index;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Chests get broken and moved while reorganising. Every second this checks a slice of the indexed block
 * containers near the player against the real world and forgets the ones that are no longer containers,
 * so plans, routes and guides stop pointing at empty air.
 */
public final class StaleContainerSweeper {
    private static final int RADIUS = 64;
    private static final int PER_PASS = 48;

    private final ContainerIndex index;
    private int cursor;
    private int tick;
    private int removedSinceReport;
    private long lastReportMs;

    public StaleContainerSweeper(ContainerIndex index) {
        this.index = index;
    }

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !WarehouseClient.storage().isBound()) return;
        if (++tick % 20 != 0) return;
        List<ContainerEntry> all = index.all();
        if (all.isEmpty()) return;
        String dim = RegionManager.dimensionId(mc.level);
        BlockPos me = mc.player.blockPosition();
        List<ContainerEntry> gone = new ArrayList<>();
        boolean changed = false;
        int checked = 0;
        int n = all.size();
        for (int i = 0; i < n && checked < PER_PASS; i++) {
            ContainerEntry e = all.get((cursor + i) % n);
            if (e.kind.isEntity() || e.kind == ContainerKind.ENDER_CHEST || !dim.equals(e.dimension)) continue;
            if (e.pos.distManhattan(me) > RADIUS || !mc.level.isLoaded(e.pos)) continue;
            checked++;
            ContainerResolver.Resolved res = ContainerResolver.resolveAt(mc, e.pos);
            if (res == null) {
                // Primary half is gone; a double chest may survive as a single at the other half.
                if (e.secondaryPos != null && mc.level.isLoaded(e.secondaryPos)) {
                    ContainerResolver.Resolved other = ContainerResolver.resolveAt(mc, e.secondaryPos);
                    if (other != null) {
                        e.pos = other.primary();
                        e.secondaryPos = other.secondary();
                        e.kind = other.kind();
                        e.slotCount = dev.warehouse.organizer.ChestDiscovery.defaultSlots(other.kind());
                        changed = true;
                        continue;
                    }
                }
                gone.add(e);
            } else if (res.kind() != e.kind || !res.primary().equals(e.pos) || !java.util.Objects.equals(res.secondary(), e.secondaryPos)) {
                e.kind = res.kind();
                e.pos = res.primary();
                e.secondaryPos = res.secondary();
                if (e.slotCount == 0 || e.kind != res.kind()) e.slotCount = dev.warehouse.organizer.ChestDiscovery.defaultSlots(res.kind());
                changed = true;
            }
        }
        cursor = (cursor + PER_PASS) % Math.max(1, n);
        if (!gone.isEmpty()) {
            for (ContainerEntry e : gone) all.remove(e);
            removedSinceReport += gone.size();
            changed = true;
        }
        if (changed) index.fireChanged();
        if (removedSinceReport > 0 && System.currentTimeMillis() - lastReportMs > 5000) {
            Chat.info("Forgot " + removedSinceReport + " container(s) that no longer exist. Run /warehouse plan replan when you are done rearranging.");
            removedSinceReport = 0;
            lastReportMs = System.currentTimeMillis();
        }
    }
}
