package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.testutil.FakeClientRecipeBook;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code bundleChunks}: the chunks of one split go out as one bundle, which the client handles in one tick. */
class BundleChunksIntegrationTest {
    private static final int BUDGET = 262_144;

    private VanillaPipeline pipe;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        RecipeBookSplitter.setConfig(TestConfigs.of(BUDGET, false, UndeliverableEntries.DROP, true));
        pipe = new VanillaPipeline(-1, false);
    }

    @AfterEach
    void tearDown() {
        pipe.close();
        RecipeBookSendInterceptor.debugDigest = false;
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    private static ClientboundRecipeBookAddPacket book(int entries, boolean replace) {
        return new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(entries), replace);
    }

    private static List<Packet<?>> subPackets(Object bundle) {
        List<Packet<?>> list = new ArrayList<>();
        ((ClientboundBundlePacket) bundle).subPackets().forEach(list::add);
        return list;
    }

    @Test
    void chunksOfOneSplitAreSentInOneBundle() throws Exception {
        ClientboundRecipeBookAddPacket original = book(400, true);
        AtomicInteger listenerCalls = new AtomicInteger();
        ChannelFutureListener listener = future -> {
            assertTrue(future.isSuccess());
            listenerCalls.incrementAndGet();
        };
        RecipeBookSendInterceptor.debugDigest = true;

        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(original, listener, true);
            pipe.channel.runPendingTasks();

            // One write of a new bundle object, written while it was the packet being re-sent, carrying the listener.
            assertEquals(1, pipe.recorder.messages.size());
            ClientboundBundlePacket bundle = assertInstanceOf(ClientboundBundlePacket.class, pipe.recorder.messages.get(0));
            assertSame(bundle, pipe.recorder.resent.get(0));
            assertTrue(pipe.recorder.withListener.get(0));
            assertEquals(1, listenerCalls.get());
            assertNull(RecipeBookSendInterceptor.currentlyResent());

            // On the wire: delimiter, chunks, delimiter.
            List<VanillaPipeline.Frame> frames = pipe.readFrames();
            assertInstanceOf(ClientboundBundleDelimiterPacket.class, frames.get(0).packet());
            assertInstanceOf(ClientboundBundleDelimiterPacket.class, frames.get(frames.size() - 1).packet());
            List<Packet<?>> chunks = subPackets(bundle);
            assertEquals(chunks.size() + 2, frames.size());
            assertTrue(chunks.size() > 1);
            for (int i = 0; i < chunks.size(); i++) {
                ClientboundRecipeBookAddPacket chunk = assertInstanceOf(ClientboundRecipeBookAddPacket.class, chunks.get(i));
                assertEquals(i == 0, chunk.replace(), "chunk " + i);
                assertTrue(frames.get(i + 1).packetBytes() <= BUDGET, "chunk of " + frames.get(i + 1).packetBytes() + " bytes");
            }

            // The client puts it back together as one bundle and ends with the same recipe book.
            BundlerInfo info = RecipeFixtures.protocol().bundlerInfo();
            BundlerInfo.Bundler bundler = info.startPacketBundling(frames.get(0).packet());
            Packet<?> done = null;
            for (VanillaPipeline.Frame frame : frames.subList(1, frames.size())) {
                done = bundler.addPacket(frame.packet());
            }
            ClientboundBundlePacket received = assertInstanceOf(ClientboundBundlePacket.class, done);
            FakeClientRecipeBook expected = new FakeClientRecipeBook();
            expected.apply(original, RecipeFixtures::render);
            FakeClientRecipeBook actual = new FakeClientRecipeBook();
            subPackets(received).forEach(p -> actual.apply((ClientboundRecipeBookAddPacket) p, RecipeFixtures::render));
            assertEquals(expected.known, actual.known);
            assertEquals(expected.highlight, actual.highlight);
            assertEquals(expected.toasts, actual.toasts);

            // The measured bytes were used for every chunk.
            assertEquals(chunks.size(), pipe.hadPrepared.stream().filter(Boolean::booleanValue).count());
            List<String> info1 = log.messages(Level.INFO);
            assertTrue(info1.stream().anyMatch(m -> m.contains("into " + chunks.size() + " chunks in one bundle (largest")
                    && m.endsWith("; measured bytes reused for " + chunks.size() + " of " + chunks.size() + " packets")), info1.toString());
            assertTrue(info1.stream().anyMatch(m -> m.startsWith("[RecipeBookSplitter] digest ") && m.endsWith(" bundle=true")), info1.toString());
        }
    }

    @Test
    void flushFalseIsDeferred() {
        pipe.connection.send(book(400, true), null, false);
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size());
        assertTrue(pipe.channel.outboundMessages().isEmpty(), "nothing is flushed yet");

        pipe.channel.flush();

        assertFalse(pipe.channel.outboundMessages().isEmpty());
    }

    @Test
    void unsplitPacketIsNotBundled() {
        ClientboundRecipeBookAddPacket small = book(5, true);

        pipe.connection.send(small);
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size());
        assertSame(small, pipe.recorder.messages.get(0));
        assertEquals(1, pipe.readFrames().size());
    }

    @Test
    void noUnbundlerSendsLooseChunks() throws Exception {
        try (TestConnection bare = TestConnection.create(new PacketEncoder<>(RecipeFixtures.protocol()));
             LogCapture log = new LogCapture("RecipeBookSplitter")) {
            bare.connection().send(book(400, true));
            bare.channel().runPendingTasks();

            List<Object> written = bare.recorder().messages;
            assertTrue(written.size() > 1);
            assertTrue(written.stream().allMatch(ClientboundRecipeBookAddPacket.class::isInstance), "no bundle for a pipeline that could not take it apart");
            assertTrue(log.messages(Level.DEBUG).stream().anyMatch(m -> m.contains("bundleChunks: sending " + written.size()
                    + " chunks loose (the connection has no bundle unpacker)")), log.entries().toString());
        }
    }

    @Test
    void sendAsBundleLimits() {
        SplitterConfig on = TestConfigs.of(BUDGET, false, UndeliverableEntries.DROP, true);
        SplitterConfig off = TestConfigs.of(BUDGET, false, UndeliverableEntries.DROP, false);
        EmbeddedChannel withUnbundler = new EmbeddedChannel();
        withUnbundler.pipeline().addLast(HandlerNames.UNBUNDLER, new PacketBundleUnpacker(RecipeFixtures.protocol().bundlerInfo()));
        EmbeddedChannel without = new EmbeddedChannel();
        try {
            assertFalse(RecipeBookSendInterceptor.sendAsBundle(on, withUnbundler, 1));
            assertTrue(RecipeBookSendInterceptor.sendAsBundle(on, withUnbundler, 2));
            assertTrue(RecipeBookSendInterceptor.sendAsBundle(on, withUnbundler, BundlerInfo.BUNDLE_SIZE_LIMIT));
            assertFalse(RecipeBookSendInterceptor.sendAsBundle(on, withUnbundler, BundlerInfo.BUNDLE_SIZE_LIMIT + 1));
            assertFalse(RecipeBookSendInterceptor.sendAsBundle(off, withUnbundler, 2));
            assertFalse(RecipeBookSendInterceptor.sendAsBundle(on, without, 2));
        } finally {
            withUnbundler.finishAndReleaseAll();
            without.finishAndReleaseAll();
        }
    }

    /** A packet that already was in a bundle is split in place; its chunks are not bundled a second time. */
    @Test
    void alreadyBundledPacketIsSplitInPlaceWithoutNesting() {
        Packet<ClientGamePacketListener> motion = new ClientboundSetEntityMotionPacket(1, new Vec3(0.25, 0.5, 0.75));
        pipe.connection.send(new ClientboundBundlePacket(List.of(motion, book(400, true))));
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size());
        List<Packet<?>> subs = subPackets(pipe.recorder.messages.get(0));
        assertTrue(subs.size() > 2);
        assertTrue(subs.stream().noneMatch(ClientboundBundlePacket.class::isInstance));
        assertSame(motion, subs.get(0));
        // Delimiter, motion, chunks, delimiter: one bundle on the wire.
        List<VanillaPipeline.Frame> frames = pipe.readFrames();
        assertEquals(subs.size() + 2, frames.size());
        assertEquals(2, frames.stream().filter(f -> f.packet() instanceof ClientboundBundleDelimiterPacket).count());
    }

    /** The bundle built for the chunks re-enters the hook and must pass it as it is, once. */
    @Test
    void recursionGuardCoversTheNewBundle() {
        pipe.connection.send(book(400, true));
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size(), "the new bundle was neither split again nor sent twice");
        assertSame(pipe.recorder.messages.get(0), pipe.recorder.resent.get(0));
        assertNull(RecipeBookSendInterceptor.currentlyResent());
    }
}
