package dev.recipebooksplitter.split;

import dev.recipebooksplitter.util.Sizes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.util.zip.Deflater;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.jspecify.annotations.Nullable;

/**
 * The hard size limits of one connection's outbound pipeline, read from the handlers vanilla installs: a packet that
 * breaks them makes {@code CompressionEncoder} or {@code Varint21LengthFieldPrepender} throw on the event loop, and
 * {@code Connection.exceptionCaught} then disconnects the player.
 *
 * <p>All sizes are of what {@code PacketEncoder} writes (packet id plus payload), before ViaVersion translation.
 *
 * <p>The handlers are recognised by class and name, not by what they do: a mod that patches the limits inside the
 * vanilla handler classes cannot be seen that way. {@link #compressionLimitMayBeLifted} covers the one such mod known
 * to lift the 8,388,608-byte limit (Packet Fixer).
 *
 * @param mode how the pipeline frames packets
 * @param compressionThreshold the {@code CompressionEncoder} threshold, or -1 if there is none
 */
public record ConnectionLimits(Mode mode, int compressionThreshold) {
    /** Largest frame body a 3-byte VarInt length can describe ({@code Varint21LengthFieldPrepender.encode}). */
    public static final int FRAME_LIMIT_BYTES = 2_097_151;
    /** {@code CompressionEncoder.encode} throws "Packet too big" above this many uncompressed bytes. */
    public static final int COMPRESSION_LIMIT_BYTES = 8_388_608;
    /**
     * Up to this size an uncompressed packet always fits a frame after compression, so deflating it to find out is not
     * needed: even stored (incompressible) DEFLATE data only adds a few bytes per 64 KiB block plus the 6-byte zlib
     * wrapper, far below the 97,151 bytes of room left. Checked by {@code ConnectionLimitsTest}.
     */
    static final int ALWAYS_FITS_COMPRESSED_BYTES = 2_000_000;
    /** The Fabric mod id of Packet Fixer, which raises the limit of {@code CompressionEncoder} inside the vanilla class. */
    public static final String PACKET_FIXER_MOD_ID = "packetfixer";

    /**
     * True if a mod is loaded that may lift {@link #COMPRESSION_LIMIT_BYTES} inside the vanilla {@code CompressionEncoder}
     * (set at startup, see {@link #PACKET_FIXER_MOD_ID}). A packet over that size is then no longer certainly
     * undeliverable for its size alone: only the frame limit decides, and whether the encoder sends it is unknown (the
     * mod can be configured not to). The frame limit is not affected: the receiving frame decoder keeps its 3-byte
     * length (checked with Packet Fixer 3.3.5 at the level of the handler classes, no real client).
     */
    public static volatile boolean compressionLimitMayBeLifted;

    public enum Mode {
        /** No {@code compress} handler, vanilla {@code Varint21LengthFieldPrepender}: the frame limit applies to the raw packet. */
        UNCOMPRESSED,
        /** Vanilla {@code CompressionEncoder} under the name {@code compress}. */
        COMPRESSED,
        /** Something else (a replaced compressor or frame encoder, an in-memory connection): no limit is assumed. */
        UNKNOWN
    }

    public static final ConnectionLimits UNKNOWN = new ConnectionLimits(Mode.UNKNOWN, -1);

    /**
     * Reads the pipeline by handler name. The names are vanilla's ({@code HandlerNames.COMPRESS}, {@code PREPENDER});
     * ViaFabric adds its own handlers next to them and moves only those, so their order does not matter here.
     */
    public static ConnectionLimits detect(ChannelPipeline pipeline) {
        ChannelHandler compress = pipeline.get(HandlerNames.COMPRESS);
        if (compress instanceof CompressionEncoder encoder) {
            return new ConnectionLimits(Mode.COMPRESSED, encoder.getThreshold());
        }
        if (compress == null && pipeline.get(HandlerNames.PREPENDER) instanceof Varint21LengthFieldPrepender) {
            return new ConnectionLimits(Mode.UNCOMPRESSED, -1);
        }
        return UNKNOWN;
    }

    /** Supplies the exact bytes {@code PacketEncoder} writes for the packet; only called when they are needed. */
    @FunctionalInterface
    public interface EncodedPacket {
        ByteBuf encode() throws Exception;
    }

    /**
     * @param sendable true if the pipeline can send it, false if it certainly throws, null if this cannot be known
     * @param frameBytes the frame body size (after compression) if it was computed, else -1
     * @param reason why it cannot be sent, or why that is unknown, for the log; null if it can be sent
     */
    public record Verdict(@Nullable Boolean sendable, long frameBytes, @Nullable String reason) {
        static final Verdict UNKNOWN = new Verdict(null, -1, null);

        public boolean certainlyUnsendable() {
            return Boolean.FALSE.equals(sendable);
        }
    }

    /**
     * Whether a packet of {@code packetBytes} bytes (as {@code PacketEncoder} writes it) gets through this pipeline.
     * A compressed packet between {@link #ALWAYS_FITS_COMPRESSED_BYTES} and {@link #COMPRESSION_LIMIT_BYTES} is
     * deflated exactly like {@code CompressionEncoder} does to get the frame size; {@code encoded} is only called then,
     * and this method releases the buffer it returns.
     */
    public Verdict check(long packetBytes, EncodedPacket encoded) throws Exception {
        switch (mode) {
            case UNCOMPRESSED -> {
                return packetBytes <= FRAME_LIMIT_BYTES
                        ? new Verdict(true, packetBytes, null)
                        : new Verdict(false, packetBytes, "network compression is off, and a frame can hold at most 2,097,151 bytes");
            }
            case COMPRESSED -> {
                boolean overCompressionLimit = packetBytes > COMPRESSION_LIMIT_BYTES;
                if (overCompressionLimit && !compressionLimitMayBeLifted) {
                    return new Verdict(false, -1, "network compression refuses packets over 8,388,608 bytes");
                }
                if (packetBytes < compressionThreshold) {
                    // Sent uncompressed behind a VarInt 0: one more byte.
                    long frame = 1 + packetBytes;
                    return frame <= FRAME_LIMIT_BYTES
                            ? new Verdict(true, frame, null)
                            : new Verdict(false, frame, "it is below the compression threshold, so it is not compressed, and a frame can hold at most 2,097,151 bytes");
                }
                if (packetBytes <= ALWAYS_FITS_COMPRESSED_BYTES) {
                    return new Verdict(true, -1, null);
                }
                ByteBuf bytes = encoded.encode();
                try {
                    if (bytes.readableBytes() != packetBytes) {
                        throw new IllegalStateException("encoded " + bytes.readableBytes() + " bytes, measured " + packetBytes);
                    }
                    long frame = VarInt.getByteSize(bytes.readableBytes()) + deflatedSize(bytes);
                    if (frame > FRAME_LIMIT_BYTES) {
                        return new Verdict(false, frame, "it compresses to a " + Sizes.bytes(frame) + "-byte frame, and a frame can hold at most 2,097,151 bytes");
                    }
                    return overCompressionLimit
                            ? new Verdict(null, frame, "it is over 8,388,608 bytes, which network compression refuses unless a mod such as Packet Fixer lifts that limit, so whether this connection can send it is unknown")
                            : new Verdict(true, frame, null);
                } finally {
                    bytes.release();
                }
            }
            default -> {
                return Verdict.UNKNOWN;
            }
        }
    }

    /** Exactly what {@code CompressionEncoder.encode} produces after the VarInt: a default {@link Deflater}, 8 KiB steps. */
    static long deflatedSize(ByteBuf data) {
        byte[] input = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), input);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input, 0, input.length);
            deflater.finish();
            byte[] out = new byte[8192];
            long total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(out);
            }
            return total;
        } finally {
            deflater.end();
        }
    }
}
