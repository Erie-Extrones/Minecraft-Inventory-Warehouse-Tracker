package dev.warehouse.organizer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/** Heuristic category + sub-family assignment for vanilla items. Overrides and plugin items are handled by {@link dev.warehouse.items.CategoryResolver}. */
public final class Categorizer {
    public static final String BUILDING = "Building Blocks";
    public static final String COLORED = "Colored Blocks";
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
    public static final String ENCHANTING = "Enchanting";
    public static final String WORKSTATIONS = "Workstations";
    public static final String TRANSPORT = "Transport";
    public static final String STORAGE = "Storage";
    public static final String KEYS = "Keys & Currency";
    public static final String INFINITE = "Infinite Items";
    public static final String COLLECTIBLES = "Collectibles";
    public static final String CUSTOM = "Custom";
    public static final String MISC = "Misc";

    public static final List<String> DEFAULT_ORDER = List.of(BUILDING, COLORED, NATURAL, WOOD, STONE, ORES, REDSTONE, TOOLS, ARMOR, FOOD, FARMING, MOB_DROPS,
            DECOR, POTIONS, ENCHANTING, WORKSTATIONS, TRANSPORT, STORAGE, KEYS, INFINITE, COLLECTIBLES, CUSTOM, MISC);

    private static final String[] WOOD_TYPES = {"pale_oak", "dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "bamboo", "crimson", "warped", "poplar"};
    private static final String[] COLORS = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] STONE_TYPES = {"deepslate", "cobblestone", "blackstone", "end_stone", "red_sandstone", "sandstone", "red_nether_brick", "nether_brick", "prismarine", "purpur", "quartz", "andesite", "diorite", "granite", "tuff", "basalt", "calcite", "dripstone", "mud_brick", "stone_brick", "smooth_stone", "stone"};
    private static final Set<String> WORKSTATION_IDS = Set.of("furnace", "blast_furnace", "smoker", "campfire", "soul_campfire", "crafting_table", "cartography_table", "fletching_table",
            "smithing_table", "grindstone", "stonecutter", "loom", "anvil", "chipped_anvil", "damaged_anvil", "lectern", "bell", "beacon", "conduit", "lodestone", "respawn_anchor",
            "jukebox", "scaffolding", "ladder", "crafter", "chiseled_bookshelf", "bookshelf", "note_block");
    private static final Set<String> ENCHANTING_IDS = Set.of("enchanted_book", "experience_bottle", "enchanting_table", "lapis_lazuli");
    private static final Set<String> MOB_DROP_IDS = Set.of("rotten_flesh", "bone", "gunpowder", "spider_eye", "ender_pearl", "ender_eye", "blaze_rod", "slime_ball", "leather", "feather",
            "ink_sac", "glow_ink_sac", "rabbit_hide", "phantom_membrane", "shulker_shell", "nautilus_shell", "prismarine_shard", "prismarine_crystals", "nether_star", "heart_of_the_sea",
            "scute", "armadillo_scute", "turtle_scute", "echo_shard", "ghast_tear", "magma_cream", "wind_charge", "breeze_rod", "trial_key", "ominous_trial_key", "heavy_core",
            "resin_clump", "string", "goat_horn", "frogspawn", "sniffer_egg", "turtle_egg", "dragon_egg", "totem_of_undying", "wither_skeleton_skull", "zombie_head", "skeleton_skull",
            "creeper_head", "dragon_head", "piglin_head", "player_head");

    private Categorizer() {}

    /** Category for a stack (vanilla heuristics only). */
    public static String categorize(ItemStack stack) {
        String path = itemPath(stack);
        if (stack.is(ItemTags.SHULKER_BOXES) || stack.is(ItemTags.BUNDLES) || path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel") || path.equals("ender_chest") || path.endsWith("_bundle") || path.endsWith("copper_chest"))
            return STORAGE;
        if (WORKSTATION_IDS.contains(path)) return WORKSTATIONS;
        if (ENCHANTING_IDS.contains(path)) return ENCHANTING;
        if (MOB_DROP_IDS.contains(path)) return MOB_DROPS;

        // Wearables: only true body armor counts; carpets, harnesses, pumpkins and heads are also "equippable".
        if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)
                || path.equals("elytra") || path.equals("wolf_armor") || path.endsWith("_smithing_template") && !path.startsWith("netherite"))
            return ARMOR;
        if (path.endsWith("_horse_armor") || path.endsWith("_harness") || path.equals("saddle") || path.equals("lead") || path.endsWith("_on_a_stick")
                || stack.is(ItemTags.RAILS) || stack.is(ItemTags.BOATS) || stack.is(ItemTags.CHEST_BOATS) || path.contains("minecart"))
            return TRANSPORT;
        if (stack.has(DataComponents.WEAPON) || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
                || path.equals("bow") || path.equals("crossbow") || path.equals("trident") || path.equals("mace") || path.equals("shield"))
            return TOOLS;
        if (stack.has(DataComponents.TOOL) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES) || path.equals("shears")
                || path.equals("flint_and_steel") || path.equals("fishing_rod") || path.equals("brush") || path.equals("spyglass") || path.endsWith("compass") || path.equals("clock")
                || path.equals("arrow") || path.endsWith("_arrow") || path.equals("firework_rocket"))
            return TOOLS;
        if (path.contains("potion") || path.equals("brewing_stand") || path.equals("blaze_powder") || path.equals("nether_wart") || path.equals("fermented_spider_eye")
                || path.equals("glistering_melon_slice") || path.equals("dragon_breath") || path.equals("glass_bottle") || path.equals("cauldron") || path.equals("rabbit_foot")
                || path.equals("ominous_bottle") || path.equals("honey_bottle"))
            return POTIONS;
        if (stack.has(DataComponents.FOOD) || path.equals("cake") || path.equals("milk_bucket") || path.equals("sugar") || path.endsWith("egg") && !path.contains("spawn") && !path.equals("dragon_egg"))
            return FOOD;
        if (path.contains("redstone") || path.equals("repeater") || path.equals("comparator") || path.contains("piston") || path.equals("observer") || path.equals("hopper")
                || path.equals("dropper") || path.equals("dispenser") || path.equals("lever") || path.endsWith("_button") || path.endsWith("pressure_plate") || path.contains("tripwire")
                || path.equals("daylight_detector") || path.equals("target") || path.contains("sculk_sensor") || path.equals("slime_block") || path.equals("honey_block") || path.equals("tnt")
                || path.contains("lightning_rod") || path.endsWith("copper_bulb"))
            return path.endsWith("_ore") ? ORES : REDSTONE;
        if (stack.is(ItemTags.DYES) || path.endsWith("_dye")) return DECOR;
        if (stack.is(ItemTags.COALS) || path.endsWith("_ore") || path.startsWith("raw_") || path.endsWith("_ingot") || path.endsWith("_nugget") || path.equals("diamond")
                || path.equals("emerald") || path.startsWith("amethyst") || path.equals("quartz") || path.equals("netherite_scrap") || path.equals("ancient_debris")
                || path.equals("netherite_upgrade_smithing_template") || path.equals("flint") || path.equals("glowstone_dust") || isMineralBlock(path))
            return ORES;
        if (path.contains("seeds") || path.equals("wheat") || path.equals("carrot") || path.equals("potato") || path.equals("beetroot") || path.equals("sugar_cane") || path.equals("bamboo")
                || path.equals("cactus") || path.equals("cocoa_beans") || path.equals("bone_meal") || path.contains("sapling") || path.contains("propagule") || path.equals("melon")
                || path.equals("pumpkin") || path.contains("mushroom") && !path.contains("stew") || path.equals("kelp") || path.equals("sea_pickle") || path.equals("hay_block")
                || path.equals("composter") || path.equals("honeycomb") || path.equals("torchflower") || path.equals("pitcher_pod"))
            return FARMING;
        if (isColoredBlock(stack, path)) return COLORED;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.WOODEN_SLABS) || stack.is(ItemTags.WOODEN_FENCES)
                || stack.is(ItemTags.FENCE_GATES) || stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOODEN_TRAPDOORS) || stack.is(ItemTags.SIGNS) || stack.is(ItemTags.HANGING_SIGNS)
                || path.equals("stick") || path.contains("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae") || path.endsWith("_stem") && isWoodType(path) || path.contains("bamboo"))
            return WOOD;
        if (stack.is(ItemTags.BANNERS) || stack.is(ItemTags.BEDS) || stack.is(ItemTags.CANDLES) || path.equals("painting") || path.contains("item_frame") || path.equals("armor_stand")
                || path.equals("flower_pot") || path.contains("lantern") || path.equals("torch") || path.equals("soul_torch") || path.equals("end_rod") || path.contains("candle")
                || path.contains("pottery_sherd") || path.equals("decorated_pot") || path.endsWith("_shelf") || path.endsWith("_cushion") || path.startsWith("music_disc")
                || path.endsWith("_banner_pattern") || path.equals("carved_pumpkin") || path.equals("jack_o_lantern") || path.endsWith("_head") || path.endsWith("_skull")
                || path.equals("chain") || path.endsWith("_chain") || path.contains("froglight") || path.equals("sea_lantern") || path.equals("glowstone"))
            return DECOR;
        if (stack.getItem() instanceof BlockItem bi) {
            Block block = bi.getBlock();
            BlockState st = block.defaultBlockState();
            if (st.is(BlockTags.LEAVES) || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.ICE)
                    || st.is(BlockTags.SNOW) || st.is(BlockTags.CORALS) || path.equals("grass_block") || path.equals("gravel") || path.equals("clay") || path.contains("moss")
                    || path.contains("vine") || path.contains("grass") || path.contains("fern") || path.contains("lily") || path.contains("bush") || path.equals("obsidian")
                    || path.equals("crying_obsidian") || path.equals("netherrack") || path.equals("soul_sand") || path.equals("soul_soil") || path.equals("magma_block")
                    || path.contains("nylium") || path.contains("shroomlight") || path.contains("fungus") || path.contains("roots") || path.contains("sponge") || path.contains("coral")
                    || path.equals("mud") || path.equals("packed_mud") || path.contains("dripleaf") || path.contains("azalea") || path.contains("sculk") || path.contains("snow")
                    || path.contains("ice") || path.contains("spore") || path.contains("leaf") || path.contains("flower") || path.equals("cobweb") || path.contains("petals")
                    || path.contains("tulip") || path.contains("orchid") || path.equals("poppy") || path.equals("dandelion") || path.contains("daisy") || path.equals("allium")
                    || path.equals("cornflower") || path.equals("lilac") || path.equals("peony") || path.contains("rose") || path.equals("sunflower") || path.contains("eyeblossom")
                    || path.contains("dead_") || path.equals("mycelium") || path.equals("podzol") || path.equals("dirt_path") || path.equals("farmland") || path.contains("suspicious_")
                    || path.contains("pointed_dripstone") || path.contains("amethyst_bud") || path.equals("budding_amethyst"))
                return NATURAL;
            if (isStoneVariant(path) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER) || stack.is(ItemTags.STONE_BRICKS) || stack.is(ItemTags.STONE_CRAFTING_MATERIALS))
                return STONE;
            return BUILDING;
        }
        if (path.equals("brick") || path.equals("nether_brick") || path.equals("resin_brick")) return BUILDING;
        if (path.equals("snowball") || path.equals("clay_ball")) return NATURAL;
        if (path.equals("popped_chorus_fruit") || path.equals("chorus_fruit")) return FOOD;
        return MISC;
    }

    private static boolean isColoredBlock(ItemStack stack, String path) {
        if (stack.is(ItemTags.WOOL) || stack.is(ItemTags.WOOL_CARPETS) || stack.is(ItemTags.TERRACOTTA) || stack.is(ItemTags.GLAZED_TERRACOTTA) || stack.is(ItemTags.CONCRETE)
                || stack.is(ItemTags.CONCRETE_POWDERS) || stack.is(ItemTags.WOOL_STAIRS) || stack.is(ItemTags.WOOL_SLABS) || stack.is(ItemTags.CONCRETE_STAIRS) || stack.is(ItemTags.CONCRETE_SLABS))
            return true;
        return path.contains("stained_glass") || path.equals("glass") || path.equals("glass_pane") || path.equals("tinted_glass") || path.endsWith("_wool") || path.endsWith("_carpet")
                || path.endsWith("_concrete") || path.endsWith("_concrete_powder") || path.endsWith("terracotta");
    }

    /** Sub-family key for adjacency ordering: wood type, color, stone variant, or the first path token. */
    public static String subFamily(ItemStack stack) {
        String path = itemPath(stack);
        for (String w : WOOD_TYPES) if (path.startsWith(w + "_") || path.startsWith("stripped_" + w)) return w;
        for (String c : COLORS) if (path.startsWith(c + "_")) return c;
        String stone = stoneType(path);
        if (stone != null) return stone;
        if (path.contains("copper")) return "copper";
        int i = path.indexOf('_');
        return i > 0 ? path.substring(0, i) : path;
    }

    public static String itemPath(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static boolean isWoodType(String path) {
        for (String w : WOOD_TYPES) if (path.startsWith(w)) return true;
        return false;
    }

    private static boolean isMineralBlock(String path) {
        return switch (path) {
            case "gold_block", "iron_block", "copper_block", "diamond_block", "emerald_block", "netherite_block", "lapis_block", "coal_block", "redstone_block", "raw_iron_block", "raw_gold_block", "raw_copper_block", "amethyst_block" -> true;
            default -> false;
        };
    }

    /** Stone family if the item is a stone-type block or its stairs/slabs/walls/bricks, else null. */
    private static String stoneType(String path) {
        String p = path;
        for (String prefix : new String[]{"polished_", "cracked_", "chiseled_", "smooth_", "cut_", "mossy_", "cobbled_", "infested_"}) {
            if (p.startsWith(prefix)) p = p.substring(prefix.length());
        }
        for (String s : STONE_TYPES) {
            if (p.equals(s) || p.startsWith(s + "_") || p.equals(s + "s")) {
                if (s.equals("stone") && (path.startsWith("stonecutter") || path.contains("redstone") || path.contains("glowstone") || path.contains("grindstone") || path.contains("lodestone") || path.contains("blackstone") || path.contains("sandstone") || path.contains("dripstone") || path.contains("end_stone")))
                    continue;
                return s;
            }
        }
        return null;
    }

    private static boolean isStoneVariant(String path) {
        return stoneType(path) != null;
    }
}
