package dev.warehouse.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to container screen layout fields for positioning our widgets. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int warehouse$leftPos();

    @Accessor("topPos")
    int warehouse$topPos();

    @Accessor("imageWidth")
    int warehouse$imageWidth();

    @Accessor("imageHeight")
    int warehouse$imageHeight();
}
