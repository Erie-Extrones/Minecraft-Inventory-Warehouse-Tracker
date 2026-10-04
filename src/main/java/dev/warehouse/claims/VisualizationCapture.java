package dev.warehouse.claims;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.config.ModConfig;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import dev.warehouse.storage.JsonStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GriefPrevention shows claim bounds with fake client-side block changes (glowstone corners, gold edges).
 * We watch incoming block updates: a corner block appearing where the real block is something else is
 * a fake corner. Once the burst settles we derive the box and bind it to the anchor sharing the lesser corner.
 */
public final class VisualizationCapture {
    private final JsonStore<List<ClaimAnchor>> claims;
    private final RegionManager regions;
    private final List<BlockPos> corners = new ArrayList<>();
    private String dimension;
    private long lastCornerMs;

    public VisualizationCapture(JsonStore<List<ClaimAnchor>> claims, RegionManager regions) {
        this.claims = claims;
        this.regions = regions;
    }

    /** Called from the packet mixin, on the client thread, before the fake state is applied. */
    public void onBlockUpdate(Minecraft mc, BlockPos pos, BlockState incoming) {
        if (mc.level == null || !WarehouseClient.storage().isBound()) return;
        ModConfig cfg = ConfigIO.get();
        Block block = incoming.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String ids = id.toString();
        boolean normal = ids.equals(cfg.claimCornerBlock);
        boolean sub = ids.equals(cfg.subdivisionCornerBlock);
        boolean admin = ids.equals(cfg.adminClaimCornerBlock);
        if (!normal && !((sub || admin) && cfg.captureSubdivisions)) return;
        BlockState real = mc.level.getBlockState(pos);
        if (real.getBlock() == block) return; // real block of that type, not a visualization
        String dim = RegionManager.dimensionId(mc.level);
        if (dimension != null && !dimension.equals(dim)) corners.clear();
        dimension = dim;
        corners.add(pos.immutable());
        lastCornerMs = System.currentTimeMillis();
    }

    public void tick(Minecraft mc) {
        if (corners.isEmpty()) return;
        if (System.currentTimeMillis() - lastCornerMs < ConfigIO.get().visualizationSettleMs) return;
        finalizeCapture(mc);
    }

    private void finalizeCapture(Minecraft mc) {
        List<BlockPos> pts = new ArrayList<>(corners);
        corners.clear();
        if (pts.size() < 2 || mc.level == null) return;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : pts) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (maxX - minX < 1 || maxZ - minZ < 1) return;
        int worldMin = mc.level.getMinY();
        int worldMax = mc.level.getMaxY();
        String dim = dimension;

        ClaimAnchor match = null;
        for (ClaimAnchor a : claims.get()) {
            if (a.lesserX == minX && a.lesserZ == minZ && worldMatches(a.world, dim)) {
                match = a;
                break;
            }
        }
        if (match == null) {
            // Try without the world check (GP world names rarely equal the dimension id).
            for (ClaimAnchor a : claims.get()) {
                if (a.lesserX == minX && a.lesserZ == minZ) {
                    match = a;
                    break;
                }
            }
        }

        // Replace a previous capture of the same box.
        Region existing = null;
        for (Region r : regions.ofType(RegionType.CLAIM)) {
            if (r.dimension.equals(dim) && r.minX == minX && r.minZ == minZ && r.maxX == maxX && r.maxZ == maxZ) {
                existing = r;
                break;
            }
        }
        if (existing != null) {
            if (match != null && match.boundRegionId == null) {
                match.boundRegionId = existing.id;
                existing.name = match.displayName();
                claims.markDirty();
                regions.markDirty();
            }
            Chat.info("Claim bounds already known: " + existing.name);
            return;
        }

        String name;
        if (match != null && match.boundRegionId != null) {
            Region old = regions.byId(match.boundRegionId);
            name = old != null ? old.name : match.displayName();
            if (old != null) regions.deleteById(old.id);
        } else if (match != null) {
            name = match.displayName();
        } else {
            name = regions.nextAutoName(RegionType.CLAIM).replace("Claim ", "Claim (unmatched) ");
        }
        Region region = regions.create(name, RegionType.CLAIM, dim, new BlockPos(minX, worldMin, minZ), new BlockPos(maxX, worldMax, maxZ));
        if (match != null) {
            match.boundRegionId = region.id;
            claims.markDirty();
        }
        Chat.info("Captured claim bounds: " + region.name + " (" + (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + ")"
                + (match == null ? " — no matching /claimlist anchor; run /warehouse claims import" : ""));
        WarehouseClient.onRegionsChanged();
    }

    private static boolean worldMatches(String gpWorld, String dimensionId) {
        String w = gpWorld.toLowerCase();
        String d = dimensionId.toLowerCase();
        if (d.endsWith(":overworld")) return !w.endsWith("_nether") && !w.endsWith("_the_end");
        if (d.endsWith(":the_nether")) return w.endsWith("_nether") || w.contains("nether");
        if (d.endsWith(":the_end")) return w.endsWith("_the_end") || w.contains("end");
        return true;
    }

    public UUID anyBoundId() {
        for (ClaimAnchor a : claims.get()) if (a.boundRegionId != null) return a.boundRegionId;
        return null;
    }
}
