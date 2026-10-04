package dev.warehouse.wand;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import dev.warehouse.region.RegionType;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Right-click with the configured wand: first click = corner A, second = corner B (creates region),
 * sneak-right-click cancels. Vanilla placement is suppressed while holding the wand.
 */
public final class WandHandler {
    public final PendingSelection selection = new PendingSelection();
    private final RegionManager regions;
    /** The last block the player interacted with and when; used by the container resolver. */
    public static BlockPos lastUseBlockPos;
    public static long lastUseBlockTimeMs;
    public static String lastUseBlockDimension;

    public WandHandler(RegionManager regions) {
        this.regions = regions;
    }

    public void register() {
        UseBlockCallback.EVENT.register(this::onUseBlock);
    }

    public static boolean isWand(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Identifier id = Identifier.tryParse(ConfigIO.get().wandItem);
        if (id == null) return false;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item != null && stack.is(item);
    }

    private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide()) return InteractionResult.PASS;
        // Record for the container resolver regardless of what is held.
        if (hand == InteractionHand.MAIN_HAND) {
            lastUseBlockPos = hit.getBlockPos();
            lastUseBlockTimeMs = System.currentTimeMillis();
            lastUseBlockDimension = RegionManager.dimensionId(level);
        }
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!isWand(player.getMainHandItem())) return InteractionResult.PASS;
        if (!WarehouseClient.storage().isBound()) return InteractionResult.PASS;

        BlockPos pos = hit.getBlockPos();
        String dim = RegionManager.dimensionId(level);

        if (player.isShiftKeyDown()) {
            if (!selection.isEmpty()) {
                selection.clear();
                Chat.info("Selection cancelled.");
            }
            return InteractionResult.SUCCESS;
        }

        if (selection.isEmpty() || !dim.equals(selection.dimension)) {
            selection.clear();
            selection.dimension = dim;
            selection.cornerA = pos;
            Chat.info("Corner A set to " + fmt(pos) + ". Right-click the opposite corner (sneak-click to cancel).");
            return InteractionResult.SUCCESS;
        }

        selection.cornerB = pos;
        String name = regions.nextAutoName(RegionType.WAREHOUSE);
        Region r = regions.create(name, RegionType.WAREHOUSE, dim, selection.cornerA, pos);
        selection.clear();
        Chat.send(Chat.prefix()
                .append(Component.literal("Created region ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(r.name).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + r.volume() + " blocks). Rename with ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("/warehouse region rename \"" + r.name + "\" <new name>").withStyle(ChatFormatting.GRAY)));
        WarehouseClient.onRegionsChanged();
        return InteractionResult.SUCCESS;
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
