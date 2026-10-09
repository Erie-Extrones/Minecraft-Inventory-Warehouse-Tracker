package dev.warehouse.prices;

import dev.warehouse.WarehouseClient;
import dev.warehouse.config.ConfigIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads ChestShop-style signs you look at: line 1 owner, line 2 quantity, line 3 "B 10 : S 5", line 4 item. */
public final class SignPriceReader {
    private static final Pattern BUY = Pattern.compile("(?i)\\bB\\s*:?\\s*([\\d.]+)");
    private static final Pattern SELL = Pattern.compile("(?i)\\bS\\s*:?\\s*([\\d.]+)");
    private static final long REPEAT_MS = 10 * 60_000L;

    private final Map<BlockPos, Long> seen = new HashMap<>();
    private int ticks;

    public void tick(Minecraft mc) {
        if (++ticks % 10 != 0 || !ConfigIO.get().capturePricesFromSigns || mc.level == null || mc.player == null || !WarehouseClient.storage().isBound()) return;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        long now = System.currentTimeMillis();
        Long last = seen.get(pos);
        if (last != null && now - last < REPEAT_MS) return;
        seen.put(pos.immutable(), now);
        if (seen.size() > 500) seen.clear();
        List<Component> msgs = sign.getText(SignTextSlot.FRONT).getMessages(false);
        if (msgs.size() < 4) return;
        String owner = msgs.get(0).getString().trim();
        String qtyLine = msgs.get(1).getString().trim();
        String priceLine = msgs.get(2).getString().trim();
        String item = msgs.get(3).getString().trim();
        if (item.isEmpty() || !qtyLine.matches("\\d+")) return;
        int qty = Math.max(1, Integer.parseInt(qtyLine));
        Matcher b = BUY.matcher(priceLine), s = SELL.matcher(priceLine);
        boolean any = false;
        ShopChatParser parser = WarehouseClient.shopChatParser();
        String shop = owner.isEmpty() ? null : owner.replaceAll("[\\[\\]]", "");
        if (b.find()) {
            parser.record(item, Double.parseDouble(b.group(1)) / qty, qty, false, shop, "sign");
            any = true;
        }
        if (s.find()) {
            parser.record(item, Double.parseDouble(s.group(1)) / qty, qty, true, shop, "sign");
            any = true;
        }
        if (!any) seen.remove(pos);
    }
}
