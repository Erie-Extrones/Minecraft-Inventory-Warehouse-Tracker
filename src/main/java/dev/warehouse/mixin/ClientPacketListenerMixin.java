package dev.warehouse.mixin;

import dev.warehouse.WarehouseClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes incoming packets. Injections are placed after PacketUtils.ensureRunningOnSameThread so they
 * only fire on the client thread (the netty-thread call throws before reaching them).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleBlockUpdate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V"))
    private void warehouse$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        WarehouseClient.onBlockUpdatePacket(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void warehouse$onSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        WarehouseClient.onContainerContentPacket(packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void warehouse$onSetContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        WarehouseClient.onContainerContentPacket(packet.containerId());
    }
}
