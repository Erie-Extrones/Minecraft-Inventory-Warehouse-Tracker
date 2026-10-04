package dev.warehouse.organizer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Heuristic category + sub-family assignment for vanilla-ish items. Overrides are applied by {@link dev.warehouse.items.CategoryResolver}. */
public final class Categorizer {
    public static final String BUILDING = "Building Blocks";
    public static final String NATURAL = "Natural";
    public static final String WOOD = "Wood";
    public static final String STONE = "Stone";
    public static final String ORES = "Ores & Minerals";
    public static final String REDSTONE = "Redstone";
    public static final String TOOLS = "Tools & Weapons";
    public static final String ARMOR = "Armor";
    public static final String FOOD = "Food";
    public static final String FARMING = "Farming";
    public static final String MOB_DROPS = "Mob Drops";
    public static final String DECOR = "Dyes & Decoration";
    public static final String POTIONS = "Potions & Brewing";
    public static final String TRANSPORT = "Transport";
    public static final String STORAGE = "Storage";
    public static final String CUSTOM = "Custom";
    public static final String MISC = "Misc";

    public static final List<String> DEFAULT_ORDER = List.of(BUILDING, NATURAL, WOOD, STONE, ORES, REDSTONE, TOOLS, ARMOR, FOOD, FARMING, MOB_DROPS, DECOR, POTIONS, TRANSPORT, STORAGE, CUSTOM, MISC);

    private static final String[] WOOD_TYPES = {"pale_oak", "dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped", "poplar"};
    private static final String[] COLORS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] STONE_TYPES = {"deepslate", "cobblestone", "blackstone", "end_stone", "red_sandstone", "sandstone", "nether_brick", "prismarine", "purpur", "quartz", "andesite", "diorite", "granite", "tuff", "basalt", "calcite", "dripstone", "mud_brick", "stone_brick", "stone"};

    private Categorizer() {}

    /** Category for a stack (vanilla heuristics only). */
    public static String categorize(ItemStack stack) {
        String path = itemPath(stack);
        if (stack.is(ItemTags.SHULKER_BOXES) || stack.is(ItemTags.BUNDLES) || path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel") || path.equals("ender_chest") || path.endsWith("_bundle"))
            return STORAGE;
        // --- components first: they are the most reliable signal in 26.x ---
        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
        if (eq != null && eq.slot().isArmor()) return ARMOR;
        if (stack.has(DataComponents.WEAPON) || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS) || path.equals("bow") || path.equals("crossbow") || path.equals("trident") || path.equals("mace") || path.equals("shield"))
            return TOOLS;
        if (stack.has(DataComponents.TOOL) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES) || path.equals("shears") || path.equals("flint_and_steel") || path.equals("fishing_rod") || path.equals("brush") || path.equals("spyglass") || path.endsWith("compass") || path.equals("clock") || path.equals("lead") || path.equals("name_tag") || path.equals("elytra") || path.equals("totem_of_undying"))
            return TOOLS;
        if (path.equals("arrow") || path.endsWith("_arrow") || path.equals("firework_rocket")) return TOOLS;
        if (path.contains("potion") || path.equals("brewing_stand") || path.equals("blaze_powder") || path.equals("nether_wart") || path.equals("fermented_spider_eye") || path.equals("glistering_melon_slice") || path.equals("dragon_breath") || path.equals("phantom_membrane") || path.equals("ghast_tear") || path.equals("magma_cream") || path.equals("glass_bottle") || path.equals("experience_bottle") || path.equals("cauldron") || path.equals("rabbit_foot") || path.equals("turtle_scute") || path.equals("ominous_bottle") || path.equals("breeze_rod") || path.equals("wind_charge"))
            return POTIONS;
        if (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE) && !path.contains("potion") && !path.equals("milk_bucket") && !path.contains("bottle")) return FOOD;
        if (path.equals("milk_bucket") || path.equals("cake") || path.equals("sugar") || path.equals("egg") || path.endsWith("_egg") && !path.contains("spawn")) return FOOD;
        if (stack.is(ItemTags.RAILS) || stack.is(ItemTags.BOATS) || stack.is(ItemTags.CHEST_BOATS) || path.contains("minecart") || path.contains("saddle") || path.contains("harness") || path.equals("elytra") || path.contains("horse_armor"))
            return TRANSPORT;
        if (path.contains("redstone") || path.equals("repeater") || path.equals("comparator") || path.contains("piston") || path.equals("observer") || path.equals("hopper") || path.equals("dropper") || path.equals("dispenser") || path.equals("lever") || path.endsWith("_button") || path.endsWith("pressure_plate") || path.contains("tripwire") || path.equals("daylight_detector") || path.equals("target") || path.contains("sculk_sensor") || path.equals("note_block") || path.equals("slime_block") || path.equals("honey_block") || path.equals("tnt") || path.equals("lightning_rod") || path.equals("copper_bulb") || path.endsWith("copper_bulb") || path.equals("crafter") || path.equals("calibrated_sculk_sensor") || path.contains("trapped_chest"))
            return REDSTONE;
        if (stack.is(ItemTags.DYES) || path.endsWith("_dye")) return DECOR;
        if (stack.is(ItemTags.COALS) || path.contains("_ore") || path.startsWith("raw_") || path.endsWith("_ingot") || path.endsWith("_nugget") || path.equals("diamond") || path.equals("emerald") || path.equals("lapis_lazuli") || path.startsWith("amethyst") || path.equals("quartz") || path.contains("netherite") && !path.contains("block") || path.equals("ancient_debris") || path.endsWith("_block") && isMineralBlock(path) || path.equals("gold_block") || path.equals("iron_block") || path.equals("copper_block") || path.equals("diamond_block") || path.equals("emerald_block") || path.equals("netherite_block") || path.equals("lapis_block") || path.equals("coal_block") || path.equals("redstone_block") || path.equals("flint") || path.equals("glowstone_dust"))
            return ORES;
        if (path.contains("seeds") || path.equals("wheat") || path.equals("carrot") || path.equals("potato") || path.equals("beetroot") || path.equals("sugar_cane") || path.equals("bamboo") || path.equals("cactus") || path.equals("cocoa_beans") || path.equals("bone_meal") || path.equals("sweet_berries") || path.equals("glow_berries") || path.contains("sapling") || path.equals("melon_slice") || path.equals("pumpkin") || path.equals("melon") || path.contains("mushroom") && !path.contains("stew") || path.equals("kelp") || path.equals("sea_pickle") || path.equals("nether_wart") || path.equals("hay_block") || path.equals("composter") || path.equals("honeycomb") || path.equals("honey_bottle") || path.contains("propagule") || path.equals("torchflower") || path.equals("pitcher_pod") || path.equals("egg"))
            return FARMING;
        if (isMobDrop(path)) return MOB_DROPS;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.WOODEN_SLABS) || stack.is(ItemTags.WOODEN_FENCES) || stack.is(ItemTags.FENCE_GATES) || stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOODEN_TRAPDOORS) || stack.is(ItemTags.SIGNS) || stack.is(ItemTags.HANGING_SIGNS) || path.equals("stick") || path.contains("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae") || path.equals("ladder") || path.equals("bookshelf") || path.equals("crafting_table") || path.contains("bamboo"))
            return WOOD;
        if (stack.is(ItemTags.WOOL) || stack.is(ItemTags.WOOL_CARPETS) || stack.is(ItemTags.BANNERS) || stack.is(ItemTags.BEDS) || stack.is(ItemTags.CANDLES) || stack.is(ItemTags.TERRACOTTA) || stack.is(ItemTags.GLAZED_TERRACOTTA) || stack.is(ItemTags.CONCRETE) || stack.is(ItemTags.CONCRETE_POWDERS) || path.contains("stained_glass") || path.contains("glass_pane") || path.equals("glass") || path.contains("carpet") || path.equals("painting") || path.equals("item_frame") || path.equals("glow_item_frame") || path.equals("armor_stand") || path.equals("flower_pot") || path.contains("lantern") || path.equals("torch") || path.equals("soul_torch") || path.equals("end_rod") || path.contains("candle") || path.contains("pottery_sherd") || path.equals("decorated_pot") || path.contains("_head") || path.contains("_skull") || path.contains("shelf") || path.contains("glass"))
            return DECOR;
        if (stack.getItem() instanceof BlockItem bi) {
            Block block = bi.getBlock();
            BlockState st = block.defaultBlockState();
            if (st.is(BlockTags.LEAVES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.ICE) || st.is(BlockTags.SNOW) || st.is(BlockTags.CORALS) || path.equals("grass_block") || path.equals("gravel") || path.equals("clay") || path.contains("moss") || path.contains("vine") || path.contains("grass") || path.contains("fern") || path.contains("lily") || path.contains("bush") || path.equals("obsidian") || path.equals("crying_obsidian") || path.equals("netherrack") || path.equals("soul_sand") || path.equals("soul_soil") || path.equals("magma_block") || path.contains("nylium") || path.contains("shroomlight") || path.contains("fungus") || path.contains("roots") || path.contains("sponge") || path.contains("coral") || path.contains("mud") && !path.contains("brick") || path.contains("dripleaf") || path.contains("azalea") || path.contains("sculk") || path.equals("glowstone") || path.contains("snow") || path.contains("ice") || path.contains("spore") || path.contains("pumpkin") || path.contains("melon") || path.contains("leaf") || path.contains("flower"))
                return NATURAL;
            if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER) || st.is(BlockTags.STONE_BRICKS) || stack.is(ItemTags.STONE_BRICKS) || stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || isStoneVariant(path))
                return STONE;
            if (st.is(BlockTags.STAIRS) || st.is(BlockTags.SLABS) || st.is(BlockTags.WALLS) || st.is(BlockTags.FENCES) || st.is(BlockTags.DOORS) || st.is(BlockTags.TRAPDOORS) || path.contains("brick") || path.contains("copper") || path.contains("purpur") || path.contains("quartz") || path.contains("prismarine") || path.contains("_block") || path.contains("tiles") || path.contains("pillar") || path.contains("chiseled") || path.contains("polished") || path.contains("smooth") || path.contains("cut_"))
                return BUILDING;
            return BUILDING;
        }
        if (path.equals("string") || path.equals("book") || path.equals("paper") || path.equals("map") || path.equals("writable_book") || path.contains("bundle") || path.equals("bucket") || path.endsWith("_bucket")) return MISC;
        return MISC;
    }

    /** Sub-family key for adjacency ordering: wood type, color, stone variant, or the raw path. */
    public static String subFamily(ItemStack stack) {
        String path = itemPath(stack);
        for (String w : WOOD_TYPES) if (path.startsWith(w + "_") || path.startsWith("stripped_" + w)) return w;
        for (String c : COLORS) if (path.startsWith(c + "_")) return c;
        for (String s : STONE_TYPES) if (path.startsWith(s) || path.startsWith("polished_" + s) || path.startsWith("cracked_" + s) || path.startsWith("chiseled_" + s) || path.startsWith("smooth_" + s) || path.startsWith("cut_" + s) || path.startsWith("mossy_" + s)) return s;
        int i = path.indexOf('_');
        return i > 0 ? path.substring(0, i) : path;
    }

    public static String itemPath(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static boolean isMineralBlock(String path) {
        return path.startsWith("raw_") || path.equals("amethyst_block") || path.equals("budding_amethyst");
    }

    private static boolean isStoneVariant(String path) {
        for (String s : STONE_TYPES) if (path.equals(s) || path.startsWith(s + "_") || path.startsWith("polished_" + s) || path.startsWith("mossy_" + s) || path.startsWith("cracked_" + s) || path.startsWith("chiseled_" + s) || path.startsWith("smooth_" + s) || path.startsWith("cut_" + s) || path.equals("cobbled_" + s)) return true;
        return path.equals("cobbled_deepslate") || path.equals("mossy_cobblestone") || path.equals("end_stone");
    }

    private static boolean isMobDrop(String path) {
        return switch (path) {
            case "rotten_flesh", "bone", "gunpowder", "spider_eye", "ender_pearl", "blaze_rod", "slime_ball", "leather", "feather", "ink_sac", "glow_ink_sac",
                 "rabbit_hide", "phantom_membrane", "shulker_shell", "nautilus_shell", "prismarine_shard", "prismarine_crystals", "wither_skeleton_skull",
                 "zombie_head", "skeleton_skull", "creeper_head", "dragon_head", "piglin_head", "player_head", "nether_star", "heart_of_the_sea", "scute",
                 "armadillo_scute", "turtle_scute", "echo_shard", "ghast_tear", "magma_cream", "dragon_egg", "wind_charge", "breeze_rod", "trial_key",
                 "ominous_trial_key", "heavy_core", "sniffer_egg", "turtle_egg", "frogspawn", "resin_clump", "wolf_armor", "cod", "salmon", "tropical_fish",
                 "pufferfish", "goat_horn" -> true;
            default -> path.endsWith("_tear") || path.endsWith("_shell") || path.endsWith("_hide");
        };
    }
}
