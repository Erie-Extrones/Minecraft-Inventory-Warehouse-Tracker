package dev.warehouse.craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipes the server has sent to the client recipe book (everything the player has unlocked). Rebuilt when the book changes.
 * Since 1.21.2 the client has no recipe manager; these display entries are all vanilla gives us without a server mod.
 */
public final class ClientRecipeBookSource implements RecipeSource {
    private Map<Item, List<RecipeOption>> byResult = new HashMap<>();
    private int builtFromCollections = -1;
    private long builtAtMs;

    @Override
    public String name() {
        return "recipe book";
    }

    @Override
    public List<RecipeOption> recipesFor(Item target) {
        ensureBuilt();
        return byResult.getOrDefault(target, List.of());
    }

    public int recipeCount() {
        ensureBuilt();
        int n = 0;
        for (List<RecipeOption> l : byResult.values()) n += l.size();
        return n;
    }

    private void ensureBuilt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<RecipeCollection> collections = mc.player.getRecipeBook().getCollections();
        long now = System.currentTimeMillis();
        if (collections.size() == builtFromCollections && now - builtAtMs < 60_000) return;
        builtFromCollections = collections.size();
        builtAtMs = now;
        ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
        Map<Item, List<RecipeOption>> map = new HashMap<>();
        for (RecipeCollection c : collections) {
            for (RecipeDisplayEntry e : c.getRecipes()) {
                RecipeOption opt = convert(e.display(), ctx);
                if (opt != null) map.computeIfAbsent(opt.result().getItem(), k -> new ArrayList<>()).add(opt);
            }
        }
        byResult = map;
    }

    /** Turn a vanilla recipe display into a planner option; null for displays we cannot read. Shared with add-ons. */
    public static RecipeOption convert(RecipeDisplay d, ContextMap ctx) {
        ItemStack result = d.result().resolveForFirstStack(ctx);
        if (result.isEmpty()) return null;
        List<List<ItemStack>> ingredients = new ArrayList<>();
        String kind;
        if (d instanceof ShapedCraftingRecipeDisplay s) {
            kind = "crafting";
            for (SlotDisplay sd : s.ingredients()) addSlot(ingredients, sd, ctx);
        } else if (d instanceof ShapelessCraftingRecipeDisplay s) {
            kind = "crafting";
            for (SlotDisplay sd : s.ingredients()) addSlot(ingredients, sd, ctx);
        } else if (d instanceof FurnaceRecipeDisplay f) {
            kind = "smelting";
            addSlot(ingredients, f.ingredient(), ctx);
        } else if (d instanceof StonecutterRecipeDisplay s) {
            kind = "stonecutting";
            addSlot(ingredients, s.input(), ctx);
        } else if (d instanceof SmithingRecipeDisplay s) {
            kind = "smithing";
            addSlot(ingredients, s.template(), ctx);
            addSlot(ingredients, s.base(), ctx);
            addSlot(ingredients, s.addition(), ctx);
        } else {
            return null;
        }
        if (ingredients.isEmpty()) return null;
        ItemStack station = d.craftingStation().resolveForFirstStack(ctx);
        return new RecipeOption(kind, result, ingredients, station, "recipe book");
    }

    private static void addSlot(List<List<ItemStack>> into, SlotDisplay sd, ContextMap ctx) {
        List<ItemStack> alts = new ArrayList<>();
        for (ItemStack st : sd.resolveForStacks(ctx)) if (!st.isEmpty()) alts.add(st);
        if (!alts.isEmpty()) into.add(alts);
    }
}
