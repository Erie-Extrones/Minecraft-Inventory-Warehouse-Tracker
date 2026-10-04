package dev.warehouse.index;

import dev.warehouse.items.Fingerprinter;
import dev.warehouse.items.ItemGroups;
import dev.warehouse.items.ItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/** Converts ItemStacks into StackRecords (with shulker recursion) and records custom items. */
public final class Snapshotter {
    private final ItemGroups groups;

    public Snapshotter(ItemGroups groups) {
        this.groups = groups;
    }

    public StackRecord record(ItemStack stack, int slot) {
        ItemKey key = Fingerprinter.key(stack);
        if (!key.isVanilla()) groups.record(stack, key);
        List<StackRecord> nested = null;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.size() > 0) {
            nested = new ArrayList<>();
            int i = 0;
            for (ItemStack inner : contents.itemCopies().toList()) {
                if (!inner.isEmpty()) nested.add(record(inner, i));
                i++;
            }
            if (nested.isEmpty()) nested = null;
        }
        return new StackRecord(key, stack.getCount(), slot, nested, Fingerprinter.displayName(stack));
    }

    public List<StackRecord> snapshot(List<ItemStack> stacks) {
        List<StackRecord> out = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack s = stacks.get(i);
            if (!s.isEmpty()) out.add(record(s, i));
        }
        return out;
    }
}
