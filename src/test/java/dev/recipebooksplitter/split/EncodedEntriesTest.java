package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.Test;

/** The store of measured entry bytes, without Minecraft's codecs: packets are {@code 0x48 | count | entries | flag}. */
class EncodedEntriesTest {
    private static final byte ID = 0x48;
    private static final byte REPLACE_FALSE = 0;
    private static final byte REPLACE_TRUE = 1;

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static EncodedEntries store(int expectedEntries) {
        EncodedEntries store = EncodedEntries.forLayout(bytes(ID, 0, REPLACE_FALSE), bytes(ID, 0, REPLACE_TRUE), expectedEntries);
        assertNotNull(store);
        return store;
    }

    /** Entry {@code i} is random bytes, so that copying from a wrong place shows. */
    private static byte[] entryBytes(int i, int size) {
        byte[] entry = new byte[size];
        new Random(i).nextBytes(entry);
        return entry;
    }

    /** Appends the entry from a buffer with junk around it, to cover the offset into the source. */
    private static void append(EncodedEntries store, byte[] entry) {
        ByteBuf source = Unpooled.buffer();
        source.writeBytes(bytes(1, 2, 3)).writeBytes(entry).writeBytes(bytes(4, 5));
        store.append(source, 3, entry.length);
        source.release();
    }

    @Test
    void forLayoutRejectsBadLayouts() {
        assertNotNull(EncodedEntries.forLayout(bytes(ID, 0, 0), bytes(ID, 0, 1), 1));
        assertNotNull(EncodedEntries.forLayout(bytes(0x80, 0x01, 0, 0), bytes(0x80, 0x01, 0, 1), 1), "a two-byte packet id");
        assertNull(EncodedEntries.forLayout(bytes(0, 0), bytes(0, 1), 1), "shorter than id, count and flag");
        assertNull(EncodedEntries.forLayout(bytes(ID, 0, 0), bytes(ID, 0, 0, 1), 1), "different lengths");
        assertNull(EncodedEntries.forLayout(bytes(ID, 1, 0), bytes(ID, 1, 1), 1), "an empty packet whose count is not 0");
        assertNull(EncodedEntries.forLayout(bytes(ID, 0, 1), bytes(ID, 0, 1), 1), "the flag does not change the last byte");
        assertNull(EncodedEntries.forLayout(bytes(ID, 0, 0), bytes(0x49, 0, 1), 1), "different packet ids");
    }

    @Test
    void probeLayoutCheck() {
        EncodedEntries store = store(1);

        assertTrue(probe(store, ID, 1, 9, 9, REPLACE_FALSE));
        assertFalse(probe(store, 0x49, 1, 9, 9, REPLACE_FALSE), "other packet id");
        assertFalse(probe(store, ID, 2, 9, 9, REPLACE_FALSE), "count is not 1");
        assertFalse(probe(store, ID, 1, 9, 9, REPLACE_TRUE), "ends with the other flag");
        assertFalse(probe(store, ID, 1, 9, 9, 7), "ends with neither flag");
        assertTrue(probe(store, ID, 1, REPLACE_FALSE), "an entry of no bytes");
        ByteBuf tooShort = Unpooled.wrappedBuffer(bytes(ID, 1));
        assertFalse(store.probeMatchesLayout(tooShort, 2), "no room for an entry slot and the flag");
    }

    private static boolean probe(EncodedEntries store, int... packet) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes(packet));
        try {
            return store.probeMatchesLayout(buf, packet.length);
        } finally {
            buf.release();
        }
    }

    @Test
    void writesContiguousAndScatteredEntries() {
        int segment = EncodedEntries.SEGMENT_BYTES;
        int[] sizes = {0, 1, 300, segment, segment + 7, 2 * segment + 3, 5};
        EncodedEntries store = store(sizes.length);
        byte[][] entries = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            entries[i] = entryBytes(i, sizes[i]);
            append(store, entries[i]);
        }
        int[][] lists = {{0, 1, 2, 3, 4, 5, 6}, {3}, {6}, {0, 2, 3, 6}, {}, {6, 0, 1}, {5, 4}, {4, 5}};

        for (int[] list : lists) {
            for (boolean replace : new boolean[] {false, true}) {
                String label = "entries " + Arrays.toString(list) + " replace=" + replace;
                byte[] expected = packet(entries, list, replace);

                assertEquals(expected.length, store.packetBytes(list), label);
                // Written behind existing content, which stays as it is.
                ByteBuf out = Unpooled.buffer(0);
                out.writeBytes(bytes(7, 7, 7));
                store.writePacket(out, list, replace, expected.length);

                assertEquals(3 + expected.length, out.readableBytes(), label);
                assertArrayEquals(expected, ByteBufUtil.getBytes(out, 3, expected.length), label);
                assertArrayEquals(bytes(7, 7, 7), ByteBufUtil.getBytes(out, 0, 3), label);
                out.release();
            }
        }
    }

    @Test
    void smallPacketsAllocateOnlyWhatTheyNeed() {
        // Every recipe unlock is a packet of one entry of about 100 bytes: it must not cost a full 256 KiB segment.
        EncodedEntries one = store(1);
        byte[] entry = entryBytes(0, 100);
        append(one, entry);
        assertEquals(EncodedEntries.FIRST_SEGMENT_BYTES, one.capacityBytes());
        assertTrue(EncodedEntries.FIRST_SEGMENT_BYTES * 16 <= EncodedEntries.SEGMENT_BYTES);

        ByteBuf out = Unpooled.buffer();
        one.writePacket(out, new int[] {0}, true, one.packetBytes(new int[] {0}));
        assertArrayEquals(packet(new byte[][] {entry}, new int[] {0}, true), ByteBufUtil.getBytes(out));
        out.release();

        EncodedEntries none = store(0);
        assertEquals(0, none.capacityBytes());
    }

    @Test
    void firstSegmentDoublesAndLaterSegmentsAreFull() {
        int segment = EncodedEntries.SEGMENT_BYTES;
        int first = EncodedEntries.FIRST_SEGMENT_BYTES;
        // Sizes that end just under and just over the doubling points, then cross the first border and fill a second.
        int[] prefix = {first - 1, 2, first, 1, 2 * first, 3, 20_000};
        int filler = segment - Arrays.stream(prefix).sum() - 8; // leaves 8 bytes of the first segment free
        int[] sizes = Arrays.copyOf(prefix, prefix.length + 5);
        System.arraycopy(new int[] {filler, 1, 5, segment, 9}, 0, sizes, prefix.length, 5);
        EncodedEntries store = store(sizes.length);
        byte[][] entries = new byte[sizes.length][];
        long total = 0;
        for (int i = 0; i < sizes.length; i++) {
            entries[i] = entryBytes(i, sizes[i]);
            append(store, entries[i]);
            total += sizes[i];
            long expected = total <= segment
                    ? Math.max(first, Long.highestOneBit(total - 1) << 1)
                    : segment * ((total + segment - 1) / segment);
            assertEquals(expected, store.capacityBytes(), "after entry " + i + " (" + total + " bytes kept)");
            // The kept bytes survive every growth.
            int[] soFar = java.util.stream.IntStream.rangeClosed(0, i).toArray();
            ByteBuf out = Unpooled.buffer();
            store.writePacket(out, soFar, false, store.packetBytes(soFar));
            assertArrayEquals(packet(Arrays.copyOf(entries, i + 1), soFar, false), ByteBufUtil.getBytes(out), "after entry " + i);
            out.release();
        }
        assertTrue(total > segment, "the test crosses the first border");
    }

    /** What the encoder writes for these entries, assembled the slow way. */
    private static byte[] packet(byte[][] entries, int[] list, boolean replace) {
        ByteBuf head = Unpooled.buffer();
        head.writeByte(ID);
        VarInt.write(head, list.length);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.writeBytes(ByteBufUtil.getBytes(head));
        head.release();
        for (int index : list) {
            packet.writeBytes(entries[index]);
        }
        packet.write(replace ? REPLACE_TRUE : REPLACE_FALSE);
        return packet.toByteArray();
    }

    @Test
    void varIntCountOfTwoBytes() {
        int count = 200;
        EncodedEntries store = store(count);
        byte[][] entries = new byte[count][];
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            entries[i] = entryBytes(i, 3);
            append(store, entries[i]);
            all[i] = i;
        }

        long bytes = store.packetBytes(all);

        // id and flag are the 2 fixed bytes of the planner's overhead; the count VarInt is 2 bytes from 128 on.
        assertEquals(ChunkPlanner.packetBytes(2, count, 3L * count), bytes);
        ByteBuf out = Unpooled.buffer();
        store.writePacket(out, all, true, bytes);
        assertArrayEquals(packet(entries, all, true), ByteBufUtil.getBytes(out));
        assertEquals(2, VarInt.getByteSize(count));
        out.release();
    }

    @Test
    void sizeMismatchThrowsBeforeWriting() {
        EncodedEntries store = store(2);
        append(store, entryBytes(0, 10));
        append(store, entryBytes(1, 10));
        ByteBuf out = Unpooled.buffer();
        out.writeBytes(bytes(7, 7, 7));
        long expected = store.packetBytes(new int[] {0, 1});

        assertThrows(IllegalStateException.class, () -> store.writePacket(out, new int[] {0, 1}, false, expected + 1));
        assertThrows(IllegalStateException.class, () -> store.writePacket(out, new int[] {0}, false, expected));

        assertEquals(3, out.writerIndex(), "nothing was written");
        assertThrows(IndexOutOfBoundsException.class, () -> store.packetBytes(new int[] {2}), "there is no third entry");
        out.release();
    }
}
