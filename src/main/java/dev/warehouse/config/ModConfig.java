package dev.warehouse.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Global, server-independent configuration. Persisted to config/warehouse/config.json. */
public final class ModConfig {
    /** Item id held in main hand to draw warehouse regions. */
    public String wandItem = "minecraft:dead_brain_coral";

    /** Draw region outlines in world. */
    public boolean showRegionOutlines = true;

    /** Colors as 0xAARRGGBB. */
    public int warehouseColor = 0xFF40C8FF;
    public int claimColor = 0xFF60FF80;
    public int pendingSelectionColor = 0xFFFFD040;
    public int highlightColor = 0xFFFF4080;

    /** Tooltip: compact single line vs full lines. */
    public boolean compactTooltip = false;
    public boolean showTooltip = true;

    /** Lost log expiry in minutes. */
    public int lostExpiryMinutes = 5;
    public int deathLostExpiryMinutes = 60;

    /** Milliseconds to accept a UseBlock interaction as the source of a newly opened container screen. */
    public int containerResolveWindowMs = 500;

    /** How many ticks between entity (armor stand / item frame) scans. */
    public int entityScanIntervalTicks = 60;
    /** Remove entity container entries not found after this many scans (while chunk loaded). */
    public int entityMissingScansBeforeRemove = 5;

    // ---- GriefPrevention claim import ----
    /** Block id GP sends as fake corner block for normal claims. */
    public String claimCornerBlock = "minecraft:glowstone";
    /** Block id GP sends as fake corner block for subdivisions. */
    public String subdivisionCornerBlock = "minecraft:iron_block";
    /** Block id GP sends as fake corner block for admin claims. */
    public String adminClaimCornerBlock = "minecraft:carved_pumpkin";
    public boolean captureSubdivisions = false;
    public boolean autoReimportClaimsOnJoin = false;
    /** Hide the raw /claimlist chat output while the parser is armed. */
    public boolean hideClaimListChat = false;
    /** How long after /claimlist is sent to keep parsing chat (ms). */
    public int claimListParseWindowMs = 3000;
    /** Quiet period after the last fake corner block before a claim visualization is finalized (ms). */
    public int visualizationSettleMs = 1000;

    // ---- Custom item fingerprinting ----
    /** Data component ids stripped before hashing (volatile / noise). */
    public List<String> strippedComponents = new ArrayList<>(Arrays.asList(
            "minecraft:damage",
            "minecraft:repair_cost",
            "minecraft:enchantment_glint_override",
            "minecraft:custom_name"
    ));
    /** Lore lines matching any of these regexes are removed before hashing. */
    public List<String> strippedLorePatterns = new ArrayList<>(Arrays.asList(
            "(?i)durability\\s*[:：]?\\s*\\d+\\s*/\\s*\\d+",
            "(?i)^\\s*owner\\s*[:：].*$",
            "(?i)^\\s*bound to\\s*[:：]?.*$",
            "(?i)^\\s*soulbound.*$"
    ));

    // ---- Organizer ----
    /** Slot headroom multiplier when estimating category volume. */
    public double volumeHeadroom = 1.5;
    /** Highlight duration in seconds after clicking a search result. */
    public int highlightSeconds = 30;
    /** Minimum stack count filter default for misplaced tab. */
    public int misplacedMinCount = 1;

    // ---- Clear inventory mode essentials ----
    public boolean essentialEquippedArmor = true;
    public boolean essentialOffhand = true;
    public boolean essentialHotbarTools = true;
    public boolean essentialFood = true;
    public boolean essentialCustomGear = true;
    /** Item ids or group ids always kept in inventory. */
    public List<String> alwaysKeep = new ArrayList<>(Arrays.asList(
            "minecraft:torch",
            "minecraft:ender_chest",
            "minecraft:shulker_box"
    ));

    // ---- Categories ----
    /** Category name -> ARGB color used when previewing plan zones in-world. */
    public Map<String, Integer> categoryColors = defaultCategoryColors();

    /** Item id or group id -> category. User-defined overrides shared across servers. */
    public Map<String, String> categoryOverrides = new LinkedHashMap<>();

    public static Map<String, Integer> defaultCategoryColors() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Building Blocks", 0xFFB0B0B0);
        m.put("Natural", 0xFF4CAF50);
        m.put("Wood", 0xFF8D6E63);
        m.put("Stone", 0xFF78909C);
        m.put("Ores & Minerals", 0xFF00BCD4);
        m.put("Redstone", 0xFFE53935);
        m.put("Tools & Weapons", 0xFF90CAF9);
        m.put("Armor", 0xFF7986CB);
        m.put("Food", 0xFFFFB74D);
        m.put("Farming", 0xFFCDDC39);
        m.put("Mob Drops", 0xFFAB47BC);
        m.put("Dyes & Decoration", 0xFFF06292);
        m.put("Potions & Brewing", 0xFF9575CD);
        m.put("Transport", 0xFF26A69A);
        m.put("Custom", 0xFFFFD700);
        m.put("Misc", 0xFF9E9E9E);
        return m;
    }
}
