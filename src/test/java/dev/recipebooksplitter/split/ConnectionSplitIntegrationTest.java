package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pushes packets through a real {@link Connection} and {@link PacketEncoder} with the mod's mixins applied, so the
 * hook, the sizing through the real encoder and the re-send through {@code Connection.send} run exactly as on a server.
 */
class ConnectionSplitIntegrationTest {
    private static final int BUDGET = 65_536;

    private TestConnection test;
    private Connection connection;
    private EmbeddedChannel channel;
    private WriteRecorder recorder;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        RecipeBookSplitter.setConfig(new SplitterConfig(BUDGET, true, true));
        test = TestConnection.create(new PacketEncoder<>(RecipeFixtures.protocol()));
        connection = test.connection();
        channel = test.channel();
        recorder = test.recorder();
        assertTrue(connection.isConnected());
    }

    @AfterEach
    void tearDown() {
        test.close();
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    private List<Packet<?>> readFrames(EmbeddedChannel from) {
        List<Packet<?>> packets = new ArrayList<>();
        ByteBuf frame;
        while ((frame = from.readOutbound()) != null) {
            assertTrue(frame.readableBytes() <= BUDGET, "frame of " + frame.readableBytes() + " bytes");
            packets.add(RecipeFixtures.decode(frame));
        }
        return packets;
    }

    private static List<ClientboundRecipeBookAddPacket> addPackets(List<Packet<?>> packets) {
        return packets.stream().map(ClientboundRecipeBookAddPacket.class::cast).toList();
    }

    private static void assertSameEntries(List<Entry> expected, List<ClientboundRecipeBookAddPacket> chunks) {
        List<Entry> actual = chunks.stream().flatMap(chunk -> chunk.entries().stream()).toList();
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).contents().id(), actual.get(i).contents().id(), "entry " + i);
            assertEquals(expected.get(i).flags(), actual.get(i).flags(), "entry " + i);
            assertEquals(RecipeFixtures.render(expected.get(i).contents()), RecipeFixtures.render(actual.get(i).contents()), "entry " + i);
        }
    }

    @Test
    void splitsReplaceTrue() {
        List<Entry> entries = RecipeFixtures.entries(400);
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<ChannelFuture> completed = new AtomicReference<>();
        ChannelFutureListener listener = future -> {
            listenerCalls.incrementAndGet();
            completed.set(future);
        };

        connection.send(new ClientboundRecipeBookAddPacket(entries, true), listener, true);
        channel.runPendingTasks();

        List<ClientboundRecipeBookAddPacket> chunks = addPackets(readFrames(channel));
        assertTrue(chunks.size() > 1);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i == 0, chunks.get(i).replace(), "chunk " + i);
        }
        assertSameEntries(entries, chunks);

        assertEquals(1, listenerCalls.get());
        assertTrue(completed.get().isSuccess());

        // The connection only ever saw our chunk objects, each written while it was the packet being re-sent.
        assertEquals(chunks.size(), recorder.messages.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(recorder.messages.get(i) instanceof ClientboundRecipeBookAddPacket);
            assertSame(recorder.messages.get(i), recorder.resent.get(i), "write " + i);
        }
        assertNull(RecipeBookSendInterceptor.currentlyResent());

        // The listener belongs to the last chunk only, so it completes when the whole book has been written.
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertFalse(recorder.withListener.get(i), "chunk " + i + " must not carry the listener");
        }
        assertTrue(recorder.withListener.get(chunks.size() - 1), "the last chunk carries the listener");
    }

    @Test
    void splitsReplaceFalse() {
        List<Entry> entries = RecipeFixtures.entries(400);

        connection.send(new ClientboundRecipeBookAddPacket(entries, false));
        channel.runPendingTasks();

        List<ClientboundRecipeBookAddPacket> chunks = addPackets(readFrames(channel));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().noneMatch(ClientboundRecipeBookAddPacket::replace));
        assertTrue(recorder.withListener.stream().noneMatch(Boolean::booleanValue), "no listener, so no chunk has a promise of its own");
        assertSameEntries(entries, chunks);
    }

    @Test
    void flushFalseIsDeferred() {
        List<Entry> entries = RecipeFixtures.entries(400);

        connection.send(new ClientboundRecipeBookAddPacket(entries, true), null, false);
        channel.runPendingTasks();

        int written = recorder.messages.size();
        assertTrue(written > 1);
        assertTrue(channel.outboundMessages().isEmpty(), "nothing is flushed yet");

        channel.flush();

        assertEquals(written, channel.outboundMessages().size());
        assertSameEntries(entries, addPackets(readFrames(channel)));
    }

    @Test
    void underBudgetSendsOriginalObject() {
        ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(
                List.of(RecipeFixtures.entry(1, 100, (byte) 1), RecipeFixtures.entry(2, 100, (byte) 2), RecipeFixtures.entry(3, 100, (byte) 3)), true);

        connection.send(original);
        channel.runPendingTasks();

        assertEquals(1, recorder.messages.size());
        assertSame(original, recorder.messages.get(0));
        assertSame(original, recorder.resent.get(0), "the unsplit original goes through the same guard");
        assertEquals(1, channel.outboundMessages().size());
    }

    @Test
    void smallAndNonRecipePacketsPassThrough() {
        List<Packet<?>> packets = List.of(
                new ClientboundRecipeBookAddPacket(List.of(), true),
                new ClientboundRecipeBookAddPacket(List.of(RecipeFixtures.entry(1, 3 * BUDGET, (byte) 0)), false),
                new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(1))));

        for (Packet<?> packet : packets) {
            recorder.messages.clear();
            recorder.resent.clear();

            connection.send(packet);
            channel.runPendingTasks();

            assertEquals(1, recorder.messages.size(), packet.type().toString());
            assertSame(packet, recorder.messages.get(0));
            assertNull(recorder.resent.get(0), "the vanilla path does not go through our chunk writer");
        }
    }

    @Test
    void noEncoderFallsBack() throws Exception {
        try (TestConnection bare = TestConnection.create(null)) {
            ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true);

            bare.connection().send(original);
            bare.channel().runPendingTasks();

            assertEquals(1, bare.recorder().messages.size());
            assertSame(original, bare.recorder().messages.get(0));
            assertSame(original, bare.channel().readOutbound());
            assertNull(bare.channel().readOutbound());
        }
    }

    @Test
    void measurementFailureFallsBack() throws Exception {
        // A configuration-phase encoder cannot encode play packets, so sizing fails with "Sending unknown packet".
        try (TestConnection wrongPhase = TestConnection.create(new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND))) {
            ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(10), true);

            wrongPhase.connection().send(original);
            wrongPhase.channel().runPendingTasks();

            // The original is sent once, unsplit. (It then fails in the encoder as it would without the mod.)
            List<Object> sentAddPackets = wrongPhase.recorder().messages.stream().filter(ClientboundRecipeBookAddPacket.class::isInstance).toList();
            assertEquals(1, sentAddPackets.size());
            assertSame(original, sentAddPackets.get(0));
        }
    }

    /**
     * The embedded event loop runs everything inline, so this uses a real event loop thread: a recipe book packet
     * sent from another thread is rescheduled by the hook, and must still be written in order between its neighbours.
     */
    @Test
    void chunksStayContiguousWhenSentFromAnotherThread() throws Exception {
        try (LocalConnection local = new LocalConnection(true)) {
            List<Entry> entries = RecipeFixtures.entries(400);
            ClientboundRecipeBookRemovePacket before = new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(1)));
            ClientboundRecipeBookRemovePacket after = new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(2)));
            local.connection.send(before);
            local.connection.send(new ClientboundRecipeBookAddPacket(entries, true));
            local.connection.send(after);

            LocalConnection.awaitUntil(() -> local.recorder.messages.contains(after), "the last packet was never written");

            List<Object> written = local.recorder.messages;
            assertSame(before, written.get(0));
            assertSame(after, written.get(written.size() - 1));
            List<ClientboundRecipeBookAddPacket> chunks = written.subList(1, written.size() - 1).stream()
                    .map(ClientboundRecipeBookAddPacket.class::cast).toList();
            assertTrue(chunks.size() > 1);
            assertTrue(chunks.get(0).replace());
            assertTrue(chunks.stream().skip(1).noneMatch(ClientboundRecipeBookAddPacket::replace));
            assertSameEntries(entries, chunks);
            for (int i = 1; i < written.size() - 1; i++) {
                assertSame(written.get(i), local.recorder.resent.get(i), "write " + i);
            }
        }
    }
}
