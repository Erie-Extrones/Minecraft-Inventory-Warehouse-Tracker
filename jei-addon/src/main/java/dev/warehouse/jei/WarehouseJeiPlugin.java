package dev.warehouse.jei;

import dev.warehouse.WarehouseClient;
import dev.warehouse.craft.ClientRecipeBookSource;
import dev.warehouse.craft.RecipeOption;
import dev.warehouse.craft.RecipeSource;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.types.IRecipeHolderType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registers with JEI and, once its runtime exists, feeds JEI's recipes to the warehouse craft planner. */
@JeiPlugin
public final class WarehouseJeiPlugin implements IModPlugin {
    private static final Identifier UID = Identifier.fromNamespaceAndPath("warehouse_jei", "plugin");
    static volatile @Nullable IJeiRuntime runtime;
    private static boolean sourceRegistered;

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime rt) {
        runtime = rt;
        if (!sourceRegistered && WarehouseClient.craftPlanner() != null) {
            WarehouseClient.craftPlanner().addSource(new JeiRecipeSource());
            sourceRegistered = true;
        } else if (WarehouseClient.craftPlanner() != null) {
            WarehouseClient.craftPlanner().invalidate();
        }
    }

    /** The item JEI is showing under the mouse in its ingredient list, bookmarks or recipe screen. */
    static Optional<ItemStack> hovered() {
        IJeiRuntime rt = runtime;
        if (rt == null) return Optional.empty();
        try {
            ItemStack s = rt.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (s != null && !s.isEmpty()) return Optional.of(s);
            s = rt.getBookmarkOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (s != null && !s.isEmpty()) return Optional.of(s);
            Optional<ItemStack> r = rt.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (r.isPresent() && !r.get().isEmpty()) return r;
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /** Crafting, smelting, stonecutting and smithing recipes from JEI, converted through their vanilla displays. */
    static final class JeiRecipeSource implements RecipeSource {
        @Override
        public String name() {
            return "JEI";
        }

        @Override
        public List<RecipeOption> recipesFor(Item target) {
            IJeiRuntime rt = runtime;
            Minecraft mc = Minecraft.getInstance();
            if (rt == null || mc.level == null) return List.of();
            ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
            IFocus<ItemStack> focus = rt.getJeiHelpers().getFocusFactory().createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, new ItemStack(target));
            List<RecipeOption> out = new ArrayList<>();
            collect(rt, RecipeTypes.CRAFTING, focus, ctx, out);
            collect(rt, RecipeTypes.STONECUTTING, focus, ctx, out);
            collect(rt, RecipeTypes.SMELTING, focus, ctx, out);
            collect(rt, RecipeTypes.SMITHING, focus, ctx, out);
            return out;
        }

        private static <R extends Recipe<?>> void collect(IJeiRuntime rt, IRecipeHolderType<R> type, IFocus<ItemStack> focus, ContextMap ctx, List<RecipeOption> out) {
            try {
                rt.getRecipeManager().createRecipeLookup(type).limitFocus(List.of(focus)).get().forEach((RecipeHolder<R> holder) -> {
                    for (RecipeDisplay d : holder.value().display()) {
                        RecipeOption o = ClientRecipeBookSource.convert(d, ctx);
                        if (o != null) out.add(new RecipeOption(o.kind(), o.result(), o.ingredients(), o.station(), "JEI"));
                    }
                });
            } catch (Exception e) {
                WarehouseClient.LOGGER.debug("JEI recipe lookup failed for {}", type, e);
            }
        }
    }
}
