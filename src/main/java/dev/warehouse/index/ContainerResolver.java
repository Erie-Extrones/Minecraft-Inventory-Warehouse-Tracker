package dev.warehouse.index;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.region.RegionManager;
import dev.warehouse.wand.WandHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/** Which allow-listed block did the player just open? Timing based; misses are tolerated, wrong answers are not. */
public final class ContainerResolver {
    public record Resolved(ContainerKind kind, String dimension, BlockPos pos, @Nullable BlockPos secondaryPos) {
        /** Canonical primary position for double chests: the smaller coordinate. */
        public BlockPos primary() {
            if (secondaryPos == null) return pos;
            return compare(pos, secondaryPos) <= 0 ? pos : secondaryPos;
        }

        public BlockPos secondary() {
            if (secondaryPos == null) return null;
            return compare(pos, secondaryPos) <= 0 ? secondaryPos : pos;
        }

        private static int compare(BlockPos a, BlockPos b) {
            if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
            if (a.getZ() != b.getZ()) return Integer.compare(a.getZ(), b.getZ());
            return Integer.compare(a.getY(), b.getY());
        }
    }

    private ContainerResolver() {}

    public static @Nullable Resolved resolveRecentlyUsedBlock(Minecraft mc) {
        if (mc.level == null) return null;
        BlockPos pos = WandHandler.lastUseBlockPos;
        if (pos == null) return null;
        long age = System.currentTimeMillis() - WandHandler.lastUseBlockTimeMs;
        if (age > ConfigIO.get().containerResolveWindowMs) return null;
        String dim = RegionManager.dimensionId(mc.level);
        if (!dim.equals(WandHandler.lastUseBlockDimension)) return null;
        return resolveAt(mc, pos);
    }

    public static @Nullable Resolved resolveAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return null;
        String dim = RegionManager.dimensionId(mc.level);
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof ChestBlock) {
            if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
                return new Resolved(ContainerKind.DOUBLE_CHEST, dim, pos.immutable(), other.immutable());
            }
            return new Resolved(ContainerKind.CHEST, dim, pos.immutable(), null);
        }
        if (block instanceof BarrelBlock) return new Resolved(ContainerKind.BARREL, dim, pos.immutable(), null);
        if (block instanceof ShulkerBoxBlock) return new Resolved(ContainerKind.SHULKER_BOX, dim, pos.immutable(), null);
        if (block instanceof EnderChestBlock) return new Resolved(ContainerKind.ENDER_CHEST, dim, pos.immutable(), null);
        return null;
    }

    public static boolean isAllowlisted(BlockState state) {
        Block b = state.getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
    }
}
