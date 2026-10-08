package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disconnect in the configuration or play phase (the play listener does not override it); the phase is the listener class. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void rbsh$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        Harness.onDisconnect(((Object) this).getClass().getSimpleName(), details.reason().getString());
    }

    @Inject(method = "handleKeepAlive", at = @At("HEAD"))
    private void rbsh$keepAlive(ClientboundKeepAlivePacket packet, CallbackInfo ci) {
        Harness.onKeepAlive(packet.getId());
    }
}
