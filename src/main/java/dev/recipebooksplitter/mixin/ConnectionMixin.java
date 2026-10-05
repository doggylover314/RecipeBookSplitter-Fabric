package dev.recipebooksplitter.mixin;

import dev.recipebooksplitter.split.RecipeBookSendInterceptor;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    /*
     * Why this method: Connection.send(Packet, ChannelFutureListener, boolean) is the single choke point every
     * server-side send ends up in, so no recipe book packet can bypass us, including ones other mods hand to the
     * Connection directly. No mixin in Polymer 0.15.2, packet-tweaker 0.6.0, Fabric API 0.141.6, ViaFabric 0.4.22 or
     * FabricProxy-Lite 2.11.0 targets this method.
     *
     * Why this runs after Polymer: ServerCommonPacketListenerImpl.send runs before it calls Connection.send, and
     * Polymer's hooks sit on that earlier method (replace/prevent, its optional count-based recipe splitter). By the
     * time we see the packet, Polymer has finished with it. Polymer's remaining work happens inside
     * PacketEncoder.encode (the per-player item rewriting); the measurement in EntrySizer runs the real encoder, so it
     * includes that.
     *
     * Why ViaVersion is unaffected: we only replace one packet object by several packet objects, before they enter the
     * pipeline. ViaFabric's handler sits after the encoder and works on bytes, so it translates each chunk as it
     * would any other packet, and it does not hook this method.
     *
     * order = 1500 puts us behind any default-order (1000) HEAD injection another mod may add here, so that we
     * see the packet in its final form. The attribute exists in every sponge-mixin that Fabric Loader 0.19.x bundles
     * (0.17.1 to 0.17.4), which is why fabric.mod.json requires Loader 0.19.0 or newer.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true, order = 1500)
    private void recipebooksplitter$splitRecipeBookAdd(Packet<?> packet, @Nullable ChannelFutureListener listener,
                                                       boolean flush, CallbackInfo ci) {
        if (packet instanceof ClientboundRecipeBookAddPacket recipeBookAdd
                && RecipeBookSendInterceptor.onSend((Connection) (Object) this, recipeBookAdd, listener, flush)) {
            ci.cancel();
        }
    }
}
