package dev.warehouse.ui;

import net.minecraft.ChatFormatting;

/** Formats "last seen" ages and picks a color: fresh (< 1h), hours (< 1d), days. */
public final class Staleness {
    private Staleness() {}

    public static String describe(long epochMs) {
        if (epochMs <= 0) return "never opened";
        long s = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    public static ChatFormatting formatting(long epochMs) {
        if (epochMs <= 0) return ChatFormatting.DARK_GRAY;
        long s = (System.currentTimeMillis() - epochMs) / 1000;
        if (s < 3600) return ChatFormatting.GREEN;
        if (s < 86400) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    public static int color(long epochMs) {
        if (epochMs <= 0) return 0xFF707070;
        long s = (System.currentTimeMillis() - epochMs) / 1000;
        if (s < 3600) return 0xFF70E070;
        if (s < 86400) return 0xFFE0D060;
        return 0xFFE07060;
    }
}
