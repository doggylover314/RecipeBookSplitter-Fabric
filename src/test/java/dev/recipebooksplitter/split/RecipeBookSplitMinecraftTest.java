package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.recipebooksplitter.split.ChunkPlanner.Chunk;
import dev.recipebooksplitter.testutil.FakeClientRecipeBook;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Measuring and splitting with Minecraft's real codecs, and what a client would end up with. */
class RecipeBookSplitMinecraftTest {
    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    private static int encodedSize(ClientboundRecipeBookAddPacket packet) throws Exception {
        ByteBuf buf = RecipeFixtures.encode(packet);
        try {
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    private static EntrySizer.Measurement measure(List<Entry> entries) throws Exception {
        return EntrySizer.measure(entries, RecipeFixtures.writer(), false, false);
    }

    private static List<Entry> pads(int... padBytes) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < padBytes.length; i++) {
            entries.add(RecipeFixtures.entry(i, padBytes[i], (byte) (i % 4)));
        }
        return entries;
    }

    @Test
    void measurementIsExact() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            entries.add(RecipeFixtures.entry(i, (i * 37) % 3000, (byte) (i % 4)));
        }

        EntrySizer.Measurement measurement = measure(entries);

        assertEquals(encodedSize(new ClientboundRecipeBookAddPacket(entries, true)), measurement.totalBytes());
        assertEquals(encodedSize(new ClientboundRecipeBookAddPacket(entries, false)), measurement.totalBytes());
        assertEquals(encodedSize(new ClientboundRecipeBookAddPacket(List.of(), false)) - 1, measurement.fixedOverheadBytes());

        // Each measured entry is exactly what the entry codec writes on its own.
        for (int i = 0; i < entries.size(); i++) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RecipeFixtures.access());
            Entry.STREAM_CODEC.encode(buf, entries.get(i));
            assertEquals(buf.writerIndex(), measurement.entryBytes()[i], "entry " + i);
            buf.release();
        }
    }

    @Test
    void varIntSizeMatchesMinecraft() {
        for (int value : new int[] {0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455, 268_435_456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            assertEquals(VarInt.getByteSize(value), ChunkPlanner.varIntSize(value), "value " + value);
        }
    }

    @Test
    void digestCoversExactlyTheEntryBytes() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(40);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (Entry entry : entries) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RecipeFixtures.access());
            Entry.STREAM_CODEC.encode(buf, entry);
            expected.update(buf.nioBuffer());
            buf.release();
        }

        EntrySizer.Measurement measurement = EntrySizer.measure(entries, RecipeFixtures.writer(), true, false);

        assertEquals(HexFormat.of().formatHex(expected.digest()), measurement.sha256());
        assertNull(measure(entries).sha256());
    }

    @Test
    void measuringFlagIsSetOnlyWhileMeasuring() throws Exception {
        AtomicBoolean seenWhileWriting = new AtomicBoolean(true);
        EntrySizer.PacketWriter writer = (packet, out) -> {
            seenWhileWriting.compareAndSet(true, EntrySizer.isMeasuring());
            RecipeFixtures.writer().write(packet, out);
        };

        assertFalse(EntrySizer.isMeasuring());
        EntrySizer.measure(RecipeFixtures.entries(3), writer, false, false);

        assertTrue(seenWhileWriting.get());
        assertFalse(EntrySizer.isMeasuring());
    }

    @Test
    void splitEqualsOriginalOnClient() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);
        EntrySizer.Measurement measurement = measure(entries);

        for (int budget : new int[] {65_536, 100_000}) {
            for (boolean replace : new boolean[] {true, false}) {
                String label = "budget=" + budget + " replace=" + replace;
                List<Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), budget);
                assertTrue(plan.size() > 1, label);
                List<ClientboundRecipeBookAddPacket> chunks =
                        ChunkPlanner.split(entries, replace, plan, ClientboundRecipeBookAddPacket::new);

                // Decode every chunk from its wire bytes, as a client would.
                List<ClientboundRecipeBookAddPacket> decodedChunks = new ArrayList<>();
                for (int k = 0; k < chunks.size(); k++) {
                    ByteBuf frame = RecipeFixtures.encode(chunks.get(k));
                    assertEquals(plan.get(k).bytes(), frame.readableBytes(), label + " chunk " + k);
                    assertTrue(frame.readableBytes() <= budget, label + " chunk " + k);
                    decodedChunks.add((ClientboundRecipeBookAddPacket) RecipeFixtures.decode(frame));
                }
                ClientboundRecipeBookAddPacket decodedWhole =
                        (ClientboundRecipeBookAddPacket) RecipeFixtures.decode(RecipeFixtures.encode(new ClientboundRecipeBookAddPacket(entries, replace)));

                // Replace flags: first chunk only, and only if the original had it.
                for (int k = 0; k < decodedChunks.size(); k++) {
                    assertEquals(replace && k == 0, decodedChunks.get(k).replace(), label + " chunk " + k);
                }

                // Same entries in the same order with the same flags and the same bytes.
                List<Entry> flattened = decodedChunks.stream().flatMap(chunk -> chunk.entries().stream()).toList();
                assertEquals(entries.size(), flattened.size(), label);
                for (int i = 0; i < entries.size(); i++) {
                    assertEquals(entries.get(i).contents().id(), flattened.get(i).contents().id(), label + " entry " + i);
                    assertEquals(entries.get(i).flags(), flattened.get(i).flags(), label + " entry " + i);
                    assertEquals(RecipeFixtures.render(entries.get(i).contents()), RecipeFixtures.render(flattened.get(i).contents()), label + " entry " + i);
                }

                // The client ends up in the same state, including stale entries a replace has to remove.
                FakeClientRecipeBook whole = new FakeClientRecipeBook();
                FakeClientRecipeBook split = new FakeClientRecipeBook();
                whole.seedHighlighted(100_000, 10);
                split.seedHighlighted(100_000, 10);
                whole.apply(decodedWhole, RecipeFixtures::render);
                decodedChunks.forEach(chunk -> split.apply(chunk, RecipeFixtures::render));

                assertEquals(whole.known, split.known, label);
                assertEquals(whole.highlight, split.highlight, label);
                assertEquals(whole.toasts, split.toasts, label);
                assertEquals(!replace, split.known.containsKey(100_000), label + ": stale entries are removed by replace only");
                assertEquals(!replace, split.highlight.contains(100_009), label);
                assertEquals(entries.size() + (replace ? 0 : 10), split.known.size(), label);
            }
        }
    }

    @Test
    void orderingIsDetectable() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);
        EntrySizer.Measurement measurement = measure(entries);
        List<Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), 65_536);
        List<ClientboundRecipeBookAddPacket> chunks = ChunkPlanner.split(entries, true, plan, ClientboundRecipeBookAddPacket::new);

        FakeClientRecipeBook inOrder = new FakeClientRecipeBook();
        FakeClientRecipeBook reversed = new FakeClientRecipeBook();
        chunks.forEach(chunk -> inOrder.apply(chunk, RecipeFixtures::render));
        chunks.reversed().forEach(chunk -> reversed.apply(chunk, RecipeFixtures::render));

        assertEquals(entries.size(), inOrder.known.size());
        assertNotEquals(inOrder.known, reversed.known, "applying the replace chunk last must lose the other chunks");
        assertNotEquals(inOrder.toasts, reversed.toasts);
    }

    @Test
    void oversizedEntryGoesAloneAndEverythingStillDecodes() throws Exception {
        int[] padBytes = new int[20];
        Arrays.fill(padBytes, 500);
        padBytes[7] = 150_000;
        List<Entry> entries = pads(padBytes);
        EntrySizer.Measurement measurement = measure(entries);

        List<Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), 65_536);

        Chunk big = plan.stream().filter(Chunk::oversized).findFirst().orElseThrow();
        assertEquals(7, big.start());
        assertEquals(1, big.entryCount());
        assertEquals(1, plan.stream().filter(Chunk::oversized).count());
        assertTrue(plan.stream().filter(chunk -> !chunk.oversized()).allMatch(chunk -> chunk.bytes() <= 65_536));

        List<ClientboundRecipeBookAddPacket> chunks = ChunkPlanner.split(entries, true, plan, ClientboundRecipeBookAddPacket::new);
        List<Entry> decoded = new ArrayList<>();
        for (int k = 0; k < chunks.size(); k++) {
            ByteBuf frame = RecipeFixtures.encode(chunks.get(k));
            assertEquals(plan.get(k).bytes(), frame.readableBytes());
            decoded.addAll(((ClientboundRecipeBookAddPacket) RecipeFixtures.decode(frame)).entries());
        }
        assertEquals(entries.size(), decoded.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(RecipeFixtures.render(entries.get(i).contents()), RecipeFixtures.render(decoded.get(i).contents()), "entry " + i);
        }
    }

    @Test
    void underBudgetIsASingleChunk() throws Exception {
        List<Entry> entries = pads(100, 200, 300, 400, 500);
        EntrySizer.Measurement measurement = measure(entries);

        List<Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), 65_536);

        assertEquals(1, plan.size());
        assertEquals(new Chunk(0, 5, measurement.totalBytes(), false), plan.get(0));
    }

    @Test
    void emptyAndSingleEntryPacketsMeasure() throws Exception {
        assertEquals(0, measure(List.of()).entryBytes().length);
        List<Entry> single = pads(1000);
        assertEquals(encodedSize(new ClientboundRecipeBookAddPacket(single, false)), measure(single).totalBytes());
    }

    @Test
    void keptBytesEqualCodecBytesForEveryChunk() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);
        EntrySizer.Measurement measurement = EntrySizer.measure(entries, RecipeFixtures.writer(), false, true);
        List<Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), 262_144);
        assertTrue(plan.size() > 1);
        assertNotNull(measurement.encoded());

        for (boolean replace : new boolean[] {true, false}) {
            List<ClientboundRecipeBookAddPacket> chunks = ChunkPlanner.split(entries, replace, plan, ClientboundRecipeBookAddPacket::new);
            for (int k = 0; k < chunks.size(); k++) {
                Chunk chunk = plan.get(k);
                ByteBuf expected = RecipeFixtures.encode(chunks.get(k));
                ByteBuf kept = Unpooled.buffer();
                measurement.encoded().writePacket(kept, IntStream.range(chunk.start(), chunk.end()).toArray(), chunks.get(k).replace(), chunk.bytes());

                assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(kept), "replace=" + replace + " chunk " + k);
                expected.release();
                kept.release();
            }
        }
    }

    @Test
    void keepingBytesDoesNotChangeSizesOrDigest() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(60);

        EntrySizer.Measurement without = EntrySizer.measure(entries, RecipeFixtures.writer(), true, false);
        EntrySizer.Measurement with = EntrySizer.measure(entries, RecipeFixtures.writer(), true, true);

        assertNull(without.encoded());
        assertNotNull(with.encoded());
        assertArrayEquals(without.entryBytes(), with.entryBytes());
        assertEquals(without.fixedOverheadBytes(), with.fixedOverheadBytes());
        assertEquals(without.sha256(), with.sha256());
        assertEquals(with.totalBytes(), with.encoded().packetBytes(IntStream.range(0, entries.size()).toArray()));
    }

    @Test
    void foreignLayoutKeepsNothing() throws Exception {
        // A mod that appends something to every packet: the layout "id | count | entries | flag" no longer holds, and
        // the codec call did not write the whole packet.
        EntrySizer.PacketWriter trailing = (packet, out) -> {
            RecipeFixtures.writer().write(packet, out);
            out.writeByte(0);
        };

        EntrySizer.Measurement measurement = EntrySizer.measure(RecipeFixtures.entries(5), trailing, false, true);

        assertNull(measurement.encoded());
        assertEquals(EntrySizer.NotKept.OUTSIDE_CODEC, measurement.notKept());
        assertEquals(5, measurement.entryBytes().length);
    }

    @Test
    void unexpectedLayoutInsideTheCodecKeepsNothing() throws Exception {
        // The codec call itself wrote a different last byte for a one-entry packet: neither replace flag.
        EntrySizer.PacketWriter odd = (packet, out) -> {
            RecipeFixtures.writer().write(packet, out);
            if (((ClientboundRecipeBookAddPacket) packet).entries().size() == 1) {
                out.setByte(out.writerIndex() - 1, 7);
            }
        };

        EntrySizer.Measurement measurement = EntrySizer.measure(RecipeFixtures.entries(5), odd, false, true);

        assertNull(measurement.encoded());
        assertEquals(EntrySizer.NotKept.LAYOUT, measurement.notKept());
    }

    /** Another mod's hook at the head of PacketEncoder.encode, or at its end: bytes the codec call did not write. */
    @Test
    void bytesWrittenAroundTheCodecCallKeepNothing() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(5);
        EntrySizer.Measurement plain = EntrySizer.measure(entries, RecipeFixtures.writer(), false, false);
        EntrySizer.PacketWriter prefixed = (packet, out) -> {
            out.writeByte(0x7F);
            RecipeFixtures.writer().write(packet, out);
        };
        EntrySizer.PacketWriter varyingPrefix = new EntrySizer.PacketWriter() {
            private int counter;

            @Override
            public void write(Packet<?> packet, ByteBuf out) throws Exception {
                out.writeByte(++counter & 0x7F);
                RecipeFixtures.writer().write(packet, out);
            }
        };
        EntrySizer.PacketWriter suffixed = (packet, out) -> {
            RecipeFixtures.writer().write(packet, out);
            out.writeByte(0x55);
        };

        for (EntrySizer.PacketWriter writer : new EntrySizer.PacketWriter[] {prefixed, varyingPrefix, suffixed}) {
            EntrySizer.Measurement measurement = EntrySizer.measure(entries, writer, false, true);

            assertNull(measurement.encoded());
            assertEquals(EntrySizer.NotKept.OUTSIDE_CODEC, measurement.notKept());
            // The sizes are those of the entries, whatever is written around them.
            assertArrayEquals(plain.entryBytes(), measurement.entryBytes());
        }
    }

    @Test
    void codecCalledTwiceKeepsNothing() throws Exception {
        EntrySizer.PacketWriter twice = (packet, out) -> {
            ByteBuf scrap = Unpooled.buffer();
            RecipeFixtures.writer().write(packet, scrap); // a first codec call, into a buffer of its own
            scrap.release();
            RecipeFixtures.writer().write(packet, out);
        };

        EntrySizer.Measurement measurement = EntrySizer.measure(RecipeFixtures.entries(3), twice, false, true);

        assertNull(measurement.encoded());
        assertEquals(EntrySizer.NotKept.OUTSIDE_CODEC, measurement.notKept());
    }

    @Test
    void withoutTheHookNothingIsKeptAndNoMoreProbesAreSpent() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        EntrySizer.PacketWriter codec = RecipeFixtures.writerWithoutHook();
        EntrySizer.PacketWriter counting = (packet, out) -> {
            probes.incrementAndGet();
            codec.write(packet, out);
        };

        EntrySizer.Measurement without = EntrySizer.measure(RecipeFixtures.entries(5), counting, false, true);

        assertNull(without.encoded());
        assertEquals(EntrySizer.NotKept.NO_CODEC_HOOK, without.notKept());
        assertEquals(1 + 5, probes.get(), "no second empty probe for the other replace flag, as nothing is kept");
        assertEquals(5, without.entryBytes().length);
        // Not asked to keep anything: no reason either.
        assertNull(EntrySizer.measure(RecipeFixtures.entries(5), codec, false, false).notKept());
        assertNull(EntrySizer.measure(RecipeFixtures.entries(5), RecipeFixtures.writer(), false, true).notKept());
    }

    /**
     * The 1.1.0 review found 271 KB allocated per one-entry packet (a recipe unlock), 100 times what the entry needs:
     * the kept bytes started with a 256 KiB segment.
     */
    @Test
    void measuringOneEntryKeepingBytesAllocatesLittle() throws Exception {
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "no per-thread allocation counter on this JVM");
        threads.setThreadAllocatedMemoryEnabled(true);
        List<Entry> one = RecipeFixtures.entries(1);
        EntrySizer.PacketWriter writer = RecipeFixtures.writer();
        for (int i = 0; i < 500; i++) {
            EntrySizer.measure(one, writer, false, true);
        }

        int rounds = 200;
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < rounds; i++) {
            assertNotNull(EntrySizer.measure(one, writer, false, true).encoded());
        }
        long perCall = (threads.getCurrentThreadAllocatedBytes() - before) / rounds;

        // The three probe encodes and their buffers are most of it; the first kept segment is 256 bytes.
        assertTrue(perCall < 64 * 1024, perCall + " bytes allocated per measured one-entry packet");
    }
}
