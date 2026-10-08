package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disconnect in the login phase. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void rbsh$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        Harness.onDisconnect("login", details.reason().getString());
    }
}
