package dev.recipebooksplitter.split;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.jspecify.annotations.Nullable;

/**
 * Measures how many bytes each recipe book entry takes on the wire by encoding one-entry probe packets with the
 * connection's real {@code PacketEncoder}. That reproduces exactly what the encoder will write, including rewrites that
 * other mods apply while encoding (Polymer's per-player item replacement, Fabric API's custom ingredient fallback),
 * which a bare {@code Entry.STREAM_CODEC} call would miss.
 */
public final class EntrySizer {
    private static final ThreadLocal<Boolean> MEASURING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<CodecSpan> CODEC_SPAN = ThreadLocal.withInitial(CodecSpan::new);

    private EntrySizer() {}

    /** Where the codec call(s) of the probe being encoded wrote into the output buffer; see {@link #noteCodecSpan}. */
    private static final class CodecSpan {
        int calls;
        int start;
        int end;
    }

    /** Why the bytes of a measurement were not kept although that was asked for. */
    public enum NotKept {
        /** The hook around the codec call in {@code PacketEncoder.encode} did not run, so nothing would use the bytes. */
        NO_CODEC_HOOK,
        /** The codec call did not write the whole packet and nothing else: other bytes are written around it. */
        OUTSIDE_CODEC,
        /** The probes do not have the layout {@code id | count | entry | replace flag}. */
        LAYOUT
    }

    /** True while this thread is encoding a probe packet, so encoder hooks can ignore it. */
    public static boolean isMeasuring() {
        return MEASURING.get();
    }

    /**
     * Called by the hook around the codec call in {@code PacketEncoder.encode} while this thread encodes a probe: the
     * codec wrote the bytes {@code [start, end)} of the output buffer. Only the hook sees where the codec's own output
     * lies, and the kept bytes are only valid if it is the whole packet (see {@link #measure}).
     */
    public static void noteCodecSpan(int start, int end) {
        CodecSpan span = CODEC_SPAN.get();
        span.calls++;
        span.start = start;
        span.end = end;
    }

    /** Writes a packet exactly like {@code PacketEncoder.encode}: packet id, then payload. */
    @FunctionalInterface
    public interface PacketWriter {
        void write(Packet<?> packet, ByteBuf out) throws Exception;
    }

    /**
     * @param fixedOverheadBytes packet id VarInt plus the 1-byte replace flag
     * @param entryBytes encoded size of each entry
     * @param sha256 hex digest over all entry bytes, or null if not requested
     * @param encoded the bytes of every entry, or null if they were not requested or could not be kept
     * @param notKept why {@code encoded} is null although it was requested, otherwise null
     */
    public record Measurement(int fixedOverheadBytes, int[] entryBytes, @Nullable String sha256, @Nullable EncodedEntries encoded,
                              @Nullable NotKept notKept) {
        public long totalBytes() {
            long sum = 0;
            for (int bytes : entryBytes) {
                sum += bytes;
            }
            return ChunkPlanner.packetBytes(fixedOverheadBytes, entryBytes.length, sum);
        }
    }

    /**
     * Assumes an entry's bytes do not depend on its neighbours, so the packet size is the sum of the entry sizes
     * plus the fixed overhead; the tests check that this equals a full encode.
     *
     * <p>The probes run the real {@code PacketEncoder.encode}. Other mods keep per-thread state for the duration of one
     * encode (packet-tweaker, and with it Polymer, clears its packet context when an encode returns; Fabric API resets
     * its custom ingredient state), so a probe run from inside another encode on the same thread would wipe that state
     * for the outer packet. That needs a recipe packet to be sent from within an encode, which a vanilla nested write
     * would break in the same way, and nothing in the tested stack does it.
     *
     * <p>With {@code keepBytes} the entry bytes of the probes are kept (see {@link EncodedEntries}), at the price of one
     * more {@code PacketEncoder.encode} call per measured packet: an empty packet with the other replace flag, to learn
     * both flag bytes. Nothing is kept unless every probe has the layout {@code id | count | entry | replace} and the
     * hook around the codec call reported (see {@link #noteCodecSpan}) that the codec call wrote exactly that: the kept
     * header would otherwise contain bytes that something else writes around the codec call (another mod's hook on
     * {@code PacketEncoder.encode}), and they would then be written twice when a chunk is written. Without the hook
     * (not applied) nothing would use the kept bytes, so none are kept.
     */
    public static Measurement measure(List<ClientboundRecipeBookAddPacket.Entry> entries, PacketWriter writer,
                                      boolean computeSha256, boolean keepBytes) throws Exception {
        boolean previous = MEASURING.get();
        MEASURING.set(Boolean.TRUE);
        ByteBuf scratch = Unpooled.buffer(4096);
        try {
            MessageDigest digest = computeSha256 ? MessageDigest.getInstance("SHA-256") : null;
            // Empty packet: id + VarInt(0) + replace flag.
            int empty = encodedSize(writer, scratch, new ClientboundRecipeBookAddPacket(List.of(), false));
            EncodedEntries kept = null;
            NotKept notKept = null;
            if (keepBytes) {
                notKept = codecWroteWholePacket(empty);
                if (notKept == null) {
                    byte[] emptyFalse = ByteBufUtil.getBytes(scratch, 0, empty);
                    int emptyTrue = encodedSize(writer, scratch, new ClientboundRecipeBookAddPacket(List.of(), true));
                    notKept = codecWroteWholePacket(emptyTrue);
                    if (notKept == null) {
                        kept = EncodedEntries.forLayout(emptyFalse, ByteBufUtil.getBytes(scratch, 0, emptyTrue), entries.size());
                        notKept = kept == null ? NotKept.LAYOUT : null;
                    }
                }
            }
            int fixedOverhead = empty - 1;
            // In a one-entry packet the entry follows the id and the 1-byte count. That is the same number as the
            // fixed overhead (id plus the 1-byte replace flag), but for a different reason.
            int entryOffset = empty - 1;
            int[] sizes = new int[entries.size()];
            for (int i = 0; i < sizes.length; i++) {
                // One-entry packet: id + VarInt(1) + entry + replace flag; both counts take one byte.
                int probe = encodedSize(writer, scratch, new ClientboundRecipeBookAddPacket(List.of(entries.get(i)), false));
                sizes[i] = probe - empty;
                if (digest != null) {
                    digest.update(scratch.nioBuffer(entryOffset, sizes[i]));
                }
                if (kept != null) {
                    notKept = codecWroteWholePacket(probe);
                    if (notKept == null && !kept.probeMatchesLayout(scratch, probe)) {
                        notKept = NotKept.LAYOUT; // not the layout we know: the chunks are encoded normally
                    }
                    if (notKept == null) {
                        kept.append(scratch, entryOffset, sizes[i]);
                    } else {
                        kept = null;
                    }
                }
            }
            return new Measurement(fixedOverhead, sizes, digest == null ? null : HexFormat.of().formatHex(digest.digest()), kept, notKept);
        } finally {
            MEASURING.set(previous);
            scratch.release();
        }
    }

    /**
     * Encodes one packet with the probe writer, flagged as a measuring probe like the ones in {@link #measure}. Used
     * for the exact bytes of a chunk when {@code measure} did not keep any. The caller releases the returned buffer.
     */
    static ByteBuf encodeProbe(PacketWriter writer, Packet<?> packet) throws Exception {
        boolean previous = MEASURING.get();
        MEASURING.set(Boolean.TRUE);
        ByteBuf out = Unpooled.buffer();
        try {
            writer.write(packet, out);
            return out;
        } catch (Throwable t) {
            out.release();
            throw t;
        } finally {
            MEASURING.set(previous);
        }
    }

    private static int encodedSize(PacketWriter writer, ByteBuf scratch, Packet<?> packet) throws Exception {
        scratch.clear();
        CODEC_SPAN.get().calls = 0;
        writer.write(packet, scratch);
        return scratch.readableBytes();
    }

    /**
     * Whether the last probe, {@code length} bytes long, was written by exactly one codec call that wrote all of it and
     * nothing else wrote anything: null if so, else why not.
     */
    private static @Nullable NotKept codecWroteWholePacket(int length) {
        CodecSpan span = CODEC_SPAN.get();
        if (span.calls == 0) {
            return NotKept.NO_CODEC_HOOK;
        }
        return span.calls == 1 && span.start == 0 && span.end == length ? null : NotKept.OUTSIDE_CODEC;
    }
}
