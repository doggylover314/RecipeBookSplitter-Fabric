package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.FakeClientRecipeBook;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import io.netty.channel.ChannelFutureListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bundles through a real {@code Connection}, {@code PacketBundleUnpacker} and {@code PacketEncoder} (vanilla's handler
 * names and order), with the mod's mixins applied; the frames are then put back together like the client's
 * {@code PacketBundlePacker} does.
 */
class BundleSplitIntegrationTest {
    private static final int BUDGET = 262_144;

    private VanillaPipeline pipe;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        RecipeBookSplitter.setConfig(TestConfigs.budget(BUDGET));
        pipe = new VanillaPipeline(-1, false);
    }

    @AfterEach
    void tearDown() {
        pipe.close();
        RecipeBookSendInterceptor.encodeOnce = true;
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    private static Packet<ClientGamePacketListener> motion(int id) {
        return new ClientboundSetEntityMotionPacket(id, new Vec3(0.25, 0.5, 0.75));
    }

    private static Packet<ClientGamePacketListener> power(int id) {
        return new ClientboundProjectilePowerPacket(id, 0.125);
    }

    /** What the client's PacketBundlePacker rebuilds from the frames: the sub-packets of each bundle, plus loose packets. */
    private static List<Object> reassemble(List<VanillaPipeline.Frame> frames) {
        BundlerInfo info = RecipeFixtures.protocol().bundlerInfo();
        List<Object> out = new ArrayList<>();
        BundlerInfo.Bundler bundler = null;
        for (VanillaPipeline.Frame frame : frames) {
            if (bundler != null) {
                Packet<?> done = bundler.addPacket(frame.packet());
                if (done != null) {
                    out.add(done);
                    bundler = null;
                }
            } else {
                bundler = info.startPacketBundling(frame.packet());
                if (bundler == null) {
                    out.add(frame.packet());
                }
            }
        }
        assertNull(bundler, "unterminated bundle");
        return out;
    }

    private static List<Packet<?>> subPackets(Object bundle) {
        List<Packet<?>> list = new ArrayList<>();
        ((ClientboundBundlePacket) bundle).subPackets().forEach(list::add);
        return list;
    }

    @Test
    void recipePacketInBundleIsSplitInPlace() {
        List<ClientboundRecipeBookAddPacket.Entry> entries = RecipeFixtures.entries(400);
        ClientboundRecipeBookAddPacket book = new ClientboundRecipeBookAddPacket(entries, true);
        ClientboundBundlePacket original = new ClientboundBundlePacket(List.of(motion(1), book, power(2)));
        AtomicInteger listenerCalls = new AtomicInteger();
        ChannelFutureListener listener = future -> {
            assertTrue(future.isSuccess());
            listenerCalls.incrementAndGet();
        };

        pipe.connection.send(original, listener, true);
        pipe.channel.runPendingTasks();

        // One write: a new bundle object, written while it was the packet being re-sent, carrying the listener.
        assertEquals(1, pipe.recorder.messages.size());
        ClientboundBundlePacket rebuilt = assertInstanceOf(ClientboundBundlePacket.class, pipe.recorder.messages.get(0));
        assertNotSame(original, rebuilt);
        assertSame(rebuilt, pipe.recorder.resent.get(0));
        assertTrue(pipe.recorder.withListener.get(0));
        assertNull(RecipeBookSendInterceptor.currentlyResent());

        // On the wire: delimiter, motion, chunks, power, delimiter; every chunk within budget.
        List<VanillaPipeline.Frame> frames = pipe.readFrames();
        assertInstanceOf(ClientboundBundleDelimiterPacket.class, frames.get(0).packet());
        assertInstanceOf(ClientboundSetEntityMotionPacket.class, frames.get(1).packet());
        assertInstanceOf(ClientboundProjectilePowerPacket.class, frames.get(frames.size() - 2).packet());
        assertInstanceOf(ClientboundBundleDelimiterPacket.class, frames.get(frames.size() - 1).packet());
        List<ClientboundRecipeBookAddPacket> chunks = frames.subList(2, frames.size() - 2).stream()
                .map(f -> {
                    assertTrue(f.packetBytes() <= BUDGET, "chunk of " + f.packetBytes() + " bytes");
                    return (ClientboundRecipeBookAddPacket) f.packet();
                }).toList();
        assertTrue(chunks.size() > 1, "split into " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i == 0, chunks.get(i).replace(), "chunk " + i);
        }

        // The client puts it back together as one bundle with the same order, and ends with the same recipe book.
        List<Object> received = reassemble(frames);
        assertEquals(1, received.size());
        List<Packet<?>> subs = subPackets(received.get(0));
        assertEquals(chunks.size() + 2, subs.size());
        FakeClientRecipeBook expected = new FakeClientRecipeBook();
        expected.apply(book, RecipeFixtures::render);
        FakeClientRecipeBook actual = new FakeClientRecipeBook();
        subs.stream().filter(ClientboundRecipeBookAddPacket.class::isInstance)
                .forEach(p -> actual.apply((ClientboundRecipeBookAddPacket) p, RecipeFixtures::render));
        assertEquals(expected.known, actual.known);
        assertEquals(expected.highlight, actual.highlight);
        assertEquals(expected.toasts, actual.toasts);

        // The encoder never saw a bundle object: only probes, delimiters and single sub-packets.
        assertTrue(pipe.encoded.stream().noneMatch(ClientboundBundlePacket.class::isInstance));
        assertEquals(1, listenerCalls.get());
    }

    @Test
    void bundleWithoutRecipePacketIsLeftToVanilla() {
        ClientboundBundlePacket original = new ClientboundBundlePacket(List.of(motion(1), power(1)));

        pipe.connection.send(original);
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size());
        assertSame(original, pipe.recorder.messages.get(0));
        assertNull(pipe.recorder.resent.get(0), "vanilla path: not re-sent by us");
        assertEquals(4, pipe.readFrames().size());
        assertEquals(4, pipe.encoded.size(), "no probes");
    }

    @Test
    void smallRecipePacketInBundleKeepsTheOriginalBundle() {
        ClientboundBundlePacket original = new ClientboundBundlePacket(
                List.of(motion(1), new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(3), false)));

        pipe.connection.send(original);
        pipe.channel.runPendingTasks();

        assertEquals(1, pipe.recorder.messages.size());
        assertSame(original, pipe.recorder.messages.get(0));
        assertSame(original, pipe.recorder.resent.get(0), "measured, then re-sent unchanged");
        assertEquals(4, pipe.readFrames().size());
    }

    @Test
    void twoRecipePacketsInOneBundleKeepTheirOrder() {
        ClientboundRecipeBookAddPacket first = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(200), true);
        ClientboundRecipeBookAddPacket second = new ClientboundRecipeBookAddPacket(
                RecipeFixtures.entries(400).subList(200, 400), false);
        ClientboundBundlePacket original = new ClientboundBundlePacket(List.of(first, motion(7), second));

        pipe.connection.send(original);
        pipe.channel.runPendingTasks();

        List<Packet<?>> subs = subPackets(reassemble(pipe.readFrames()).get(0));
        int motionAt = -1;
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i) instanceof ClientboundSetEntityMotionPacket) {
                motionAt = i;
            }
        }
        assertTrue(motionAt > 1, "first book split before the motion packet");
        assertTrue(subs.size() - motionAt - 1 > 1, "second book split after it");
        List<Integer> ids = subs.stream().filter(ClientboundRecipeBookAddPacket.class::isInstance)
                .flatMap(p -> ((ClientboundRecipeBookAddPacket) p).entries().stream())
                .map(e -> e.contents().id().index()).toList();
        assertEquals(IntStream.range(0, 400).boxed().toList(), ids);
        assertTrue(((ClientboundRecipeBookAddPacket) subs.get(0)).replace());
        assertFalse(((ClientboundRecipeBookAddPacket) subs.get(motionAt + 1)).replace());
    }

    @Test
    void bundleThatWouldExceedTheClientLimitIsSentUnsplit() {
        List<Packet<? super ClientGamePacketListener>> subs = new ArrayList<>();
        for (int i = 0; i < BundlerInfo.BUNDLE_SIZE_LIMIT - 2; i++) {
            subs.add(motion(i));
        }
        subs.add(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), false));
        ClientboundBundlePacket original = new ClientboundBundlePacket(subs);

        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(original);
            pipe.channel.runPendingTasks();

            assertEquals(1, pipe.recorder.messages.size());
            assertSame(original, pipe.recorder.messages.get(0));
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains("more than the 4,096 a client accepts in one bundle"), errors.get(0));
        }
        assertEquals(BundlerInfo.BUNDLE_SIZE_LIMIT + 1, pipe.readFrames().size());
    }

    @Test
    void bundleForAnotherPlayerIsSplitWhileOursIsWritten() throws Exception {
        try (VanillaPipeline other = new VanillaPipeline(-1, false)) {
            ChannelFutureListener sendToOther = future -> other.connection.send(
                    new ClientboundBundlePacket(List.of(motion(1), new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true))));

            pipe.connection.send(new ClientboundBundlePacket(
                    List.of(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true))), sendToOther, true);
            pipe.channel.runPendingTasks();
            other.channel.runPendingTasks();

            ClientboundBundlePacket written = assertInstanceOf(ClientboundBundlePacket.class, other.recorder.messages.get(0));
            assertTrue(subPackets(written).size() > 2, "the other player's bundle was split too");
        }
    }

    /**
     * The embedded event loop runs everything inline, so this uses a real event loop thread: a bundle sent from another
     * thread is rescheduled by the hook, and must still be written in order between its neighbours.
     */
    @Test
    void bundleSentFromAnotherThreadIsSplitOnTheEventLoop() throws Exception {
        try (LocalConnection local = new LocalConnection(true)) {
            ClientboundBundlePacket before = new ClientboundBundlePacket(List.of(motion(1), power(1)));
            ClientboundBundlePacket original = new ClientboundBundlePacket(List.of(motion(2), new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true)));
            ClientboundBundlePacket after = new ClientboundBundlePacket(List.of(motion(3), power(3)));
            local.connection.send(before);
            local.connection.send(original);
            local.connection.send(after);

            LocalConnection.awaitUntil(() -> local.recorder.messages.size() == 3, "the bundles were never written");

            assertSame(before, local.recorder.messages.get(0));
            ClientboundBundlePacket rebuilt = assertInstanceOf(ClientboundBundlePacket.class, local.recorder.messages.get(1));
            assertNotSame(original, rebuilt);
            assertSame(after, local.recorder.messages.get(2));
            assertTrue(subPackets(rebuilt).size() > 2, "split into " + subPackets(rebuilt).size());
            assertNull(local.recorder.resent.get(0), "the first bundle stays on the vanilla path");
            assertSame(rebuilt, local.recorder.resent.get(1));
            assertNull(local.recorder.resent.get(2));
        }
    }

    @Test
    void measurementFailureSendsTheOriginalBundle() throws Exception {
        try (TestConnection wrongPhase = TestConnection.create(new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
             LogCapture log = new LogCapture("RecipeBookSplitter")) {
            ClientboundBundlePacket original = new ClientboundBundlePacket(
                    List.of(new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(10), true)));

            wrongPhase.connection().send(original);
            wrongPhase.channel().runPendingTasks();

            List<Object> bundles = wrongPhase.recorder().messages.stream().filter(ClientboundBundlePacket.class::isInstance).toList();
            assertEquals(1, bundles.size());
            assertSame(original, bundles.get(0));
            assertTrue(log.messages(Level.ERROR).stream().anyMatch(m -> m.contains("in a bundle; sending the bundle unsplit")));
        }
    }

    /** Every recipe packet the bundle's sub-packets contain, in order. */
    private static List<ClientboundRecipeBookAddPacket> books(Object bundle) {
        return subPackets(bundle).stream().filter(ClientboundRecipeBookAddPacket.class::isInstance)
                .map(ClientboundRecipeBookAddPacket.class::cast).toList();
    }

    @Test
    void bundleSubPacketsReuseMeasuredBytes() throws Exception {
        List<ClientboundRecipeBookAddPacket.Entry> entries = RecipeFixtures.entries(400);
        pipe.connection.send(new ClientboundBundlePacket(List.of(motion(1), new ClientboundRecipeBookAddPacket(entries, true), power(2))));
        pipe.channel.runPendingTasks();
        ClientboundBundlePacket rebuilt = assertInstanceOf(ClientboundBundlePacket.class, pipe.recorder.messages.get(0));
        List<ClientboundRecipeBookAddPacket> chunks = books(rebuilt);
        assertTrue(chunks.size() > 1);

        // The measured bytes were on offer for each chunk when it was encoded, and for nothing else (probes included).
        assertEquals(pipe.encoded.size(), pipe.hadPrepared.size());
        for (int i = 0; i < pipe.encoded.size(); i++) {
            Packet<?> encoded = pipe.encoded.get(i);
            boolean isChunk = chunks.stream().anyMatch(chunk -> chunk == encoded);
            assertEquals(isChunk, pipe.hadPrepared.get(i), "encode " + i + " of " + encoded.type());
        }

        // And the wire bytes are those of a run that encodes the chunks again.
        List<VanillaPipeline.Frame> once = pipe.readFrames();
        RecipeBookSendInterceptor.encodeOnce = false;
        try (VanillaPipeline again = new VanillaPipeline(-1, false)) {
            again.connection.send(new ClientboundBundlePacket(List.of(motion(1), new ClientboundRecipeBookAddPacket(entries, true), power(2))));
            again.channel.runPendingTasks();
            List<VanillaPipeline.Frame> normal = again.readFrames();

            assertTrue(again.hadPrepared.stream().noneMatch(Boolean::booleanValue));
            assertEquals(normal.size(), once.size());
            for (int i = 0; i < once.size(); i++) {
                assertArrayEquals(normal.get(i).wire(), once.get(i).wire(), "frame " + i);
            }
        }
    }

    @Test
    void unsplitSubPacketInBundleIsWrittenFromMeasuredBytes() throws Exception {
        ClientboundRecipeBookAddPacket book = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(20), true);
        ClientboundBundlePacket original = new ClientboundBundlePacket(List.of(motion(1), book));

        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            pipe.connection.send(original);
            pipe.channel.runPendingTasks();

            assertSame(original, pipe.recorder.messages.get(0), "nothing changed: the original bundle");
            int at = IntStream.range(0, pipe.encoded.size()).filter(i -> pipe.encoded.get(i) == book).findFirst().orElse(-1);
            assertTrue(at >= 0);
            assertTrue(pipe.hadPrepared.get(at), "the book is written from the bytes measured for it");
            List<String> debug = log.messages(Level.DEBUG);
            assertEquals(1, debug.size(), debug.toString());
            assertTrue(debug.get(0).contains("recipe book packet in a bundle ") && debug.get(0).endsWith("sent unsplit; measured bytes reused for 1 of 1 packets"), debug.get(0));
        }
    }

    /**
     * Fabric API (on the test classpath, as on most servers) copies every bundle's sub-packets into an ArrayList when
     * the bundle is built (fabric-networking-api-v1 BundlePacketMixin) and flattens nested bundles, so even a bundle
     * built from a one-shot Iterable can be looked into. Without Fabric API, {@code containsCandidate} only looks into a
     * Collection.
     */
    @Test
    void fabricApiCopiesBundleContentsIntoAList() {
        ClientboundRecipeBookAddPacket book = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(3), true);
        AtomicInteger iterations = new AtomicInteger();
        Iterable<Packet<? super ClientGamePacketListener>> once = () -> {
            iterations.incrementAndGet();
            return List.<Packet<? super ClientGamePacketListener>>of(book).iterator();
        };
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(once);
        assertEquals(1, iterations.get(), "iterated once, by Fabric API, while constructing");
        assertInstanceOf(ArrayList.class, bundle.subPackets());
        ClientboundBundlePacket nested = new ClientboundBundlePacket(List.of(motion(1), bundle));
        assertEquals(2, subPackets(nested).size(), "nested bundle flattened");
        assertTrue(RecipeBookSendInterceptor.containsCandidate(nested, 1));
    }
}
