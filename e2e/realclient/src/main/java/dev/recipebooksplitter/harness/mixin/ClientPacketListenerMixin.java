package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks of the play-phase packet listener. Each of {@code handleLogin} and {@code handleRecipeBookAdd} runs twice per
 * packet: first on the Netty thread, where {@code PacketUtils.ensureRunningOnSameThread} queues the packet for the
 * render thread and throws, then on the render thread. A RETURN injection only fires for the second call, and the HEAD
 * hook checks the thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void rbsh$login(ClientboundLoginPacket packet, CallbackInfo ci) {
        Harness.onJoin();
    }

    @Inject(method = "handleRecipeBookAdd", at = @At("HEAD"))
    private void rbsh$addStart(ClientboundRecipeBookAddPacket packet, CallbackInfo ci) {
        Harness.onHandleStart();
    }

    @Inject(method = "handleRecipeBookAdd", at = @At("RETURN"))
    private void rbsh$addEnd(ClientboundRecipeBookAddPacket packet, CallbackInfo ci) {
        Harness.onHandleEnd(packet, (ClientPacketListener) (Object) this);
    }

    @Inject(method = "refreshRecipeBook", at = @At("HEAD"))
    private void rbsh$refreshStart(ClientRecipeBook book, CallbackInfo ci) {
        Harness.onRefreshStart();
    }

    @Inject(method = "refreshRecipeBook", at = @At("RETURN"))
    private void rbsh$refreshEnd(ClientRecipeBook book, CallbackInfo ci) {
        Harness.onRefreshEnd();
    }
}
