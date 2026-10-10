package dev.warehouse.craft;

import net.minecraft.world.item.Item;

import java.util.List;

/** Supplies recipes for the planner. The client recipe book is built in; the JEI add-on registers another. */
public interface RecipeSource {
    String name();

    List<RecipeOption> recipesFor(Item target);
}
