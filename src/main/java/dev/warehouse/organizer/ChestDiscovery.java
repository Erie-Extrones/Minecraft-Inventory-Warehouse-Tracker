package dev.warehouse.organizer;

import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerIndex;
import dev.warehouse.index.ContainerKind;
import dev.warehouse.index.ContainerResolver;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Finds every allow-listed container block inside Warehouse regions from loaded chunks and registers it in the index. */
public final class ChestDiscovery {
    public record Report(int found, int added, int chunksTotal, int chunksUnloaded, List<Region> regions) {
        public String message() {
            StringBuilder sb = new StringBuilder("Found " + found + " container(s)");
            if (added > 0) sb.append(" (" + added + " new)");
            if (chunksUnloaded > 0) sb.append(" — " + chunksUnloaded + "/" + chunksTotal + " chunks not loaded; walk the area and rerun");
            return sb.toString();
        }
    }

    private final ContainerIndex index;
    private final RegionManager regions;

    public ChestDiscovery(ContainerIndex index, RegionManager regions) {
        this.index = index;
        this.regions = regions;
    }

    public Report run(Minecraft mc) {
        if (mc.level == null) return new Report(0, 0, 0, 0, List.of());
        String dim = RegionManager.dimensionId(mc.level);
        List<Region> wh = new ArrayList<>();
        for (Region r : regions.ofType(RegionType.WAREHOUSE)) if (r.dimension.equals(dim)) wh.add(r);
        return run(mc, wh);
    }

    /** Scan the given regions (any type) in the current dimension. */
    public Report run(Minecraft mc, List<Region> toScan) {
        if (mc.level == null) return new Report(0, 0, 0, 0, List.of());
        String dim = RegionManager.dimensionId(mc.level);
        List<Region> wh = new ArrayList<>();
        for (Region r : toScan) if (r.dimension.equals(dim)) wh.add(r);
        int found = 0, added = 0, chunksTotal = 0, chunksUnloaded = 0;
        Set<BlockPos> handled = new HashSet<>();
        boolean changed = false;
        for (Region r : wh) {
            int cx0 = r.minX >> 4, cx1 = r.maxX >> 4, cz0 = r.minZ >> 4, cz1 = r.maxZ >> 4;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    chunksTotal++;
                    LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        chunksUnloaded++;
                        continue;
                    }
                    for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                        BlockPos pos = be.getBlockPos();
                        if (!r.contains(dim, pos) || handled.contains(pos)) continue;
                        BlockState state = be.getBlockState();
                        if (!ContainerResolver.isAllowlisted(state)) continue;
                        ContainerResolver.Resolved res = ContainerResolver.resolveAt(mc, pos);
                        if (res == null) continue;
                        handled.add(res.primary());
                        if (res.secondary() != null) handled.add(res.secondary());
                        found++;
                        ContainerEntry e = index.atBlock(dim, res.primary());
                        if (e == null && res.secondary() != null) e = index.atBlock(dim, res.secondary());
                        if (e == null) {
                            e = new ContainerEntry(UUID.randomUUID(), res.kind(), dim, res.primary());
                            e.secondaryPos = res.secondary();
                            e.slotCount = defaultSlots(res.kind());
                            e.lastSeenEpochMs = 0;
                            e.regionId = r.id;
                            index.all().add(e);
                            added++;
                            changed = true;
                        } else {
                            if (e.kind != res.kind() || e.regionId == null || !e.regionId.equals(r.id)) {
                                e.kind = res.kind();
                                e.pos = res.primary();
                                e.secondaryPos = res.secondary();
                                e.regionId = r.id;
                                if (e.slotCount == 0) e.slotCount = defaultSlots(res.kind());
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        if (changed) index.fireChanged();
        return new Report(found, added, chunksTotal, chunksUnloaded, wh);
    }

    public static int defaultSlots(ContainerKind kind) {
        return switch (kind) {
            case DOUBLE_CHEST -> 54;
            case CHEST, BARREL, SHULKER_BOX, ENDER_CHEST -> 27;
            case ARMOR_STAND -> 6;
            case ITEM_FRAME -> 1;
            case OTHER -> 27;
        };
    }
}
