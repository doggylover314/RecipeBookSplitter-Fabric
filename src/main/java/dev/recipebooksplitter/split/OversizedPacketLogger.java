package dev.recipebooksplitter.split;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.util.Sizes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;

/** Backs the {@code logOversizedPackets} option: a warning for every clientbound packet that encodes to over 4 MiB. */
public final class OversizedPacketLogger {
    public static final int THRESHOLD_BYTES = 4 * 1024 * 1024;
    private static final int UNCOMPRESSED_LIMIT_BYTES = 8_388_608;

    private OversizedPacketLogger() {}

    /**
     * Called at the end of {@code PacketEncoder.encode}, where {@code out} holds exactly the packet just written
     * (packet id plus payload): before compression and before ViaVersion translation.
     */
    public static void onEncoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out) {
        if (!RecipeBookSplitter.config().logOversizedPackets()) {
            return;
        }
        int size = out.readableBytes();
        if (size <= THRESHOLD_BYTES || EntrySizer.isMeasuring()) {
            return;
        }
        String recipient = ctx.pipeline().get(HandlerNames.PACKET_HANDLER) instanceof Connection connection
                ? RecipeBookSendInterceptor.describe(connection)
                : String.valueOf(ctx.channel().remoteAddress());
        String note = size > UNCOMPRESSED_LIMIT_BYTES ? " - exceeds the 8,388,608-byte limit"
                : size > RecipeBookSendInterceptor.FRAME_LIMIT_BYTES ? " - exceeds the 2,097,151-byte frame limit if compression is disabled"
                : "";
        RecipeBookSplitter.LOGGER.warn("[RecipeBookSplitter] oversized clientbound packet {} for {}: {} ({} bytes){}",
                packet.type(), recipient, Sizes.mib(size), Sizes.bytes(size), note);
    }
}
