package dev.warehouse.claims;

import dev.warehouse.Chat;
import net.minecraft.client.Minecraft;

/** Sends /claimlist and arms the parser. */
public final class ClaimImporter {
    private final ClaimListParser parser;

    public ClaimImporter(ClaimListParser parser) {
        this.parser = parser;
    }

    public void run(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return;
        parser.arm();
        mc.player.connection.sendCommand("claimlist");
        Chat.info("Sent /claimlist, parsing for a few seconds...");
    }
}
