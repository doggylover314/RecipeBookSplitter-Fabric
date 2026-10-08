package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Entries that the connection cannot send even alone, through the mod and the real vanilla pipeline. */
class UndeliverableEntryIntegrationTest {
    private static final int BUDGET = 262_144;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @AfterEach
    void tearDown() {
        RecipeBookSendInterceptor.encodeOnce = true;
        RecipeBookSendInterceptor.debugDigest = false;
        ConnectionLimits.compressionLimitMayBeLifted = false;
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    private static void config(UndeliverableEntries mode) {
        RecipeBookSplitter.setConfig(TestConfigs.of(BUDGET, false, mode, false));
    }

    private static byte[] encoded(ClientboundRecipeBookAddPacket packet) throws Exception {
        ByteBuf buf = RecipeFixtures.encode(packet);
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static List<Integer> ids(List<VanillaPipeline.Frame> frames) {
        return frames.stream().map(VanillaPipeline.Frame::packet).filter(ClientboundRecipeBookAddPacket.class::isInstance)
                .flatMap(p -> ((ClientboundRecipeBookAddPacket) p).entries().stream())
                .map(e -> e.contents().id().index()).toList();
    }

    private static List<ClientboundRecipeBookAddPacket> books(List<VanillaPipeline.Frame> frames) {
        return frames.stream().map(VanillaPipeline.Frame::packet).filter(ClientboundRecipeBookAddPacket.class::isInstance)
                .map(ClientboundRecipeBookAddPacket.class::cast).toList();
    }

    @Test
    void dropLeavesOutTheEntryAndKeepsTheConnection() throws Exception {
        config(UndeliverableEntries.DROP);
        List<Entry> entries = List.of(RecipeFixtures.entry(10, 100, (byte) 1), RecipeFixtures.entry(11, 2_200_000, (byte) 3),
                RecipeFixtures.entry(12, 100, (byte) 2));
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            assertTrue(pipe.channel.isOpen(), "still connected");
            List<VanillaPipeline.Frame> frames = pipe.readFrames();
            assertEquals(List.of(10, 12), ids(frames));
            assertTrue(books(frames).get(0).replace());
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains("left out recipe display entry #1 (display id 11, recipe unknown)"), errors.get(0));
            assertTrue(errors.get(0).contains("network compression is off, and a frame can hold at most 2,097,151 bytes"), errors.get(0));
        }
    }

    @Test
    void droppedFirstEntryPassesReplaceToTheNextChunk() throws Exception {
        config(UndeliverableEntries.DROP);
        List<Entry> entries = List.of(RecipeFixtures.entry(20, 2_200_000, (byte) 0), RecipeFixtures.entry(21, 100, (byte) 0));
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false)) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            List<ClientboundRecipeBookAddPacket> books = books(pipe.readFrames());
            assertEquals(1, books.size());
            assertTrue(books.get(0).replace(), "the client must still clear its book");
            assertEquals(List.of(21), books.get(0).entries().stream().map(e -> e.contents().id().index()).toList());
        }
    }

    @Test
    void singleUndeliverableEntryBecomesAnEmptyPacket() throws Exception {
        config(UndeliverableEntries.DROP);
        for (boolean replace : new boolean[] {true, false}) {
            try (VanillaPipeline pipe = new VanillaPipeline(-1, false)) {
                pipe.connection.send(new ClientboundRecipeBookAddPacket(List.of(RecipeFixtures.entry(30, 2_200_000, (byte) 2)), replace));
                pipe.channel.runPendingTasks();

                assertTrue(pipe.channel.isOpen());
                List<ClientboundRecipeBookAddPacket> books = books(pipe.readFrames());
                assertEquals(1, books.size());
                assertTrue(books.get(0).entries().isEmpty());
                assertEquals(replace, books.get(0).replace(), "the original replace flag");
            }
        }
    }

    @Test
    void compressedConnectionDropsOnlyWhatCannotBeSent() throws Exception {
        config(UndeliverableEntries.DROP);
        List<Entry> entries = List.of(
                RecipeFixtures.entry(40, 100, (byte) 0),
                RecipeFixtures.incompressibleEntry(41, 3_000_000, (byte) 0), // under 8 MiB, but its frame would be ~3 MB
                RecipeFixtures.entry(42, 4_500_000, (byte) 0),              // compresses well: sendable
                RecipeFixtures.entry(43, 9_000_000, (byte) 0));             // over 8 MiB
        try (VanillaPipeline pipe = new VanillaPipeline(256, true); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, false));
            pipe.channel.runPendingTasks();

            assertTrue(pipe.channel.isOpen());
            List<VanillaPipeline.Frame> frames = pipe.readFrames();
            assertEquals(2, frames.size(), "entry 40 in one chunk, entry 42 alone");
            assertEquals(List.of(40), ids(frames));
            // Entry 42 arrives intact, but a vanilla client could not decode it: 4.5 MB of byte array exceed the
            // 2 MiB NBT quota of custom_data. The connection limits do not know about that.
            byte[] expected42 = encoded(new ClientboundRecipeBookAddPacket(List.of(entries.get(2)), false));
            assertArrayEquals(expected42, frames.get(1).data());
            assertTrue(frames.get(1).decodeError().contains("NbtAccounterException"), frames.get(1).decodeError());
            assertTrue(frames.stream().allMatch(f -> f.frameBodyBytes() <= ConnectionLimits.FRAME_LIMIT_BYTES));
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(2, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains("display id 41") && errors.get(0).contains("compresses to a"), errors.get(0));
            assertTrue(errors.get(1).contains("display id 43") && errors.get(1).contains("over 8,388,608 bytes"), errors.get(1));
            List<String> warnings = log.messages(Level.WARN);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("display id 42") && w.contains("network compression lets this connection send it")),
                    warnings.toString());
        }
    }

    /**
     * Review finding: Packet Fixer lifts the 8 MiB check inside the vanilla encoder, so "drop" must not decide on that
     * size alone. The test pipeline is vanilla and still refuses the entry; what is checked is the mod's decision.
     */
    @Test
    void entryOverEightMiBIsNotLeftOutWhereTheCompressionLimitMayBeLifted() throws Exception {
        config(UndeliverableEntries.DROP);
        ConnectionLimits.compressionLimitMayBeLifted = true;
        List<Entry> entries = List.of(RecipeFixtures.entry(100, 100, (byte) 0), RecipeFixtures.entry(101, 9_000_000, (byte) 0),
                RecipeFixtures.incompressibleEntry(102, 3_000_000, (byte) 0));
        try (VanillaPipeline pipe = new VanillaPipeline(256, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, false));
            pipe.channel.runPendingTasks();

            List<Integer> handedToTheEncoder = pipe.recorder.messages.stream().filter(ClientboundRecipeBookAddPacket.class::isInstance)
                    .flatMap(p -> ((ClientboundRecipeBookAddPacket) p).entries().stream()).map(e -> e.contents().id().index()).toList();
            assertEquals(List.of(100, 101), handedToTheEncoder, "the 9 MB entry is sent, the 3 MB random one cannot pass any frame");
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains("display id 102") && errors.get(0).contains("compresses to a"), errors.get(0));
            List<String> warnings = log.messages(Level.WARN);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("display id 101")
                    && w.endsWith("; it is over 8,388,608 bytes, which network compression refuses unless a mod such as Packet Fixer lifts that limit, so whether this connection can send it is unknown")), warnings.toString());
        }
    }

    @Test
    void sendModeSendsItAndTheConnectionFailsAsWithoutTheMod() throws Exception {
        config(UndeliverableEntries.SEND);
        List<Entry> entries = List.of(RecipeFixtures.entry(50, 100, (byte) 0), RecipeFixtures.entry(51, 2_200_000, (byte) 0));
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            assertTrue(log.messages(Level.ERROR).stream().anyMatch(m -> m.contains("sending it anyway because undeliverableEntries is \"send\"")));
            assertFalse(pipe.channel.isOpen(), "the frame encoder threw and the connection was closed, as in vanilla");
        }
    }

    @Test
    void unknownPipelineDropsNothing() throws Exception {
        config(UndeliverableEntries.DROP);
        List<Entry> entries = List.of(RecipeFixtures.entry(60, 100, (byte) 0), RecipeFixtures.entry(61, 2_200_000, (byte) 0));
        // Encoder only, no prepender: nothing is known about the limits, so the entry is sent (with a WARN).
        try (TestConnection test = TestConnection.create(new PacketEncoder<>(RecipeFixtures.protocol()));
             LogCapture log = new LogCapture("RecipeBookSplitter")) {
            test.connection().send(new ClientboundRecipeBookAddPacket(entries, true));
            test.channel().runPendingTasks();

            assertEquals(2, test.recorder().messages.size());
            assertTrue(log.messages(Level.ERROR).isEmpty());
            List<String> warnings = log.messages(Level.WARN);
            assertTrue(warnings.stream().anyMatch(w -> w.contains("display id 61")
                    && w.endsWith("; this connection's compression or framing is not vanilla's, so whether it can send more than 2,097,151 bytes is unknown")), warnings.toString());
        }
    }

    @Test
    void undeliverableEntryInsideABundleIsDroppedInPlace() throws Exception {
        config(UndeliverableEntries.DROP);
        Packet<ClientGamePacketListener> motion = new ClientboundSetEntityMotionPacket(5, Vec3.ZERO);
        ClientboundRecipeBookAddPacket book = new ClientboundRecipeBookAddPacket(
                List.of(RecipeFixtures.entry(70, 100, (byte) 0), RecipeFixtures.entry(71, 2_200_000, (byte) 0)), false);
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false)) {
            pipe.connection.send(new ClientboundBundlePacket(List.of(motion, book)));
            pipe.channel.runPendingTasks();

            assertTrue(pipe.channel.isOpen());
            List<VanillaPipeline.Frame> frames = pipe.readFrames();
            assertEquals(4, frames.size(), "delimiter, motion, one chunk, delimiter");
            assertInstanceOf(ClientboundSetEntityMotionPacket.class, frames.get(1).packet());
            assertEquals(List.of(70), ids(frames));
        }
    }

    /**
     * A book of two chunks with an undeliverable entry in the middle of the first one: that chunk's entries are
     * not neighbours in the measured bytes, and are still written from them.
     */
    @Test
    void droppedEntriesLeaveNonContiguousChunksThatAreStillReused() throws Exception {
        config(UndeliverableEntries.DROP);
        List<Entry> entries = new ArrayList<>(RecipeFixtures.entries(100));
        entries.add(10, RecipeFixtures.entry(5_000, 2_200_000, (byte) 0));

        List<VanillaPipeline.Frame> once;
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            once = pipe.readFrames();
            assertEquals(2, once.size());
            assertEquals(entries.stream().map(e -> e.contents().id().index()).filter(id -> id != 5_000).toList(), ids(once));
            assertTrue(books(once).get(0).replace());
            assertFalse(books(once).get(1).replace());
            assertEquals(2, pipe.hadPrepared.stream().filter(Boolean::booleanValue).count(), "both chunks from measured bytes");
            List<String> info = log.messages(Level.INFO);
            assertEquals(1, info.size(), info.toString());
            assertTrue(info.get(0).endsWith("; measured bytes reused for 2 of 2 packets"), info.get(0));
        }

        RecipeBookSendInterceptor.encodeOnce = false;
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false)) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            List<VanillaPipeline.Frame> normal = pipe.readFrames();
            assertEquals(normal.size(), once.size());
            for (int i = 0; i < normal.size(); i++) {
                assertArrayEquals(normal.get(i).wire(), once.get(i).wire(), "frame " + i);
            }
        }
    }

    /** The deflate check gives the same verdict whether the entry's bytes were kept or encoded again. */
    @Test
    void deflateCheckSameWithAndWithoutKeptBytes() throws Exception {
        config(UndeliverableEntries.DROP);
        int base = encoded(new ClientboundRecipeBookAddPacket(List.of(RecipeFixtures.entry(81, 0, (byte) 0)), false)).length;
        List<Entry> entries = List.of(RecipeFixtures.entry(80, 100, (byte) 0),
                RecipeFixtures.incompressibleEntry(81, 2_097_000 - base, (byte) 0), // 2,097,000 bytes fit a frame raw, but not deflated
                RecipeFixtures.incompressibleEntry(82, 2_050_000, (byte) 0));       // deflates to a frame that fits
        List<String> messages = new ArrayList<>();
        List<List<VanillaPipeline.Frame>> framesOf = new ArrayList<>();
        for (boolean encodeOnce : new boolean[] {true, false}) {
            RecipeBookSendInterceptor.encodeOnce = encodeOnce;
            try (VanillaPipeline pipe = new VanillaPipeline(256, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
                pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, false));
                pipe.channel.runPendingTasks();

                assertTrue(pipe.channel.isOpen());
                framesOf.add(pipe.readFrames());
                messages.addAll(log.messages(Level.ERROR));
            }
        }

        assertEquals(2, messages.size(), messages.toString());
        assertEquals(messages.get(0), messages.get(1));
        assertTrue(messages.get(0).contains("display id 81") && messages.get(0).contains("compresses to a 2,097,"), messages.get(0));
        assertEquals(List.of(80, 82), ids(framesOf.get(0)));
        assertEquals(framesOf.get(0).size(), framesOf.get(1).size());
        for (int i = 0; i < framesOf.get(0).size(); i++) {
            assertArrayEquals(framesOf.get(1).get(i).wire(), framesOf.get(0).get(i).wire(), "frame " + i);
        }
    }

    /** The debug digest and the sizes in the log describe what was sent, not what was measured. */
    @Test
    void digestDescribesTheEntriesThatWereSent() throws Exception {
        config(UndeliverableEntries.DROP);
        RecipeBookSendInterceptor.debugDigest = true;
        List<Entry> entries = List.of(RecipeFixtures.entry(90, 100, (byte) 0), RecipeFixtures.entry(91, 2_200_000, (byte) 0),
                RecipeFixtures.entry(92, 200, (byte) 1));
        List<Entry> sent = List.of(entries.get(0), entries.get(2));
        EntrySizer.Measurement expected = EntrySizer.measure(sent, RecipeFixtures.writer(), true, false);
        try (VanillaPipeline pipe = new VanillaPipeline(-1, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();

            List<String> digests = log.messages(Level.INFO).stream().filter(m -> m.contains(" digest ")).toList();
            assertEquals(1, digests.size(), log.entries().toString());
            assertEquals("[RecipeBookSplitter] digest player=embedded entries=2 replace=true bytes=" + expected.totalBytes()
                    + " chunks=1 sha256=" + expected.sha256() + " dropped=1", digests.get(0));
            assertEquals(expected.totalBytes(), pipe.readFrames().get(0).packetBytes());
        }
    }
}
