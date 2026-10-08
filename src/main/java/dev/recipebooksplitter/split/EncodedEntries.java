package dev.recipebooksplitter.split;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.VarInt;
import org.jspecify.annotations.Nullable;

/**
 * The bytes the measuring probes produced for each entry, kept so that the chunks (or the unsplit packet) can be
 * written without encoding the entries a second time. Used by one event loop thread within one send task, so there is
 * no synchronization; plain heap arrays, so there is nothing to release and the garbage collector reclaims them once
 * the task is over.
 *
 * <p>A recipe book add packet is {@code header (packet id) | VarInt count | entries | 1-byte replace flag}. The header
 * and the two possible flag bytes come from two empty probe packets; every one-entry probe is checked against that
 * layout before its entry bytes are kept.
 */
public final class EncodedEntries {
    /** Below G1's humongous threshold (half a region), and G1 regions are at least 1 MiB. */
    static final int SEGMENT_BYTES = 256 * 1024;
    /**
     * The first segment starts this small and doubles up to {@link #SEGMENT_BYTES}, so that the many packets with one
     * or a few entries (every recipe unlock is one, about 100 bytes) do not allocate and zero 256 KiB, or even 4 KiB.
     * A packet that needs a second segment is a big one, and its later segments are allocated at full size.
     */
    static final int FIRST_SEGMENT_BYTES = 256;

    private final byte[] header;
    private final byte replaceFalse;
    private final byte replaceTrue;
    private final List<byte[]> segments = new ArrayList<>();
    /** {@code starts[i]} is where entry {@code i} begins in the segments; {@code starts[count]} is the total. */
    private long[] starts;
    private int count;
    private long size;

    private EncodedEntries(byte[] header, byte replaceFalse, byte replaceTrue, int expectedEntries) {
        this.header = header;
        this.replaceFalse = replaceFalse;
        this.replaceTrue = replaceTrue;
        this.starts = new long[expectedEntries + 1];
    }

    /**
     * @param emptyFalse bytes of an empty packet with replace=false
     * @param emptyTrue bytes of an empty packet with replace=true
     * @return null if the two packets do not have the expected layout (another mod changed it); then nothing is kept
     */
    static @Nullable EncodedEntries forLayout(byte[] emptyFalse, byte[] emptyTrue, int expectedEntries) {
        int n = emptyFalse.length;
        if (n < 3 || emptyTrue.length != n || emptyFalse[n - 2] != 0
                || !Arrays.equals(emptyFalse, 0, n - 1, emptyTrue, 0, n - 1) || emptyFalse[n - 1] == emptyTrue[n - 1]) {
            return null;
        }
        return new EncodedEntries(Arrays.copyOf(emptyFalse, n - 2), emptyFalse[n - 1], emptyTrue[n - 1], expectedEntries);
    }

    /**
     * True if the one-entry probe packet in {@code probe}, {@code length} bytes long, has the layout assumed here, so
     * that its entry starts right behind the count byte, {@code header.length + 1} bytes in.
     */
    boolean probeMatchesLayout(ByteBuf probe, int length) {
        if (length < header.length + 2) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (probe.getByte(i) != header[i]) {
                return false;
            }
        }
        return probe.getByte(header.length) == 1 && probe.getByte(length - 1) == replaceFalse;
    }

    /** Keeps {@code length} bytes of {@code src} from {@code index} on as the next entry. */
    void append(ByteBuf src, int index, int length) {
        if (count + 1 >= starts.length) {
            starts = Arrays.copyOf(starts, Math.max(16, starts.length * 2));
        }
        starts[count] = size;
        for (int done = 0; done < length; ) {
            int segment = (int) (size / SEGMENT_BYTES);
            int offset = (int) (size % SEGMENT_BYTES);
            int n = Math.min(length - done, SEGMENT_BYTES - offset);
            src.getBytes(index + done, segmentWithRoom(segment, offset + n), offset, n);
            done += n;
            size += n;
        }
        starts[++count] = size;
    }

    /**
     * The segment, at least {@code needed} bytes long. Only the first segment can be shorter than
     * {@link #SEGMENT_BYTES}; it doubles (a power of two from {@link #FIRST_SEGMENT_BYTES} to {@link #SEGMENT_BYTES}).
     * {@code copy} reads below {@code size} only, which is always inside what was allocated here.
     */
    private byte[] segmentWithRoom(int segment, int needed) {
        if (segment > 0) {
            if (segment == segments.size()) {
                segments.add(new byte[SEGMENT_BYTES]);
            }
            return segments.get(segment);
        }
        if (segments.isEmpty()) {
            segments.add(new byte[FIRST_SEGMENT_BYTES]);
        }
        byte[] first = segments.get(0);
        if (first.length < needed) {
            int capacity = first.length;
            while (capacity < needed) {
                capacity *= 2;
            }
            first = Arrays.copyOf(first, capacity);
            segments.set(0, first);
        }
        return first;
    }

    /** How many bytes of storage this holds (allocated, not just used). */
    long capacityBytes() {
        long sum = 0;
        for (byte[] segment : segments) {
            sum += segment.length;
        }
        return sum;
    }

    /** Size of the packet {@link #writePacket} produces for these entries: header, count, entries and flag. */
    long packetBytes(int[] entries) {
        long sum = 0;
        for (int entry : entries) {
            Objects.checkIndex(entry, count);
            sum += starts[entry + 1] - starts[entry];
        }
        return header.length + VarInt.getByteSize(entries.length) + sum + 1;
    }

    /**
     * Writes the complete packet exactly as the encoder would: the given entries in the given order, then the flag.
     *
     * @throws IllegalStateException before writing anything if the packet would not be {@code expectedBytes} long
     */
    void writePacket(ByteBuf out, int[] entries, boolean replace, long expectedBytes) {
        long total = packetBytes(entries);
        if (total != expectedBytes) {
            throw new IllegalStateException("kept " + total + " bytes for a packet planned as " + expectedBytes);
        }
        out.ensureWritable(Math.toIntExact(total));
        out.writeBytes(header);
        VarInt.write(out, entries.length);
        // Entries that were neighbours in the book are neighbours in the segments: one copy per run.
        for (int k = 0; k < entries.length; ) {
            int first = entries[k];
            int last = first;
            while (++k < entries.length && entries[k] == last + 1) {
                last++;
            }
            copy(out, starts[first], starts[last + 1]);
        }
        out.writeByte(replace ? replaceTrue : replaceFalse);
    }

    private void copy(ByteBuf out, long from, long to) {
        for (long position = from; position < to; ) {
            int segment = (int) (position / SEGMENT_BYTES);
            int offset = (int) (position % SEGMENT_BYTES);
            int n = (int) Math.min(to - position, SEGMENT_BYTES - offset);
            out.writeBytes(segments.get(segment), offset, n);
            position += n;
        }
    }
}
