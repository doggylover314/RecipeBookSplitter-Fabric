package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the decoding of a recipe_book_add packet on the Netty thread (PacketDecoder.decode: packet id, entries,
 * item stacks and components; compression is handled by a separate handler before this one).
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
    @Inject(method = "decode", at = @At("HEAD"))
    private void rbsh$decodeStart(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        Harness.decodeStart(in.readableBytes());
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void rbsh$decodeEnd(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        if (!out.isEmpty() && out.get(out.size() - 1) instanceof ClientboundRecipeBookAddPacket packet) {
            Harness.decodeEnd(packet);
        }
    }
}
