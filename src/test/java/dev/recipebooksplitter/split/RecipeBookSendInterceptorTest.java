package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the interceptor behaves around the edges: the recursion guard, closed channels, missing encoders, failures. */
class RecipeBookSendInterceptorTest {
    private static final int BUDGET = 65_536;

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() {
        RecipeBookSplitter.setConfig(new SplitterConfig(BUDGET, true, false));
        RecipeBookSendInterceptor.WARNED_NO_ENCODER.set(false);
    }

    @AfterEach
    void tearDown() {
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
        RecipeBookSendInterceptor.WARNED_NO_ENCODER.set(false);
    }

    private static PacketEncoder<ClientGamePacketListener> encoder() {
        return new PacketEncoder<>(RecipeFixtures.protocol());
    }

    private static ClientboundRecipeBookAddPacket book(int entries) {
        return new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(entries), true);
    }

    private static int framesAndRelease(TestConnection test) {
        int frames = 0;
        ByteBuf frame;
        while ((frame = test.channel().readOutbound()) != null) {
            assertTrue(frame.readableBytes() <= BUDGET, "frame of " + frame.readableBytes() + " bytes");
            frame.release();
            frames++;
        }
        return frames;
    }

    /**
     * While our chunks are written, a send listener that sends a recipe book to someone else must still get it split:
     * the guard covers the one packet being re-sent, not everything on the thread.
     */
    @Test
    void recipePacketForAnotherPlayerIsSplitWhileChunksAreWritten() throws Exception {
        try (TestConnection a = TestConnection.create(encoder()); TestConnection b = TestConnection.create(encoder())) {
            AtomicInteger listenerRuns = new AtomicInteger();
            ChannelFutureListener sendToB = future -> {
                listenerRuns.incrementAndGet();
                b.connection().send(book(400));
            };

            // Once with a book that is split, once with one that is re-sent unchanged.
            a.connection().send(book(400), sendToB, true);
            a.connection().send(book(3), sendToB, true);
            a.channel().runPendingTasks();
            b.channel().runPendingTasks();

            assertEquals(2, listenerRuns.get());
            int framesForB = framesAndRelease(b);
            assertTrue(framesForB > 2 * 2, "both books for B were split, not sent as one frame each: " + framesForB + " frames");
            assertNull(RecipeBookSendInterceptor.currentlyResent());
        }
    }

    @Test
    void brokenPacketIsLeftToVanilla() throws Exception {
        try (TestConnection test = TestConnection.create(encoder())) {
            ClientboundRecipeBookAddPacket broken = new ClientboundRecipeBookAddPacket(null, false);

            assertFalse(RecipeBookSendInterceptor.onSend(test.connection(), broken, null, true));
        }
    }

    @Test
    void closedChannelIsNotMeasured() throws Exception {
        AtomicInteger encodes = new AtomicInteger();
        PacketEncoder<ClientGamePacketListener> counting = new PacketEncoder<>(RecipeFixtures.protocol()) {
            @Override
            protected void encode(ChannelHandlerContext ctx, Packet<ClientGamePacketListener> packet, ByteBuf out) throws Exception {
                encodes.incrementAndGet();
                super.encode(ctx, packet, out);
            }
        };
        try (TestConnection test = TestConnection.create(counting); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            test.channel().close();

            // What the scheduled task does when the client left before it ran.
            RecipeBookSendInterceptor.splitAndWrite(test.connection(), test.channel(), book(400), null, true);

            assertEquals(0, encodes.get(), "no probe encodes for a closed channel");
            assertEquals(List.of(), log.entries(), "nothing to report, in particular no missing-encoder warning");
            assertFalse(RecipeBookSendInterceptor.WARNED_NO_ENCODER.get());
        }
    }

    @Test
    void fakePlayerWithoutEncoderIsOnlyLoggedAtDebug() throws Exception {
        try (TestConnection fake = TestConnection.create(null); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            fake.connection().send(book(400));
            fake.channel().runPendingTasks();

            assertEquals(1, fake.recorder().messages.size());
            assertTrue(log.messages(Level.WARN).isEmpty(), "no warning for an embedded channel: " + log.entries());
            assertEquals(1, log.messages(Level.DEBUG).size());
            assertTrue(log.messages(Level.DEBUG).get(0).contains("no PacketEncoder"));
            assertFalse(RecipeBookSendInterceptor.WARNED_NO_ENCODER.get(), "the one-time warning is kept for real channels");
        }
    }

    @Test
    void realChannelWithoutEncoderWarnsOnce() throws Exception {
        try (LocalConnection local = new LocalConnection(false); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            ClientboundRecipeBookAddPacket first = book(400);
            ClientboundRecipeBookAddPacket second = book(400);

            local.connection.send(first);
            local.connection.send(second);
            LocalConnection.awaitUntil(() -> local.recorder.messages.contains(second), "the packets were never written");

            assertEquals(List.of(first, second), local.recorder.messages, "both are sent unsplit");
            assertEquals(1, log.messages(Level.WARN).size(), log.entries().toString());
            assertTrue(log.messages(Level.WARN).get(0).contains("no PacketEncoder named 'encoder'"));
            assertEquals(1, log.messages(Level.DEBUG).size(), "the second one is only logged at DEBUG");
        }
    }
}
