package dev.warehouse;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Small helpers for client-side chat feedback. */
public final class Chat {
    private Chat() {}

    public static MutableComponent prefix() {
        return Component.literal("[Warehouse] ").withStyle(ChatFormatting.AQUA);
    }

    public static void info(String msg) {
        send(prefix().append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
    }

    public static void warn(String msg) {
        send(prefix().append(Component.literal(msg).withStyle(ChatFormatting.YELLOW)));
    }

    public static void error(String msg) {
        send(prefix().append(Component.literal(msg).withStyle(ChatFormatting.RED)));
    }

    public static void send(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(c);
    }
}
