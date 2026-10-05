package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.HandlerNames;
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

    /** The realistic case: Netty tears the pipeline down in a later task, so right after a close it is still intact. */
    @Test
    void closedChannelWithIntactPipelineIsNotMeasured() throws Exception {
        try (LocalConnection local = new LocalConnection(true); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            ClientboundRecipeBookAddPacket book = book(400);
            AtomicBoolean pipelineIntact = new AtomicBoolean();

            // What the scheduled task does when the client left before it ran.
            local.child.eventLoop().submit(() -> {
                local.child.close();
                pipelineIntact.set(local.child.pipeline().get(HandlerNames.ENCODER) != null);
                RecipeBookSendInterceptor.splitAndWrite(local.connection, local.child, book, null, true);
            }).sync();

            assertTrue(pipelineIntact.get(), "the test is only meaningful while the encoder is still there");
            assertEquals(0, local.encodes.get(), "no probe encodes for a closed channel");
            assertEquals(List.of(), log.entries());
        }
    }

    @Test
    void closedChannelWithoutPipelineDoesNotSpendTheNoEncoderWarning() throws Exception {
        try (LocalConnection local = new LocalConnection(true); LogCapture log = new LogCapture("RecipeBookSplitter")) {
            local.child.close().sync();
            LocalConnection.awaitUntil(() -> local.child.pipeline().get(HandlerNames.ENCODER) == null, "the pipeline was not torn down");

            RecipeBookSendInterceptor.splitAndWrite(local.connection, local.child, book(400), null, true);

            assertEquals(List.of(), log.entries());
            assertFalse(RecipeBookSendInterceptor.WARNED_NO_ENCODER.get(), "a dead connection must not use up the one-time warning");
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
            // Wait by identity: the two books are equal, so contains() would return as soon as the first is written.
            LocalConnection.awaitUntil(() -> local.recorder.messages.stream().anyMatch(message -> message == second), "the packets were never written");

            assertEquals(2, local.recorder.messages.size(), "both are sent unsplit");
            assertSame(first, local.recorder.messages.get(0));
            assertSame(second, local.recorder.messages.get(1));
            assertEquals(1, log.messages(Level.WARN).size(), log.entries().toString());
            assertTrue(log.messages(Level.WARN).get(0).contains("no PacketEncoder named 'encoder'"));
            assertEquals(1, log.messages(Level.DEBUG).size(), "the second one is only logged at DEBUG");
        }
    }
}
