package dev.recipebooksplitter.split;

import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.Inflater;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.jspecify.annotations.Nullable;

/**
 * A server-side {@link Connection} on an {@link EmbeddedChannel} with the outbound handlers vanilla has in the play
 * phase, under vanilla's names and in vanilla's order: prepender, [compress], encoder, unbundler, recorder,
 * packet_handler. Outbound packets travel packet_handler, recorder, unbundler, encoder, compress, prepender.
 */
final class VanillaPipeline implements AutoCloseable {
    final Connection connection = new Connection(PacketFlow.SERVERBOUND);
    final WriteRecorder recorder = new WriteRecorder();
    final EmbeddedChannel channel = new EmbeddedChannel(false, false);
    /** Every packet object the encoder was asked to encode, including probes, in order. */
    final List<Packet<?>> encoded = new CopyOnWriteArrayList<>();
    /** For each entry of {@link #encoded}: whether measured bytes were on offer for that packet when it was encoded. */
    final List<Boolean> hadPrepared = new CopyOnWriteArrayList<>();
    private final int threshold;

    /** @param threshold network compression threshold; negative for no compression, as in server.properties */
    VanillaPipeline(int threshold, boolean viaLikeHandler) throws Exception {
        this.threshold = threshold;
        channel.pipeline().addLast(HandlerNames.PREPENDER, new Varint21LengthFieldPrepender());
        if (threshold >= 0) {
            channel.pipeline().addLast(HandlerNames.COMPRESS, new CompressionEncoder(threshold));
        }
        if (viaLikeHandler) {
            // Where ViaFabric puts "via-encoder" on a server (between compress and encoder); bytes pass unchanged.
            channel.pipeline().addLast("via-encoder", new ChannelDuplexHandler());
        }
        channel.pipeline().addLast(HandlerNames.ENCODER, new PacketEncoder<ClientGamePacketListener>(RecipeFixtures.protocol()) {
            @Override
            protected void encode(ChannelHandlerContext ctx, Packet<ClientGamePacketListener> packet, ByteBuf out) throws Exception {
                encoded.add(packet);
                hadPrepared.add(packet instanceof ClientboundRecipeBookAddPacket recipeBookAdd
                        && RecipeBookSendInterceptor.preparedFor(this, recipeBookAdd) != null);
                super.encode(ctx, packet, out);
            }
        });
        channel.pipeline().addLast(HandlerNames.UNBUNDLER, new PacketBundleUnpacker(RecipeFixtures.protocol().bundlerInfo()));
        channel.pipeline().addLast("recorder", recorder);
        channel.pipeline().addLast(HandlerNames.PACKET_HANDLER, connection);
        channel.register();
    }

    /**
     * A frame as the client's frame decoder sees it.
     *
     * @param wire the whole frame as written to the network: length prefix, compression header and body
     * @param data the packet bytes (id plus payload) after decompression
     * @param packet decoded with the clientbound play codec, or null if a client could not decode it (for example the
     *               2 MiB NBT quota of custom_data); then {@code decodeError} says why
     */
    record Frame(byte[] wire, int frameBodyBytes, int packetBytes, byte[] data, @Nullable Packet<?> packet, @Nullable String decodeError) {}

    /** Reads every written frame and decodes it like a client: frame length, [compression], packet. */
    List<Frame> readFrames() {
        List<Frame> frames = new ArrayList<>();
        ByteBuf frame;
        while ((frame = channel.readOutbound()) != null) {
            try {
                byte[] wire = ByteBufUtil.getBytes(frame);
                int body = VarInt.read(frame);
                if (body != frame.readableBytes()) {
                    throw new IllegalStateException("frame length " + body + " but " + frame.readableBytes() + " bytes follow");
                }
                ByteBuf data = frame;
                if (threshold >= 0) {
                    int uncompressed = VarInt.read(frame);
                    if (uncompressed != 0) {
                        data = inflate(frame, uncompressed);
                    }
                }
                try {
                    int packetBytes = data.readableBytes();
                    byte[] bytes = new byte[packetBytes];
                    data.getBytes(data.readerIndex(), bytes);
                    Packet<?> packet = null;
                    String error = null;
                    try {
                        packet = RecipeFixtures.protocol().codec().decode(data);
                    } catch (RuntimeException e) {
                        Throwable root = e;
                        while (root.getCause() != null) {
                            root = root.getCause();
                        }
                        error = root.toString();
                    }
                    frames.add(new Frame(wire, body, packetBytes, bytes, packet, error));
                } finally {
                    if (data != frame) {
                        data.release();
                    }
                }
            } finally {
                frame.release();
            }
        }
        return frames;
    }

    private static ByteBuf inflate(ByteBuf in, int size) {
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] out = new byte[size];
            int n = inflater.inflate(out);
            if (n != size || !inflater.finished()) {
                throw new IllegalStateException("inflated " + n + " of " + size);
            }
            return Unpooled.wrappedBuffer(out);
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalStateException(e);
        } finally {
            inflater.end();
        }
    }

    @Nullable Throwable pendingException() {
        try {
            channel.checkException();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Override
    public void close() {
        channel.finishAndReleaseAll();
    }
}
