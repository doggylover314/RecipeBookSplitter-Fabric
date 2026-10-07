package dev.recipebooksplitter.split;

import dev.recipebooksplitter.RecipeBookSplitter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.slf4j.Logger;

/**
 * One recipe book packet whose bytes were already produced while measuring. {@code PacketEncoder} asks for them
 * through {@link RecipeBookSendInterceptor#preparedFor} and calls {@link #write} instead of running the codec; the
 * result is the same bytes the codec would write, because they are what the codec wrote for each entry during the
 * measurement.
 *
 * <p>Single use, and only for the exact packet object and encoder instance that were measured. Event loop thread only.
 */
public final class PreparedPacket {
    /** What happened to this packet at the encoder. */
    enum Outcome {
        /** Not encoded yet. */
        PENDING,
        /** The measured bytes were written. */
        REUSED,
        /** Verify mode: the measured bytes equal a normal encode (which was sent). */
        VERIFIED,
        /** Verify mode: the measured bytes differ from a normal encode (which was sent). */
        MISMATCH,
        /** The measured bytes could not be written; the packet was encoded normally. */
        FAILED
    }

    private static final Logger LOGGER = RecipeBookSplitter.LOGGER;

    final ClientboundRecipeBookAddPacket packet;
    /** The {@code PacketEncoder} instance that produced the bytes. */
    final Object encoder;
    final EncodedEntries bytes;
    /** Indexes into {@link #bytes} of the entries of {@link #packet}, in order. */
    final int[] entries;
    /** Exact size of the encoded packet, from the chunk plan; cross-checked when writing. */
    final long size;
    Outcome outcome = Outcome.PENDING;

    PreparedPacket(ClientboundRecipeBookAddPacket packet, Object encoder, EncodedEntries bytes, int[] entries, long size) {
        this.packet = packet;
        this.encoder = encoder;
        this.bytes = bytes;
        this.entries = entries;
        this.size = size;
    }

    /**
     * Called from the hook around the codec call in {@code PacketEncoder.encode}. Never throws on its own account: if
     * the measured bytes cannot be written, the buffer is left as it was and the caller encodes normally.
     *
     * <p>With {@link RecipeBookSendInterceptor#verifyEncodeOnce} the packet is also encoded normally and compared with
     * the measured bytes; the normally encoded bytes are what stays in {@code out}.
     *
     * @param encodeNormally runs the codec on {@code out}
     * @return true if {@code out} now holds the packet, false if the caller has to encode it normally
     */
    public boolean write(ByteBuf out, Runnable encodeNormally) {
        int mark = out.writerIndex();
        if (!RecipeBookSendInterceptor.verifyEncodeOnce) {
            try {
                bytes.writePacket(out, entries, packet.replace(), size);
                outcome = Outcome.REUSED;
                return true;
            } catch (Throwable t) {
                out.writerIndex(mark);
                return failed(t);
            }
        }

        ByteBuf expected = null;
        try {
            expected = Unpooled.buffer(Math.toIntExact(size));
            bytes.writePacket(expected, entries, packet.replace(), size);
        } catch (Throwable t) {
            if (expected != null) {
                expected.release();
            }
            return failed(t);
        }
        try {
            encodeNormally.run();
            int written = out.writerIndex() - mark;
            boolean same = written == expected.readableBytes() && ByteBufUtil.equals(expected, 0, out, mark, written);
            outcome = same ? Outcome.VERIFIED : Outcome.MISMATCH;
            if (!same) {
                LOGGER.error("[RecipeBookSplitter] measured bytes of a recipe book packet ({} entries) differ from a normal encode ({} vs {} bytes); the normally encoded bytes were sent",
                        entries.length, expected.readableBytes(), written);
            }
            return true;
        } finally {
            expected.release();
        }
    }

    private boolean failed(Throwable cause) {
        outcome = Outcome.FAILED;
        LOGGER.error("[RecipeBookSplitter] could not write the measured bytes of a recipe book packet ({} entries); encoding it normally",
                entries.length, cause);
        return false;
    }
}
