package dev.warehouse.craft;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One way to make an item: the result (with its output count), the ingredient slots (each a list of acceptable stacks)
 * and the station it needs. Built from vanilla recipe displays or supplied by an add-on.
 */
public record RecipeOption(String kind, ItemStack result, List<List<ItemStack>> ingredients, ItemStack station, String source) {
    public int outputCount() {
        return Math.max(1, result.getCount());
    }
}
