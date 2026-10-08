package dev.recipebooksplitter.mixin;

import dev.recipebooksplitter.split.RecipeBookSendInterceptor;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
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
     * Connection directly. No mixin in Polymer 0.15.2, packet-tweaker 0.6.0, Fabric API 0.141.6, ViaFabric 0.4.22
     * (read) and 0.4.21+166 (run) or FabricProxy-Lite 2.11.0 targets this method.
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
     * Why bundles are looked at too: a ClientboundBundlePacket reaches this method as one object, and only the
     * "unbundler" handler further down the pipeline takes its sub-packets apart, so this is the last place where a
     * recipe book packet inside a bundle can be replaced by its chunks.
     *
     * order = 1500 puts us behind any default-order (1000) HEAD injection another mod may add here, so that we
     * see the packet in its final form. @Inject's order attribute exists since sponge-mixin 0.15.0 (bundled from
     * Fabric Loader 0.16.0 on), so it is not what sets the Loader floor. fabric.mod.json requires Loader 0.19.0 because
     * that is the oldest Loader that was tested: the 1.0.0 jar (which had no @WrapOperation) on 0.19.0, 0.19.3 and
     * 0.19.5, each on Java 21 and 25. PacketEncoderMixin of 1.1.0 uses MixinExtras' @WrapOperation, which Loader 0.19.0
     * bundles (MixinExtras 0.5.3; Loader 0.19.5 bundles 0.5.5). The 1.1.0 jar was verified on both Loaders, on Java 21
     * and 25 (README, "Requirements"): the wrap is applied with MixinExtras 0.5.3 too.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true, order = 1500)
    private void recipebooksplitter$splitRecipeBookAdd(Packet<?> packet, @Nullable ChannelFutureListener listener,
                                                       boolean flush, CallbackInfo ci) {
        if (packet instanceof ClientboundRecipeBookAddPacket recipeBookAdd) {
            if (RecipeBookSendInterceptor.onSend((Connection) (Object) this, recipeBookAdd, listener, flush)) {
                ci.cancel();
            }
        } else if (packet instanceof ClientboundBundlePacket bundle
                && RecipeBookSendInterceptor.onSendBundle((Connection) (Object) this, bundle, listener, flush)) {
            ci.cancel();
        }
    }
}
