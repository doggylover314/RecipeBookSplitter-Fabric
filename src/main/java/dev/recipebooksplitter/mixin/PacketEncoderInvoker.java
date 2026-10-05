package dev.recipebooksplitter.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PacketEncoder.class)
public interface PacketEncoderInvoker {
    /** Runs the connection's real encoder, so other mods' encode-time hooks apply to what we measure. */
    @Invoker("encode")
    void recipebooksplitter$encode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out) throws Exception;
}
