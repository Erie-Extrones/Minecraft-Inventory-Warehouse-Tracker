package dev.warehouse.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

import java.util.Locale;

/** Identifies the per-server storage folder: sanitized server address, or sp-&lt;world folder&gt; for singleplayer. */
public final class ServerKey {
    private ServerKey() {}

    public static String current(Minecraft mc) {
        IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated != null) {
            String folder = integrated.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize().getParent().getFileName().toString();
            return "sp-" + sanitize(folder);
        }
        ServerData data = mc.getCurrentServer();
        if (data != null && data.ip != null && !data.ip.isBlank()) {
            return sanitize(data.ip.toLowerCase(Locale.ROOT));
        }
        if (mc.getConnection() != null && mc.getConnection().getConnection().getRemoteAddress() != null) {
            return sanitize(mc.getConnection().getConnection().getRemoteAddress().toString());
        }
        return "unknown";
    }

    public static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString();
        while (s.startsWith(".") || s.startsWith("_")) s = s.substring(1);
        return s.isEmpty() ? "unknown" : s;
    }
}
