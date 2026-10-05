package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
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

    /** Sits in front of the encoder and records what the connection writes, and whether our send guard was active. */
    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        // Written on the event loop, read by the test thread.
        final List<Object> messages = new CopyOnWriteArrayList<>();
        final List<Boolean> guardActive = new CopyOnWriteArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            messages.add(msg);
            guardActive.add(RecipeBookSendInterceptor.isSendingChunks());
            ctx.write(msg, promise);
        }
    }

    private Connection connection;
    private EmbeddedChannel channel;
    private Recorder recorder;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        RecipeBookSplitter.setConfig(new SplitterConfig(BUDGET, true, true));
        recorder = new Recorder();
        connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(false, false);
        // Outbound packets travel packet_handler -> recorder -> encoder.
        channel.pipeline().addLast(HandlerNames.ENCODER, new PacketEncoder<>(RecipeFixtures.protocol()));
        channel.pipeline().addLast("recorder", recorder);
        channel.pipeline().addLast(HandlerNames.PACKET_HANDLER, connection);
        channel.register();
        assertTrue(connection.isConnected());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
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

        // The connection only ever saw our chunk objects, each written while the recursion guard was set.
        assertEquals(chunks.size(), recorder.messages.size());
        assertTrue(recorder.messages.stream().allMatch(ClientboundRecipeBookAddPacket.class::isInstance));
        assertTrue(recorder.guardActive.stream().allMatch(Boolean::booleanValue));
        assertFalse(RecipeBookSendInterceptor.isSendingChunks());
    }

    @Test
    void splitsReplaceFalse() {
        List<Entry> entries = RecipeFixtures.entries(400);

        connection.send(new ClientboundRecipeBookAddPacket(entries, false));
        channel.runPendingTasks();

        List<ClientboundRecipeBookAddPacket> chunks = addPackets(readFrames(channel));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().noneMatch(ClientboundRecipeBookAddPacket::replace));
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
            recorder.guardActive.clear();

            connection.send(packet);
            channel.runPendingTasks();

            assertEquals(1, recorder.messages.size(), packet.type().toString());
            assertSame(packet, recorder.messages.get(0));
            assertFalse(recorder.guardActive.get(0), "the vanilla path does not go through our chunk writer");
        }
    }

    @Test
    void noEncoderFallsBack() throws Exception {
        EmbeddedChannel bare = new EmbeddedChannel(false, false);
        Recorder bareRecorder = new Recorder();
        Connection bareConnection = new Connection(PacketFlow.SERVERBOUND);
        bare.pipeline().addLast("recorder", bareRecorder);
        bare.pipeline().addLast(HandlerNames.PACKET_HANDLER, bareConnection);
        bare.register();
        ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true);

        bareConnection.send(original);
        bare.runPendingTasks();

        assertEquals(1, bareRecorder.messages.size());
        assertSame(original, bareRecorder.messages.get(0));
        assertSame(original, bare.readOutbound());
        assertNull(bare.readOutbound());
        bare.finishAndReleaseAll();
    }

    @Test
    void measurementFailureFallsBack() throws Exception {
        // A configuration-phase encoder cannot encode play packets, so sizing fails with "Sending unknown packet".
        EmbeddedChannel wrongPhase = new EmbeddedChannel(false, false);
        Recorder wrongRecorder = new Recorder();
        Connection wrongConnection = new Connection(PacketFlow.SERVERBOUND);
        wrongPhase.pipeline().addLast(HandlerNames.ENCODER, new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        wrongPhase.pipeline().addLast("recorder", wrongRecorder);
        wrongPhase.pipeline().addLast(HandlerNames.PACKET_HANDLER, wrongConnection);
        wrongPhase.register();
        ClientboundRecipeBookAddPacket original = new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(10), true);

        wrongConnection.send(original);
        wrongPhase.runPendingTasks();

        // The original is sent once, unsplit. (It then fails in the encoder as it would without the mod.)
        List<Object> sentAddPackets = wrongRecorder.messages.stream().filter(ClientboundRecipeBookAddPacket.class::isInstance).toList();
        assertEquals(1, sentAddPackets.size());
        assertSame(original, sentAddPackets.get(0));
        wrongPhase.finishAndReleaseAll();
    }

    /**
     * The embedded event loop runs everything inline, so this uses a real event loop thread: a recipe book packet
     * sent from another thread is rescheduled by the hook, and must still be written in order between its neighbours.
     */
    @Test
    void chunksStayContiguousWhenSentFromAnotherThread() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        try {
            Connection serverConnection = new Connection(PacketFlow.SERVERBOUND);
            Recorder serverRecorder = new Recorder();
            LocalAddress address = new LocalAddress("recipebooksplitter-" + System.nanoTime());

            new ServerBootstrap()
                    .group(group)
                    .channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override
                        protected void initChannel(LocalChannel child) {
                            child.pipeline().addLast(HandlerNames.ENCODER, new PacketEncoder<>(RecipeFixtures.protocol()));
                            child.pipeline().addLast("recorder", serverRecorder);
                            child.pipeline().addLast(HandlerNames.PACKET_HANDLER, serverConnection);
                        }
                    })
                    .bind(address).sync();
            Channel client = new Bootstrap()
                    .group(group)
                    .channel(LocalChannel.class)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ReferenceCountUtil.release(msg);
                        }
                    })
                    .connect(address).sync().channel();

            awaitUntil(serverConnection::isConnected, "connection did not become active");

            List<Entry> entries = RecipeFixtures.entries(400);
            ClientboundRecipeBookRemovePacket before = new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(1)));
            ClientboundRecipeBookRemovePacket after = new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(2)));
            serverConnection.send(before);
            serverConnection.send(new ClientboundRecipeBookAddPacket(entries, true));
            serverConnection.send(after);

            awaitUntil(() -> serverRecorder.messages.contains(after), "the last packet was never written");

            List<Object> written = serverRecorder.messages;
            assertSame(before, written.get(0));
            assertSame(after, written.get(written.size() - 1));
            List<ClientboundRecipeBookAddPacket> chunks = written.subList(1, written.size() - 1).stream()
                    .map(ClientboundRecipeBookAddPacket.class::cast).toList();
            assertTrue(chunks.size() > 1);
            assertTrue(chunks.get(0).replace());
            assertTrue(chunks.stream().skip(1).noneMatch(ClientboundRecipeBookAddPacket::replace));
            assertSameEntries(entries, chunks);
            assertTrue(serverRecorder.guardActive.subList(1, written.size() - 1).stream().allMatch(Boolean::booleanValue));
            client.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, failureMessage);
            Thread.sleep(5);
        }
    }
}
