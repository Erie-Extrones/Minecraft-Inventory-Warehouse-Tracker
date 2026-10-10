package dev.warehouse.craft;

import dev.warehouse.Chat;
import dev.warehouse.WarehouseClient;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.index.ContainerKind;
import dev.warehouse.index.StackRecord;
import dev.warehouse.items.ItemKey;
import dev.warehouse.items.NestedContents;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "I want N of this": expands recipes down to what you must actually obtain, using what is in your inventory first and
 * the warehouse second, crafting intermediates when their ingredients can be found, and listing what is missing.
 * Only plain (non-fingerprinted) items count as ingredients.
 */
public final class CraftPlanner {
    /** A base item the plan consumes: how many, where they come from, and how many nobody has. */
    public record Line(Item item, ItemStack icon, String name, int need, int inventory, int warehouse, int missing, List<ContainerEntry> where, Map<UUID, Integer> perChest) {
        public boolean satisfiedByInventory() {
            return inventory >= need;
        }

        public boolean covered() {
            return missing == 0;
        }
    }

    /** An intermediate (or the target) the plan crafts. */
    public record Step(ItemStack result, String name, int crafts, int makes, String kind, ItemStack station) {}

    public record Plan(ItemStack target, int count, List<Line> lines, List<Step> steps, List<String> notes, long createdMs) {
        public boolean complete() {
            for (Line l : lines) if (!l.covered()) return false;
            return true;
        }

        public boolean gathered() {
            for (Line l : lines) if (!l.satisfiedByInventory()) return false;
            return true;
        }

        public int missingLines() {
            int n = 0;
            for (Line l : lines) if (!l.covered()) n++;
            return n;
        }
    }

    private static final int MAX_DEPTH = 6, MAX_NODES = 400;

    private final List<RecipeSource> sources = new ArrayList<>();
    private final ClientRecipeBookSource recipeBook = new ClientRecipeBookSource();
    private @Nullable Plan active;
    private boolean hudVisible = true;
    private boolean dirty;
    private int ticks;

    public CraftPlanner() {
        sources.add(recipeBook);
    }

    /** Add-ons (JEI) register extra recipe sources; they are consulted before the recipe book. */
    public void addSource(RecipeSource s) {
        sources.add(0, s);
        dirty = true;
    }

    public List<RecipeSource> sources() {
        return sources;
    }

    public @Nullable Plan active() {
        return active;
    }

    public boolean hudVisible() {
        return hudVisible;
    }

    public void setHudVisible(boolean b) {
        hudVisible = b;
    }

    public void invalidate() {
        dirty = true;
    }

    public void clear() {
        active = null;
    }

    /** Plan a target and pin it. Returns false (with a chat message) when no recipe is known. */
    public boolean start(Minecraft mc, ItemStack target, int count) {
        if (target.isEmpty() || count <= 0) return false;
        Plan p = compute(mc, target, count);
        if (p == null) {
            Chat.error("No recipe known for " + target.getHoverName().getString() + ". The recipe book only has what you have unlocked"
                    + (sources.size() > 1 ? "." : "; the JEI add-on can supply the rest."));
            return false;
        }
        active = p;
        Chat.info("Crafting " + count + " × " + target.getHoverName().getString() + ": " + p.lines().size() + " ingredient(s), " + p.steps().size() + " craft step(s)"
                + (p.missingLines() > 0 ? ", " + p.missingLines() + " missing" : p.gathered() ? ", everything is in your inventory" : ", gather from the warehouse with /warehouse craft route") + ".");
        return true;
    }

    public void tick(Minecraft mc) {
        if (active == null || mc.player == null) return;
        if (++ticks % 20 != 0 && !dirty) return;
        dirty = false;
        Plan p = compute(mc, active.target(), active.count());
        if (p != null) active = p;
    }

    // ------------------------------------------------------------ planning

    private static final class Pools {
        final Map<Item, Integer> inventory = new HashMap<>();
        final Map<Item, Integer> warehouse = new HashMap<>();
        final Map<Item, Map<UUID, Integer>> perChest = new HashMap<>();
        final Map<Item, Integer> usedInventory = new HashMap<>();
        final Map<Item, Integer> usedWarehouse = new HashMap<>();

        int remaining(Item i) {
            return inventory.getOrDefault(i, 0) - usedInventory.getOrDefault(i, 0) + warehouse.getOrDefault(i, 0) - usedWarehouse.getOrDefault(i, 0);
        }

        /** Reserve up to n units: inventory first. Returns {fromInventory, fromWarehouse}. */
        int[] take(Item i, int n) {
            int invLeft = inventory.getOrDefault(i, 0) - usedInventory.getOrDefault(i, 0);
            int a = Math.min(n, Math.max(0, invLeft));
            usedInventory.merge(i, a, Integer::sum);
            int whLeft = warehouse.getOrDefault(i, 0) - usedWarehouse.getOrDefault(i, 0);
            int b = Math.min(n - a, Math.max(0, whLeft));
            usedWarehouse.merge(i, b, Integer::sum);
            return new int[]{a, b};
        }
    }

    private static final class Acc {
        int need, inv, wh, missing;
    }

    private @Nullable Plan compute(Minecraft mc, ItemStack target, int count) {
        if (mc.player == null) return null;
        Item item = target.getItem();
        List<RecipeOption> options = recipesFor(item);
        if (options.isEmpty()) return null;
        Pools pools = pools(mc);
        Map<Item, Acc> acc = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int[] nodes = {0};
        expand(item, count, 0, new HashSet<>(), true, pools, acc, steps, notes, nodes);
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<Item, Acc> en : acc.entrySet()) {
            Item i = en.getKey();
            Acc a = en.getValue();
            if (a.need <= 0 && a.missing <= 0) continue; // fully crafted intermediate: it is a step, not a shopping line
            Map<UUID, Integer> per = pools.perChest.getOrDefault(i, Map.of());
            List<ContainerEntry> where = new ArrayList<>();
            for (UUID id : per.keySet()) {
                ContainerEntry e = WarehouseClient.index().byId(id);
                if (e != null) where.add(e);
            }
            where.sort(Comparator.comparingInt((ContainerEntry e) -> per.getOrDefault(e.id, 0)).reversed());
            lines.add(new Line(i, new ItemStack(i), new ItemStack(i).getHoverName().getString(), a.need, a.inv, a.wh, a.missing, where, per));
        }
        lines.sort(Comparator.comparingInt((Line l) -> l.missing() > 0 ? 0 : l.satisfiedByInventory() ? 2 : 1).thenComparing(Comparator.comparingInt(Line::need).reversed()));
        return new Plan(target.copy(), count, lines, steps, notes, System.currentTimeMillis());
    }

    private List<RecipeOption> recipesFor(Item item) {
        List<RecipeOption> out = new ArrayList<>();
        for (RecipeSource s : sources) {
            try {
                out.addAll(s.recipesFor(item));
            } catch (Exception e) {
                WarehouseClient.LOGGER.debug("Recipe source {} failed", s.name(), e);
            }
        }
        return out;
    }

    /** Plans {@code need} units of {@code item}. Non-root items use stock first; the shortfall is crafted or reported missing. */
    private void expand(Item item, int need, int depth, Set<Item> chain, boolean root, Pools pools, Map<Item, Acc> acc, List<Step> steps, List<String> notes, int[] nodes) {
        if (need <= 0 || nodes[0]++ > MAX_NODES) return;
        int shortfall = need;
        if (!root) {
            int[] took = pools.take(item, need);
            Acc a = acc.computeIfAbsent(item, k -> new Acc());
            a.need += took[0] + took[1];
            a.inv += took[0];
            a.wh += took[1];
            shortfall = need - took[0] - took[1];
            if (shortfall <= 0) return;
        }
        List<RecipeOption> options = depth < MAX_DEPTH && !chain.contains(item) ? recipesFor(item) : List.of();
        RecipeOption recipe = choose(options, pools);
        if (recipe == null) {
            Acc a = acc.computeIfAbsent(item, k -> new Acc());
            a.need += shortfall;
            a.missing += shortfall;
            return;
        }
        int crafts = (int) Math.ceil(shortfall / (double) recipe.outputCount());
        steps.add(new Step(recipe.result(), recipe.result().getHoverName().getString(), crafts, crafts * recipe.outputCount(), recipe.kind(), recipe.station()));
        Set<Item> next = new HashSet<>(chain);
        next.add(item);
        // Count identical ingredient slots together so one chosen alternative covers them all.
        Map<List<ItemStack>, Integer> slots = new LinkedHashMap<>();
        for (List<ItemStack> alts : recipe.ingredients()) slots.merge(alts, 1, Integer::sum);
        for (Map.Entry<List<ItemStack>, Integer> en : slots.entrySet()) {
            ItemStack alt = pickAlternative(en.getKey(), pools);
            int units = crafts * en.getValue() * Math.max(1, alt.getCount());
            expand(alt.getItem(), units, depth + 1, next, false, pools, acc, steps, notes, nodes);
        }
    }

    /** Prefer crafting over other stations, then the option whose ingredients are best covered by stock. */
    private @Nullable RecipeOption choose(List<RecipeOption> options, Pools pools) {
        RecipeOption best = null;
        double bestScore = -1;
        for (RecipeOption o : options) {
            double kindBonus = switch (o.kind()) {
                case "crafting" -> 3;
                case "stonecutting" -> 2;
                case "smelting" -> 1;
                default -> 0;
            };
            int covered = 0;
            for (List<ItemStack> alts : o.ingredients()) if (pools.remaining(pickAlternative(alts, pools).getItem()) > 0) covered++;
            double score = kindBonus * 10 + (o.ingredients().isEmpty() ? 0 : 9.0 * covered / o.ingredients().size());
            if (score > bestScore) {
                best = o;
                bestScore = score;
            }
        }
        return best;
    }

    private static ItemStack pickAlternative(List<ItemStack> alts, Pools pools) {
        ItemStack best = alts.get(0);
        int bestStock = -1;
        for (ItemStack s : alts) {
            int stock = pools.remaining(s.getItem());
            if (stock > bestStock) {
                best = s;
                bestStock = stock;
            }
        }
        return best;
    }

    /** Inventory (shulker contents included) and warehouse stock of plain items. */
    private static Pools pools(Minecraft mc) {
        Pools p = new Pools();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            if (st.getComponentsPatch().isEmpty()) p.inventory.merge(st.getItem(), st.getCount(), Integer::sum);
            for (StackRecord n : NestedContents.of(st)) addRecord(p.inventory, null, null, n);
        }
        for (ContainerEntry e : WarehouseClient.index().all()) {
            Region r = WarehouseClient.regions().byId(e.regionId);
            boolean counts = e.kind == ContainerKind.ENDER_CHEST || (r != null && r.type == RegionType.WAREHOUSE);
            if (!counts) continue;
            for (StackRecord s : e.contents) addRecord(p.warehouse, p.perChest, e.id, s);
        }
        return p;
    }

    private static void addRecord(Map<Item, Integer> into, @Nullable Map<Item, Map<UUID, Integer>> perChest, @Nullable UUID chest, StackRecord s) {
        if (s.nested != null && !s.nested.isEmpty()) {
            for (StackRecord n : s.nested) addRecord(into, perChest, chest, n);
            return;
        }
        if (!s.key.isVanilla()) return;
        Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(s.key.itemId)).orElse(null);
        if (item == null) return;
        into.merge(item, s.count, Integer::sum);
        if (perChest != null && chest != null) perChest.computeIfAbsent(item, k -> new LinkedHashMap<>()).merge(chest, s.count, Integer::sum);
    }

    /** What a fetch route should pick up: per item, the part of the need the inventory lacks that the warehouse has. */
    public Map<ItemKey, Integer> toFetch() {
        Map<ItemKey, Integer> out = new LinkedHashMap<>();
        if (active == null) return out;
        for (Line l : active.lines()) {
            int lacking = l.need() - l.inventory();
            int available = l.warehouse();
            if (lacking > 0 && available > 0) out.put(new ItemKey(BuiltInRegistries.ITEM.getKey(l.item()).toString(), ""), Math.min(lacking, available));
        }
        return out;
    }

    public Map<ItemKey, String> fetchNames() {
        Map<ItemKey, String> out = new LinkedHashMap<>();
        if (active == null) return out;
        for (Line l : active.lines()) out.put(new ItemKey(BuiltInRegistries.ITEM.getKey(l.item()).toString(), ""), l.name());
        return out;
    }
}
