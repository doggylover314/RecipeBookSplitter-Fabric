package dev.recipebooksplitter.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recipebooksplitter.split.OversizedPacketLogger;
import dev.recipebooksplitter.split.PreparedPacket;
import dev.recipebooksplitter.split.RecipeBookSendInterceptor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"))
    private void recipebooksplitter$logOversized(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        OversizedPacketLogger.onEncoded(ctx, packet, out);
    }

    /*
     * Encode once: the measuring already ran every entry through the codec, and the interceptor kept those bytes. When
     * it is writing a chunk of a split (or the unsplit packet) it measured, the codec call below is replaced by
     * copying the kept bytes, which are what the codec would write for these entries.
     *
     * Only the codec call is wrapped, not the whole method, so everything else in encode() still runs for these
     * packets: other mods' hooks before and after it, the debug log, JFR's onPacketSent with the real size and
     * ProtocolSwapHandler. Every clientbound packet passes through here; for all but recipe_book_add the cost is one
     * instanceof. With require = 0 a mixin that cannot be applied (the call is gone, or another mod overwrote the
     * method) only means that nothing is reused and the packets are encoded as in 1.0.0; the split log line then
     * says "reused for 0 of N packets".
     */
    @WrapOperation(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"),
            require = 0)
    private void recipebooksplitter$encodeOnce(StreamCodec<?, ?> codec, Object buf, Object packet, Operation<Void> original) {
        if (packet instanceof ClientboundRecipeBookAddPacket recipeBookAdd && buf instanceof ByteBuf out) {
            PreparedPacket prepared = RecipeBookSendInterceptor.preparedFor(this, recipeBookAdd);
            if (prepared != null && prepared.write(out, () -> original.call(codec, buf, packet))) {
                return;
            }
        }
        original.call(codec, buf, packet);
    }
}
