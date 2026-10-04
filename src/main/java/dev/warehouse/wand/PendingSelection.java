package dev.warehouse.wand;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Corner A/B state while drawing a Warehouse region. */
public final class PendingSelection {
    public @Nullable String dimension;
    public @Nullable BlockPos cornerA;
    public @Nullable BlockPos cornerB;

    public boolean isEmpty() {
        return cornerA == null;
    }

    public boolean isComplete() {
        return cornerA != null && cornerB != null;
    }

    public void clear() {
        dimension = null;
        cornerA = null;
        cornerB = null;
    }
}
