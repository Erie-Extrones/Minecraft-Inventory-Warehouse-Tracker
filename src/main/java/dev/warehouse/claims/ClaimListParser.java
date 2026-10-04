package dev.warehouse.claims;

import dev.warehouse.Chat;
import dev.warehouse.config.ConfigIO;
import dev.warehouse.storage.JsonStore;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses GriefPrevention /claimlist output while armed. */
public final class ClaimListParser {
    /** e.g. "world: x123, z-456 (2500 blocks)". GP prints only the lesser corner and the area. */
    private static final Pattern LINE = Pattern.compile("^(\\S+): x(-?\\d+), z(-?\\d+) \\((-?\\d+) blocks\\)$");
    private static final Pattern COLOR_CODES = Pattern.compile("§.");

    private final JsonStore<List<ClaimAnchor>> store;
    private long armedUntilMs;
    private final List<ClaimAnchor> parsedThisRun = new ArrayList<>();
    private boolean announced;

    public ClaimListParser(JsonStore<List<ClaimAnchor>> store) {
        this.store = store;
    }

    public void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || !isArmed()) return true;
            boolean matched = handle(message);
            return !(matched && ConfigIO.get().hideClaimListChat);
        });
    }

    public void arm() {
        armedUntilMs = System.currentTimeMillis() + ConfigIO.get().claimListParseWindowMs;
        parsedThisRun.clear();
        announced = false;
    }

    public boolean isArmed() {
        return System.currentTimeMillis() < armedUntilMs;
    }

    /** Called every tick to announce a finished import once the window closes. */
    public void tick() {
        if (!announced && armedUntilMs != 0 && !isArmed()) {
            announced = true;
            if (parsedThisRun.isEmpty()) {
                Chat.warn("No claims parsed from /claimlist. Is GriefPrevention installed, and does the output match 'world: x#, z# (# blocks)'?");
            } else {
                int withBounds = 0;
                for (ClaimAnchor a : store.get()) if (a.boundRegionId != null) withBounds++;
                Chat.info("Imported " + parsedThisRun.size() + " claim anchor(s); " + withBounds + "/" + store.get().size()
                        + " have bounds. Walk to each claim and right-click a block with a stick to capture its bounds.");
            }
        }
    }

    /** @return true if the message was a claim line. */
    boolean handle(Component message) {
        String raw = COLOR_CODES.matcher(message.getString()).replaceAll("").trim();
        Matcher m = LINE.matcher(raw);
        if (!m.matches()) return false;
        ClaimAnchor anchor = new ClaimAnchor(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        parsedThisRun.add(anchor);
        boolean exists = false;
        for (ClaimAnchor a : store.get()) {
            if (a.sameAnchor(anchor)) {
                a.area = anchor.area;
                a.importedEpochMs = anchor.importedEpochMs;
                exists = true;
                break;
            }
        }
        if (!exists) store.get().add(anchor);
        store.markDirty();
        return true;
    }
}
