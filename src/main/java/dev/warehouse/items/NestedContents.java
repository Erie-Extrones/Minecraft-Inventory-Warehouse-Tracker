package dev.warehouse.items;

import dev.warehouse.index.StackRecord;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/** Reads a shulker box item's contents as lightweight stack records (no group recording). */
public final class NestedContents {
    private NestedContents() {}

    public static List<StackRecord> of(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null || contents.size() == 0) return List.of();
        List<StackRecord> out = new ArrayList<>();
        int i = 0;
        for (ItemStack inner : contents.itemCopies().toList()) {
            if (!inner.isEmpty()) out.add(new StackRecord(Fingerprinter.key(inner), inner.getCount(), i, of(inner).isEmpty() ? null : of(inner), Fingerprinter.displayName(inner)));
            i++;
        }
        return out;
    }
}
