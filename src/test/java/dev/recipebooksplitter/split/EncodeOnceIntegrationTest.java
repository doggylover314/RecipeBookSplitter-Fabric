package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.BenchDataset;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import dev.recipebooksplitter.util.Sizes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sends recipe book packets through a real {@code Connection} and a vanilla-ordered pipeline with the mod's mixins
 * applied, once encoding the chunks again (as 1.0.0 did) and once reusing the measured bytes, and compares what ends up
 * on the wire. The bytes have to be identical in every case, and every way the reuse can miss has to fall back to a
 * normal encode.
 */
class EncodeOnceIntegrationTest {
    private static final int SPLIT_BUDGET = 262_144;
    private static final int UNSPLIT_BUDGET = 1_500_000;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @AfterEach
    void reset() {
        RecipeBookSendInterceptor.encodeOnce = true;
        RecipeBookSendInterceptor.verifyEncodeOnce = false;
        RecipeBookSendInterceptor.LOGGED_NOT_KEPT.clear();
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    /** Everything one send produced. */
    private record Sent(List<VanillaPipeline.Frame> frames, List<Packet<?>> encoded, List<Boolean> hadPrepared,
                        List<Object> written, List<LogCapture.Entry> log) {
        List<String> messages(Level level) {
            return log.stream().filter(entry -> entry.level() == level).map(LogCapture.Entry::message).toList();
        }

        /** The INFO line of a split or the DEBUG line of an unsplit packet. */
        String reportLine() {
            List<String> lines = log.stream()
                    .filter(entry -> entry.level() == Level.INFO || entry.level() == Level.DEBUG)
                    .map(LogCapture.Entry::message)
                    .filter(message -> message.contains("recipe book packet") && !message.contains("digest"))
                    .toList();
            assertEquals(1, lines.size(), log.toString());
            return lines.get(0);
        }

        /** Whether the packet at {@code index} of {@link #encoded} was written by the connection (and is no probe). */
        boolean wasSent(int index) {
            return written.stream().anyMatch(packet -> packet == encoded.get(index));
        }
    }

    private static Sent send(ClientboundRecipeBookAddPacket packet, boolean encodeOnce, int budget, int compression,
                             @Nullable ChannelHandler extra) throws Exception {
        RecipeBookSendInterceptor.encodeOnce = encodeOnce;
        RecipeBookSplitter.setConfig(TestConfigs.budget(budget));
        try (VanillaPipeline pipe = new VanillaPipeline(compression, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            if (extra != null) {
                // Between the connection and the unbundler/encoder: it sees what the mod writes, before it is encoded.
                pipe.channel.pipeline().addBefore("recorder", "extra", extra);
            }
            pipe.connection.send(packet);
            pipe.channel.runPendingTasks();
            if (extra instanceof Deferring deferring) {
                deferring.release();
                pipe.channel.runPendingTasks();
            }
            assertNull(pipe.pendingException());
            assertNull(RecipeBookSendInterceptor.currentlyResent());
            return new Sent(pipe.readFrames(), List.copyOf(pipe.encoded), List.copyOf(pipe.hadPrepared),
                    List.copyOf(pipe.recorder.messages), List.copyOf(log.entries()));
        }
    }

    private static void assertSameWire(Sent expected, Sent actual, String label) {
        assertEquals(expected.frames().size(), actual.frames().size(), label + ": frames");
        for (int i = 0; i < expected.frames().size(); i++) {
            assertArrayEquals(expected.frames().get(i).wire(), actual.frames().get(i).wire(), label + ": frame " + i);
        }
    }

    /** The measured bytes were on offer exactly when a packet that was sent was encoded, never for a probe. */
    private static void assertOfferedForSentPackets(Sent sent, String label) {
        assertEquals(sent.encoded().size(), sent.hadPrepared().size(), label);
        for (int i = 0; i < sent.encoded().size(); i++) {
            assertEquals(sent.wasSent(i), sent.hadPrepared().get(i), label + ": encode " + i);
        }
    }

    private static String reusedNote(int packets) {
        return "; measured bytes reused for " + packets + " of " + packets + " packets";
    }

    @Test
    void wireBytesIdenticalWithAndWithoutReuse() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);
        for (boolean replace : new boolean[] {true, false}) {
            for (int budget : new int[] {SPLIT_BUDGET, UNSPLIT_BUDGET}) {
                for (int compression : new int[] {-1, 256}) {
                    String label = "replace=" + replace + " budget=" + budget + " compression=" + compression;
                    Sent normal = send(new ClientboundRecipeBookAddPacket(entries, replace), false, budget, compression, null);
                    Sent once = send(new ClientboundRecipeBookAddPacket(entries, replace), true, budget, compression, null);

                    int packets = once.frames().size();
                    assertEquals(budget == SPLIT_BUDGET, packets > 1, label + ": " + packets + " frames");
                    assertSameWire(normal, once, label);
                    assertOfferedForSentPackets(once, label);
                    assertEquals(packets, once.hadPrepared().stream().filter(Boolean::booleanValue).count(), label);
                    assertTrue(normal.hadPrepared().stream().noneMatch(Boolean::booleanValue), label);
                    assertTrue(once.reportLine().endsWith(reusedNote(packets)), once.reportLine());
                    assertFalse(normal.reportLine().contains("reused"), normal.reportLine());
                    // The price: one more probe, an empty packet with the other replace flag.
                    assertEquals(normal.encoded().size() + 1, once.encoded().size(), label);
                }
            }
        }
    }

    @Test
    void bigBookIdentical() throws Exception {
        List<Entry> entries = BenchDataset.book(300, 3_000, 150, 7L);
        assertEquals(450, entries.size());

        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, true), false, SPLIT_BUDGET, -1, null);
        Sent once = send(new ClientboundRecipeBookAddPacket(entries, true), true, SPLIT_BUDGET, -1, null);

        assertTrue(once.frames().size() > 3, "frames: " + once.frames().size());
        assertSameWire(normal, once, "big book");
        assertTrue(once.reportLine().endsWith(reusedNote(once.frames().size())), once.reportLine());
    }

    /** Entries bigger than one 256 KiB segment of kept bytes, so a packet's ranges start and end inside segments. */
    @Test
    void entriesCrossingSegmentBorders() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            entries.add(RecipeFixtures.incompressibleEntry(i, 300_000, (byte) (i % 4)));
        }

        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, false), false, UNSPLIT_BUDGET, -1, null);
        Sent once = send(new ClientboundRecipeBookAddPacket(entries, false), true, UNSPLIT_BUDGET, -1, null);

        assertTrue(once.frames().size() >= 2, "frames: " + once.frames().size());
        assertSameWire(normal, once, "segments");
        assertTrue(once.reportLine().endsWith(reusedNote(once.frames().size())), once.reportLine());
    }

    @Test
    void underBudgetOriginalIsWrittenFromMeasuredBytes() throws Exception {
        ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(50), true);

        Sent once = send(original, true, UNSPLIT_BUDGET, 256, null);

        assertEquals(1, once.written().size());
        assertSame(original, once.written().get(0), "sent as the same object");
        assertOfferedForSentPackets(once, "under budget");
        assertTrue(once.hadPrepared().stream().anyMatch(Boolean::booleanValue));
        assertTrue(once.reportLine().endsWith("sent unsplit" + reusedNote(1)), once.reportLine());
        assertSameWire(send(original, false, UNSPLIT_BUDGET, 256, null), once, "under budget");
    }

    /** A handler in front of the encoder that replaces every recipe packet object by an equal copy. */
    private static final class Copying extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ClientboundRecipeBookAddPacket packet) {
                msg = new ClientboundRecipeBookAddPacket(packet.entries(), packet.replace());
            }
            ctx.write(msg, promise);
        }
    }

    @Test
    void identityMissEncodesNormally() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);

        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, true), false, SPLIT_BUDGET, -1, new Copying());
        Sent once = send(new ClientboundRecipeBookAddPacket(entries, true), true, SPLIT_BUDGET, -1, new Copying());

        assertTrue(once.frames().size() > 1);
        assertSameWire(normal, once, "copied packets");
        assertTrue(once.hadPrepared().stream().noneMatch(Boolean::booleanValue), "a copy is not the packet that was measured");
        assertTrue(once.reportLine().endsWith("; measured bytes reused for 0 of " + once.frames().size() + " packets"), once.reportLine());
    }

    /** A handler in front of the encoder that holds every write back until {@link #release}. */
    private static final class Deferring extends ChannelOutboundHandlerAdapter {
        private final ArrayDeque<Object[]> held = new ArrayDeque<>();
        private ChannelHandlerContext ctx;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            this.ctx = ctx;
            held.add(new Object[] {msg, promise});
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            // keep holding; release() flushes
        }

        void release() {
            Object[] write;
            while ((write = held.poll()) != null) {
                ctx.write(write[0], (ChannelPromise) write[1]);
            }
            ctx.flush();
        }
    }

    @Test
    void deferredWriteEncodesNormally() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);

        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, true), false, SPLIT_BUDGET, -1, new Deferring());
        Sent once = send(new ClientboundRecipeBookAddPacket(entries, true), true, SPLIT_BUDGET, -1, new Deferring());

        assertTrue(once.frames().size() > 1);
        assertSameWire(normal, once, "deferred writes");
        assertTrue(once.hadPrepared().stream().noneMatch(Boolean::booleanValue), "encoded after the task, when the bytes are gone");
        assertTrue(once.reportLine().endsWith("; measured bytes reused for 0 of " + once.frames().size() + " packets"), once.reportLine());
        // Still in order: the first chunk replaces, the rest add.
        for (int i = 0; i < once.frames().size(); i++) {
            assertEquals(i == 0, ((ClientboundRecipeBookAddPacket) once.frames().get(i).packet()).replace(), "chunk " + i);
        }
    }

    /** Hands every recipe packet it sees to another connection's channel as well, from inside the write. */
    private static final class Mirroring extends ChannelOutboundHandlerAdapter {
        private final VanillaPipeline other;

        Mirroring(VanillaPipeline other) {
            this.other = other;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ClientboundRecipeBookAddPacket) {
                other.channel.writeAndFlush(msg);
            }
            ctx.write(msg, promise);
        }
    }

    @Test
    void sameObjectToAnotherConnectionEncodesWithItsOwnEncoder() throws Exception {
        RecipeBookSplitter.setConfig(TestConfigs.budget(SPLIT_BUDGET));
        try (VanillaPipeline a = new VanillaPipeline(-1, false); VanillaPipeline b = new VanillaPipeline(-1, false)) {
            a.channel.pipeline().addBefore("recorder", "mirror", new Mirroring(b));

            a.connection.send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true));
            a.channel.runPendingTasks();

            List<VanillaPipeline.Frame> framesA = a.readFrames();
            List<VanillaPipeline.Frame> framesB = b.readFrames();
            assertTrue(framesA.size() > 1);
            assertEquals(framesA.size(), framesB.size());
            for (int i = 0; i < framesA.size(); i++) {
                assertArrayEquals(framesA.get(i).wire(), framesB.get(i).wire(), "frame " + i);
            }
            // The bytes were measured with A's encoder and are only offered to that one.
            assertEquals(framesA.size(), a.hadPrepared.stream().filter(Boolean::booleanValue).count());
            assertTrue(b.hadPrepared.stream().noneMatch(Boolean::booleanValue));
        }
    }

    /** On the first recipe packet it sees, sends a book of its own to another connection from inside the write. */
    private static final class Nesting extends ChannelOutboundHandlerAdapter {
        private final VanillaPipeline other;
        private final ClientboundRecipeBookAddPacket nested;
        private boolean done;

        Nesting(VanillaPipeline other, ClientboundRecipeBookAddPacket nested) {
            this.other = other;
            this.nested = nested;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!done && msg instanceof ClientboundRecipeBookAddPacket) {
                done = true;
                other.connection.send(nested);
            }
            ctx.write(msg, promise);
        }
    }

    private record Pair(Sent a, Sent b) {}

    private static Pair sendNested(boolean encodeOnce) throws Exception {
        RecipeBookSendInterceptor.encodeOnce = encodeOnce;
        RecipeBookSplitter.setConfig(TestConfigs.budget(SPLIT_BUDGET));
        try (VanillaPipeline a = new VanillaPipeline(-1, false); VanillaPipeline b = new VanillaPipeline(256, false);
             LogCapture log = new LogCapture("RecipeBookSplitter")) {
            a.channel.pipeline().addBefore("recorder", "nesting",
                    new Nesting(b, new ClientboundRecipeBookAddPacket(BenchDataset.book(100, 3_000, 50, 3L), true)));

            a.connection.send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true));
            a.channel.runPendingTasks();
            b.channel.runPendingTasks();

            assertNull(RecipeBookSendInterceptor.currentlyResent());
            return new Pair(
                    new Sent(a.readFrames(), List.copyOf(a.encoded), List.copyOf(a.hadPrepared), List.copyOf(a.recorder.messages), List.copyOf(log.entries())),
                    new Sent(b.readFrames(), List.copyOf(b.encoded), List.copyOf(b.hadPrepared), List.copyOf(b.recorder.messages), List.copyOf(log.entries())));
        }
    }

    @Test
    void nestedSplitForAnotherConnection() throws Exception {
        Pair normal = sendNested(false);
        Pair once = sendNested(true);

        assertTrue(once.a().frames().size() > 1);
        assertTrue(once.b().frames().size() > 1);
        assertSameWire(normal.a(), once.a(), "outer");
        assertSameWire(normal.b(), once.b(), "nested");
        // Each split has its own bytes: the nested send must neither consume nor lose the outer ones.
        assertOfferedForSentPackets(once.a(), "outer");
        assertOfferedForSentPackets(once.b(), "nested");
        assertEquals(once.a().frames().size(), once.a().hadPrepared().stream().filter(Boolean::booleanValue).count());
        assertEquals(once.b().frames().size(), once.b().hadPrepared().stream().filter(Boolean::booleanValue).count());
    }

    /** Writes the first recipe packet it sees twice. */
    private static final class Duplicating extends ChannelOutboundHandlerAdapter {
        private boolean done;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!done && msg instanceof ClientboundRecipeBookAddPacket) {
                done = true;
                ctx.write(msg, ctx.newPromise());
            }
            ctx.write(msg, promise);
        }
    }

    @Test
    void singleUse() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);

        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, true), false, SPLIT_BUDGET, -1, new Duplicating());
        Sent once = send(new ClientboundRecipeBookAddPacket(entries, true), true, SPLIT_BUDGET, -1, new Duplicating());

        assertSameWire(normal, once, "written twice");
        assertEquals(once.written().size() + 1, once.frames().size(), "the first chunk went out twice");
        List<Integer> encodesOfFirstChunk = new ArrayList<>();
        for (int i = 0; i < once.encoded().size(); i++) {
            if (once.encoded().get(i) == once.written().get(0)) {
                encodesOfFirstChunk.add(i);
            }
        }
        assertEquals(2, encodesOfFirstChunk.size());
        assertTrue(once.hadPrepared().get(encodesOfFirstChunk.get(0)), "first write: the measured bytes");
        assertFalse(once.hadPrepared().get(encodesOfFirstChunk.get(1)), "second write of the same object: encoded normally");
    }

    @Test
    void verifyModeFindsNoMismatch() throws Exception {
        List<Entry> entries = BenchDataset.book(200, 3_000, 100, 9L);
        Sent normal = send(new ClientboundRecipeBookAddPacket(entries, false), false, SPLIT_BUDGET, -1, null);

        RecipeBookSendInterceptor.verifyEncodeOnce = true;
        Sent verified = send(new ClientboundRecipeBookAddPacket(entries, false), true, SPLIT_BUDGET, -1, null);

        assertTrue(verified.frames().size() > 1);
        assertSameWire(normal, verified, "verify mode");
        assertEquals(List.of(), verified.messages(Level.ERROR));
        int packets = verified.frames().size();
        assertTrue(verified.reportLine().endsWith("; " + packets + " of " + packets + " packets verified against a normal encode"), verified.reportLine());
    }

    @Test
    void killSwitch() throws Exception {
        List<Entry> entries = RecipeFixtures.entries(400);

        Sent off = send(new ClientboundRecipeBookAddPacket(entries, true), false, SPLIT_BUDGET, -1, null);

        assertTrue(off.hadPrepared().stream().noneMatch(Boolean::booleanValue));
        // As in 1.0.0: an empty probe, one probe per entry, and every chunk encoded again.
        assertEquals(1 + entries.size() + off.frames().size(), off.encoded().size());
        assertTrue(off.reportLine().endsWith(" ms)"), off.reportLine());
    }

    /** Throws when it sees the second recipe packet. */
    private static final class Throwing extends ChannelOutboundHandlerAdapter {
        private int seen;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ClientboundRecipeBookAddPacket && ++seen == 2) {
                throw new IllegalStateException("test: a handler fails");
            }
            ctx.write(msg, promise);
        }
    }

    @Test
    void noStateLeftBehind() throws Exception {
        RecipeBookSplitter.setConfig(TestConfigs.budget(SPLIT_BUDGET));
        // A split, one that is within the budget, a handler that fails on the second chunk, and a nested split.
        for (String scenario : new String[] {"split", "unsplit", "throwing", "nested"}) {
            try (VanillaPipeline pipe = new VanillaPipeline(-1, false); VanillaPipeline other = new VanillaPipeline(-1, false)) {
                switch (scenario) {
                    case "throwing" -> pipe.channel.pipeline().addBefore("recorder", "throwing", new Throwing());
                    case "nested" -> pipe.channel.pipeline().addBefore("recorder", "nesting",
                            new Nesting(other, new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), false)));
                    default -> { }
                }

                pipe.connection.send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(scenario.equals("unsplit") ? 20 : 400), true));
                pipe.channel.runPendingTasks();

                assertFalse(pipe.recorder.messages.isEmpty(), scenario);
                assertNull(RecipeBookSendInterceptor.currentlyResent(), scenario);
                Object encoder = pipe.channel.pipeline().get(HandlerNames.ENCODER);
                for (Object written : pipe.recorder.messages) {
                    // (A failing handler makes the connection write a disconnect packet as well.)
                    if (written instanceof ClientboundRecipeBookAddPacket recipeBookAdd) {
                        assertNull(RecipeBookSendInterceptor.preparedFor(encoder, recipeBookAdd), scenario);
                    }
                }
            }
        }
    }

    @Test
    void oversizedLoggerSeesReusedSize() throws Exception {
        List<Entry> entries = new ArrayList<>(RecipeFixtures.entries(3));
        entries.add(RecipeFixtures.entry(100, 5_000_000, (byte) 0));
        entries.addAll(RecipeFixtures.entries(3));
        RecipeBookSendInterceptor.encodeOnce = true;
        RecipeBookSplitter.setConfig(TestConfigs.budget(UNSPLIT_BUDGET, true));

        // Compression on: 5 MB of zeros fits a frame once deflated.
        try (VanillaPipeline pipe = new VanillaPipeline(256, false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            pipe.channel.runPendingTasks();
            List<VanillaPipeline.Frame> frames = pipe.readFrames();

            assertEquals(3, frames.size());
            long bigPacketBytes = frames.get(1).packetBytes();
            assertTrue(bigPacketBytes > OversizedPacketLogger.THRESHOLD_BYTES);
            // The chunk is reported once with its real size; the probe that was just as big is not.
            List<String> oversized = log.messages(Level.WARN).stream().filter(message -> message.contains("oversized clientbound packet")).toList();
            assertEquals(1, oversized.size(), log.entries().toString());
            assertTrue(oversized.get(0).contains("(" + Sizes.bytes(bigPacketBytes) + " bytes)"), oversized.get(0));
            assertEquals(3, pipe.hadPrepared.stream().filter(Boolean::booleanValue).count(), "all three chunks come from measured bytes");
        }
    }

    /** Stands in for another mod's hook at the head or at the end of {@code PacketEncoder.encode}: one byte of its own. */
    private static PacketEncoder<ClientGamePacketListener> encoderWithHook(boolean beforeCodec) {
        return new PacketEncoder<>(RecipeFixtures.protocol()) {
            @Override
            protected void encode(ChannelHandlerContext ctx, Packet<ClientGamePacketListener> packet, ByteBuf out) throws Exception {
                if (beforeCodec) {
                    out.writeByte(0x7F);
                }
                super.encode(ctx, packet, out);
                if (!beforeCodec) {
                    out.writeByte(0x55);
                }
            }
        };
    }

    private record Raw(List<byte[]> frames, List<LogCapture.Entry> log) {}

    /** Sends two books of {@code entries} entries through an encoder with that hook (no framing handlers). */
    private static Raw sendThroughHookedEncoder(boolean beforeCodec, int entries, boolean encodeOnce, boolean verify) throws Exception {
        RecipeBookSendInterceptor.encodeOnce = encodeOnce;
        RecipeBookSendInterceptor.verifyEncodeOnce = verify;
        RecipeBookSendInterceptor.LOGGED_NOT_KEPT.clear();
        RecipeBookSplitter.setConfig(TestConfigs.budget(SPLIT_BUDGET));
        try (TestConnection test = TestConnection.create(encoderWithHook(beforeCodec)); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            test.connection().send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(entries), true));
            test.connection().send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(entries), false));
            test.channel().runPendingTasks();
            List<byte[]> frames = new ArrayList<>();
            ByteBuf frame;
            while ((frame = test.channel().readOutbound()) != null) {
                frames.add(ByteBufUtil.getBytes(frame));
                frame.release();
            }
            return new Raw(frames, List.copyOf(log.entries()));
        }
    }

    /**
     * Review finding: the kept header was taken from the whole {@code PacketEncoder.encode} output, so a byte that
     * another mod's hook writes before the codec call was in the header and written a second time with every chunk.
     * Bytes written around the codec call now keep nothing: the packets are encoded normally, with one INFO line.
     */
    @Test
    void bytesWrittenAroundTheCodecCallAreNotWrittenTwice() throws Exception {
        for (boolean beforeCodec : new boolean[] {true, false}) {
            for (int entries : new int[] {1, 5, 200}) {
                String label = (beforeCodec ? "hook before" : "hook after") + " the codec call, " + entries + " entries";
                Raw normal = sendThroughHookedEncoder(beforeCodec, entries, false, false);
                Raw once = sendThroughHookedEncoder(beforeCodec, entries, true, false);
                Raw verified = sendThroughHookedEncoder(beforeCodec, entries, true, true);

                assertTrue(normal.frames().size() >= 2, label);
                for (Raw raw : new Raw[] {once, verified}) {
                    assertEquals(normal.frames().size(), raw.frames().size(), label);
                    for (int i = 0; i < normal.frames().size(); i++) {
                        assertArrayEquals(normal.frames().get(i), raw.frames().get(i), label + ": frame " + i);
                    }
                    List<String> errors = raw.log().stream().filter(entry -> entry.level() == Level.ERROR).map(LogCapture.Entry::message).toList();
                    assertEquals(List.of(), errors, label);
                    // Once, although two books were sent.
                    List<String> notUsed = raw.log().stream().filter(entry -> entry.level() == Level.INFO)
                            .map(LogCapture.Entry::message).filter(message -> message.contains("encode once is not used")).toList();
                    assertEquals(1, notUsed.size(), label + ": " + raw.log());
                    assertTrue(notUsed.get(0).contains("something besides the codec call writes into the packet buffer"), notUsed.get(0));
                    assertTrue(raw.log().stream().noneMatch(entry -> entry.message().contains("reused for") && !entry.message().contains("reused for 0 of")), label);
                }
                // The hook's byte is there (equal frames above mean: exactly as often as without encode once).
                byte[] first = once.frames().get(0);
                assertEquals(beforeCodec ? 0x7F : 0x55, beforeCodec ? first[0] : first[first.length - 1], label);
            }
        }
    }

    /** Measured bytes are the ones the hook around the codec call saw, so the normal case still reuses everything. */
    @Test
    void withoutOtherHooksEverythingIsStillReused() throws Exception {
        RecipeBookSendInterceptor.LOGGED_NOT_KEPT.clear();
        Sent once = send(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true), true, SPLIT_BUDGET, -1, null);

        assertTrue(once.frames().size() > 1);
        assertTrue(once.reportLine().endsWith(reusedNote(once.frames().size())), once.reportLine());
        assertEquals(List.of(), once.log().stream().filter(entry -> entry.message().contains("encode once is not used")).toList());
    }

    @Test
    void notKeptReasonIsLoggedOncePerReason() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            RecipeBookSendInterceptor.LOGGED_NOT_KEPT.clear();
            for (int i = 0; i < 3; i++) {
                RecipeBookSendInterceptor.logNotKept(EntrySizer.NotKept.NO_CODEC_HOOK);
            }
            RecipeBookSendInterceptor.logNotKept(EntrySizer.NotKept.LAYOUT);
            RecipeBookSendInterceptor.logNotKept(EntrySizer.NotKept.LAYOUT);
            RecipeBookSendInterceptor.logNotKept(null);

            List<String> infos = log.messages(Level.INFO);
            assertEquals(2, infos.size(), infos.toString());
            assertTrue(infos.get(0).startsWith("[RecipeBookSplitter] encode once is not used: the hook around the codec call in PacketEncoder.encode did not run while measuring"), infos.get(0));
            assertTrue(infos.get(1).startsWith("[RecipeBookSplitter] encode once is not used: a measured recipe book packet does not have the layout"), infos.get(1));
            assertTrue(infos.get(0).endsWith("as in 1.0.0 (logged once)"), infos.get(0));
        }
    }

    /**
     * Review finding: with the default {@code undeliverableEntries = drop} every one-entry packet (each recipe unlock) is
     * measured, and keeping its bytes allocated a 256 KiB segment for about 100 bytes: 271 KB and 80-97 us per unlock
     * against 2 KB and 2-6 us for vanilla's own send.
     */
    @Test
    void oneEntryUnlockPacketsDoNotAllocateASegment() throws Exception {
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(), "no per-thread allocation counter on this JVM");
        threads.setThreadAllocatedMemoryEnabled(true);
        RecipeBookSplitter.setConfig(TestConfigs.budget(UNSPLIT_BUDGET));
        assertEquals(SplitterConfig.UndeliverableEntries.DROP, RecipeBookSplitter.config().undeliverableEntries());

        try (TestConnection test = TestConnection.create(new PacketEncoder<ClientGamePacketListener>(RecipeFixtures.protocol()))) {
            ClientboundRecipeBookAddPacket unlock = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(1), false);
            try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
                test.connection().send(unlock);
                test.channel().runPendingTasks();
                assertTrue(log.messages(Level.DEBUG).stream().anyMatch(message -> message.endsWith("sent unsplit" + reusedNote(1))), log.entries().toString());
            }
            for (int i = 0; i < 400; i++) {
                sendAndDrain(test, unlock);
            }

            int rounds = 200;
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < rounds; i++) {
                sendAndDrain(test, unlock);
            }
            long perPacket = (threads.getCurrentThreadAllocatedBytes() - before) / rounds;

            // About 14 KB (a few encodes, their buffers and the 4 KiB first segment); 271 KB with a 256 KiB segment.
            assertTrue(perPacket < 100 * 1024, perPacket + " bytes allocated per one-entry packet");
        }
    }

    private static void sendAndDrain(TestConnection test, ClientboundRecipeBookAddPacket packet) {
        test.connection().send(packet);
        test.channel().runPendingTasks();
        ByteBuf frame;
        while ((frame = test.channel().readOutbound()) != null) {
            frame.release();
        }
        test.recorder().messages.clear();
    }
}
