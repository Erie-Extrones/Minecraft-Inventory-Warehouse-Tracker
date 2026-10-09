package dev.warehouse.ui;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ModConfig;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemKey;
import dev.warehouse.organizer.Organizer;
import dev.warehouse.region.Region;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** "Stored in" / "Belongs in" tooltip lines. */
public final class TooltipProvider {
    private TooltipProvider() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register(TooltipProvider::append);
    }

    private static void append(ItemStack stack, net.minecraft.world.item.Item.TooltipContext ctx, net.minecraft.world.item.TooltipFlag flag, List<Component> lines) {
        ModConfig cfg = ConfigIO.get();
        if (!cfg.showTooltip || stack.isEmpty()) return;
        if (WarehouseClient.get() == null || !WarehouseClient.storage().isBound()) return;
        ItemKey key = Fingerprinter.key(stack);
        dev.warehouse.prices.Valuation.Value value = WarehouseClient.valuation().unitValue(key);
        if (value != null) {
            lines.add(Component.literal("Value: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(dev.warehouse.prices.Valuation.money(value.unit()) + " each" + (stack.getCount() > 1 ? ", " + dev.warehouse.prices.Valuation.money(value.unit() * stack.getCount()) + " here" : "")).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("  " + (value.sell() ? "sell" : "buy") + (value.fallback() ? " only" : "") + ", " + value.count() + " price" + (value.count() == 1 ? "" : "s")).withStyle(ChatFormatting.DARK_GRAY)));
        }
        List<ContainerEntry> holders = WarehouseClient.index().holding(key);
        Organizer organizer = WarehouseClient.organizer();
        Organizer.Resolution dest = organizer.resolve(key);
        if (holders.isEmpty() && dest == null) return;

        if (!holders.isEmpty()) {
            ContainerEntry best = holders.get(0);
            int total = WarehouseClient.index().totalCount(key);
            Region r = WarehouseClient.regions().byId(best.regionId);
            String where = (r != null ? r.name + " @ " : "") + best.posString();
            if (cfg.compactTooltip) {
                lines.add(Component.literal("Stored: " + where + " (×" + total + ")").withStyle(ChatFormatting.DARK_AQUA));
            } else {
                lines.add(Component.literal("Stored in: ").withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal(where).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" (×" + best.totalCount(key) + (holders.size() > 1 ? ", " + total + " total in " + holders.size() + " places" : "") + ")").withStyle(ChatFormatting.GRAY)));
                lines.add(Component.literal("  seen " + Staleness.describe(best.lastSeenEpochMs)).withStyle(Staleness.formatting(best.lastSeenEpochMs)));
            }
        }
        if (dest != null) {
            Region r = WarehouseClient.regions().byId(dest.container().regionId);
            String where = (r != null ? r.name + " @ " : "") + dest.container().posString();
            lines.add(Component.literal(cfg.compactTooltip ? "Belongs: " : "Belongs in: ").withStyle(ChatFormatting.DARK_GREEN)
                    .append(Component.literal(dest.category()).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" @ " + where).withStyle(ChatFormatting.GRAY)));
        } else if (organizer.plan().isActive()) {
            lines.add(Component.literal("Belongs in: ").withStyle(ChatFormatting.DARK_GREEN)
                    .append(Component.literal(WarehouseClient.categories().categoryOf(key) + " (no room)").withStyle(ChatFormatting.RED)));
        }
    }
}
