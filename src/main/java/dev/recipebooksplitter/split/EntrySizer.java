package dev.recipebooksplitter.split;

import io.netty.buffer.ByteBuf;
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

    private EntrySizer() {}

    /** True while this thread is encoding a probe packet, so encoder hooks can ignore it. */
    public static boolean isMeasuring() {
        return MEASURING.get();
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
     */
    public record Measurement(int fixedOverheadBytes, int[] entryBytes, @Nullable String sha256) {
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
     */
    public static Measurement measure(List<ClientboundRecipeBookAddPacket.Entry> entries, PacketWriter writer,
                                      boolean computeSha256) throws Exception {
        boolean previous = MEASURING.get();
        MEASURING.set(Boolean.TRUE);
        ByteBuf scratch = Unpooled.buffer(4096);
        try {
            MessageDigest digest = computeSha256 ? MessageDigest.getInstance("SHA-256") : null;
            // Empty packet: id + VarInt(0) + replace flag.
            int empty = encodedSize(writer, scratch, new ClientboundRecipeBookAddPacket(List.of(), false));
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
            }
            return new Measurement(fixedOverhead, sizes, digest == null ? null : HexFormat.of().formatHex(digest.digest()));
        } finally {
            MEASURING.set(previous);
            scratch.release();
        }
    }

    private static int encodedSize(PacketWriter writer, ByteBuf scratch, Packet<?> packet) throws Exception {
        scratch.clear();
        writer.write(packet, scratch);
        return scratch.readableBytes();
    }
}
