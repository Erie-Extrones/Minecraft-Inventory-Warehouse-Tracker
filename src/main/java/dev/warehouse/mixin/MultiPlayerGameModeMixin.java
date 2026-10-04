package dev.warehouse.mixin;

import dev.warehouse.inventory.DropDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes outgoing drop actions so the lost log can record what left the inventory. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "dropItem", at = @At("HEAD"))
    private void warehouse$onDropItem(LocalPlayer player, boolean all, CallbackInfo ci) {
        DropDetector.onHotbarDrop(Minecraft.getInstance(), all);
    }

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void warehouse$onContainerInput(int containerId, int slotNum, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo ci) {
        DropDetector.onContainerClick(Minecraft.getInstance(), slotNum, buttonNum, containerInput);
    }
}
