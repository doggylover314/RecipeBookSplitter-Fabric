package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectionLimits} against the real vanilla handlers: what it predicts for a packet must be what
 * {@code CompressionEncoder} and {@code Varint21LengthFieldPrepender} then do with it.
 */
class ConnectionLimitsTest {
    private static final ConnectionLimits.EncodedPacket NEVER = () -> {
        throw new AssertionError("the packet must not be encoded for this verdict");
    };

    private static final ConnectionLimits OFF = new ConnectionLimits(ConnectionLimits.Mode.UNCOMPRESSED, -1);
    private static final ConnectionLimits ON = new ConnectionLimits(ConnectionLimits.Mode.COMPRESSED, 256);

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @BeforeEach
    void setUp() {
        // "send" and one-entry packets: the mod leaves them to vanilla, so the real pipeline decides.
        RecipeBookSplitter.setConfig(TestConfigs.of(SplitterConfig.MAX_MAX_CHUNK_BYTES, false, UndeliverableEntries.SEND, false));
    }

    @AfterEach
    void tearDown() {
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
        ConnectionLimits.compressionLimitMayBeLifted = false;
    }

    @Test
    void detectsTheModeFromVanillaHandlerNames() {
        EmbeddedChannel plain = new EmbeddedChannel();
        plain.pipeline().addLast(HandlerNames.PREPENDER, new Varint21LengthFieldPrepender());
        assertEquals(OFF, ConnectionLimits.detect(plain.pipeline()));

        EmbeddedChannel compressed = new EmbeddedChannel();
        compressed.pipeline().addLast(HandlerNames.PREPENDER, new Varint21LengthFieldPrepender());
        compressed.pipeline().addLast(HandlerNames.COMPRESS, new CompressionEncoder(256));
        // ViaFabric's handler between compress and encoder changes nothing.
        compressed.pipeline().addLast("via-encoder", new ChannelDuplexHandler());
        assertEquals(ON, ConnectionLimits.detect(compressed.pipeline()));

        EmbeddedChannel foreignCompressor = new EmbeddedChannel();
        foreignCompressor.pipeline().addLast(HandlerNames.PREPENDER, new Varint21LengthFieldPrepender());
        foreignCompressor.pipeline().addLast(HandlerNames.COMPRESS, new ChannelDuplexHandler());
        assertEquals(ConnectionLimits.UNKNOWN, ConnectionLimits.detect(foreignCompressor.pipeline()));

        EmbeddedChannel noFraming = new EmbeddedChannel();
        assertEquals(ConnectionLimits.UNKNOWN, ConnectionLimits.detect(noFraming.pipeline()));

        plain.finishAndReleaseAll();
        compressed.finishAndReleaseAll();
        foreignCompressor.finishAndReleaseAll();
        noFraming.finishAndReleaseAll();
    }

    // ---- The arithmetic alone, with the packet size as input.

    @Test
    void uncompressedBoundary() throws Exception {
        ConnectionLimits.Verdict fits = OFF.check(2_097_151, NEVER);
        assertTrue(fits.sendable());
        assertEquals(2_097_151, fits.frameBytes());

        ConnectionLimits.Verdict tooBig = OFF.check(2_097_152, NEVER);
        assertFalse(tooBig.sendable());
        assertTrue(tooBig.certainlyUnsendable());
        assertEquals("network compression is off, and a frame can hold at most 2,097,151 bytes", tooBig.reason());
    }

    @Test
    void compressedOverEightMiBNeedsNoEncode() throws Exception {
        assertTrue(ON.check(8_388_608, () -> Unpooled.wrappedBuffer(new byte[8_388_608])).sendable(), "all zeros: deflates to almost nothing");

        ConnectionLimits.Verdict refused = ON.check(8_388_609, NEVER);
        assertFalse(refused.sendable());
        assertEquals("network compression refuses packets over 8,388,608 bytes", refused.reason());
        assertEquals(-1, refused.frameBytes());
        // The 8 MiB test comes first, even where the packet is also below a (huge) threshold.
        assertFalse(new ConnectionLimits(ConnectionLimits.Mode.COMPRESSED, 10_000_000).check(8_388_609, NEVER).sendable());
    }

    /**
     * Packet Fixer lifts the 8,388,608-byte check inside the vanilla {@code CompressionEncoder}, which the handler class
     * does not show. A packet over that size is then decided by its frame alone, and whether it is sent is unknown.
     */
    @Test
    void whereTheCompressionLimitMayBeLiftedOnlyTheFrameDecides() throws Exception {
        ConnectionLimits.compressionLimitMayBeLifted = true;

        ConnectionLimits.Verdict zeros = ON.check(9_000_000, () -> Unpooled.wrappedBuffer(new byte[9_000_000]));
        assertNull(zeros.sendable(), "not certainly unsendable: a mod may send it");
        assertFalse(zeros.certainlyUnsendable());
        assertTrue(zeros.frameBytes() > 4 && zeros.frameBytes() < 20_000, "frame " + zeros.frameBytes());
        assertEquals("it is over 8,388,608 bytes, which network compression refuses unless a mod such as Packet Fixer lifts that limit, so whether this connection can send it is unknown", zeros.reason());

        byte[] random = new byte[9_000_000];
        new java.util.Random(1).nextBytes(random);
        ConnectionLimits.Verdict incompressible = ON.check(9_000_000, () -> Unpooled.wrappedBuffer(random));
        assertTrue(incompressible.certainlyUnsendable(), "the frame limit still holds");
        assertTrue(incompressible.reason().startsWith("it compresses to a 9,0"), incompressible.reason());

        // Nothing changes at or below the limit, or without compression.
        assertTrue(ON.check(8_388_608, () -> Unpooled.wrappedBuffer(new byte[8_388_608])).sendable());
        assertTrue(ON.check(2_000_000, NEVER).sendable());
        assertTrue(OFF.check(2_097_151, NEVER).sendable());
        assertFalse(OFF.check(2_097_152, NEVER).sendable());

        ConnectionLimits.compressionLimitMayBeLifted = false;
        assertFalse(ON.check(9_000_000, NEVER).sendable(), "without such a mod it is refused without encoding");
    }

    @Test
    void belowThresholdRawBoundary() throws Exception {
        // Below the threshold a packet goes out raw behind a one-byte VarInt 0, so its frame is one byte longer.
        ConnectionLimits rawBelowTenMillion = new ConnectionLimits(ConnectionLimits.Mode.COMPRESSED, 10_000_000);

        ConnectionLimits.Verdict fits = rawBelowTenMillion.check(2_097_150, NEVER);
        assertTrue(fits.sendable());
        assertEquals(2_097_151, fits.frameBytes());

        ConnectionLimits.Verdict tooBig = rawBelowTenMillion.check(2_097_151, NEVER);
        assertFalse(tooBig.sendable());
        assertEquals(2_097_152, tooBig.frameBytes());
        assertEquals("it is below the compression threshold, so it is not compressed, and a frame can hold at most 2,097,151 bytes", tooBig.reason());
    }

    @Test
    void upToTwoMillionNeedsNoEncode() throws Exception {
        assertTrue(ON.check(2_000_000, NEVER).sendable());
        assertTrue(ON.check(256, NEVER).sendable(), "exactly the threshold: compressed, and small");
        assertTrue(ON.check(255, NEVER).sendable(), "below the threshold: raw, and small");
    }

    @Test
    void betweenTwoMillionAndEightMiBTheFrameIsDeflated() throws Exception {
        AtomicInteger encodes = new AtomicInteger();
        ConnectionLimits.Verdict verdict = ON.check(2_500_000, () -> {
            encodes.incrementAndGet();
            return Unpooled.wrappedBuffer(new byte[2_500_000]);
        });

        assertEquals(1, encodes.get());
        assertTrue(verdict.sendable());
        // VarInt(2,500,000) is 4 bytes, and 2.5 MB of zeros deflate to a few KB.
        assertTrue(verdict.frameBytes() > 4 && verdict.frameBytes() < 10_000, "frame " + verdict.frameBytes());
    }

    @Test
    void unknownNeverDrops() throws Exception {
        ConnectionLimits.Verdict verdict = ConnectionLimits.UNKNOWN.check(100_000_000, NEVER);

        assertNull(verdict.sendable());
        assertFalse(verdict.certainlyUnsendable());
    }

    @Test
    void encodedSizeMismatchThrows() {
        ByteBuf wrongSize = Unpooled.wrappedBuffer(new byte[2_500_001]);

        assertThrows(IllegalStateException.class, () -> ON.check(2_500_000, () -> wrongSize));
        assertEquals(0, wrongSize.refCnt(), "the buffer is released even then");
    }

    // ---- The arithmetic against the real handlers.

    private static ClientboundRecipeBookAddPacket single(ClientboundRecipeBookAddPacket.Entry entry) {
        return new ClientboundRecipeBookAddPacket(List.of(entry), false);
    }

    private static int encodedSize(ClientboundRecipeBookAddPacket packet) throws Exception {
        ByteBuf buf = RecipeFixtures.encode(packet);
        try {
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /** Predicts, sends through the real pipeline, and compares. Returns the prediction. */
    private static ConnectionLimits.Verdict predictAndSend(int threshold, ClientboundRecipeBookAddPacket packet, String label) throws Exception {
        try (VanillaPipeline pipe = new VanillaPipeline(threshold, false)) {
            ConnectionLimits limits = ConnectionLimits.detect(pipe.channel.pipeline());
            int size = encodedSize(packet);
            ConnectionLimits.Verdict verdict = limits.check(size, () -> RecipeFixtures.encode(packet));

            pipe.connection.send(packet);
            pipe.channel.runPendingTasks();
            boolean disconnected = !pipe.channel.isOpen();
            List<VanillaPipeline.Frame> frames = disconnected ? List.of() : pipe.readFrames();

            assertEquals(Boolean.TRUE.equals(verdict.sendable()), !disconnected, label + ": prediction vs pipeline");
            if (!disconnected) {
                assertEquals(1, frames.size());
                assertEquals(size, frames.get(0).packetBytes());
                if (verdict.frameBytes() >= 0) {
                    assertEquals(verdict.frameBytes(), frames.get(0).frameBodyBytes(), label + ": exact frame size");
                }
            }
            return verdict;
        }
    }

    @Test
    void uncompressedLimitIsExact() throws Exception {
        int base = encodedSize(single(RecipeFixtures.entry(1, 0, (byte) 0)));
        int padForLimit = ConnectionLimits.FRAME_LIMIT_BYTES - base;
        assertEquals(ConnectionLimits.FRAME_LIMIT_BYTES, encodedSize(single(RecipeFixtures.entry(1, padForLimit, (byte) 0))));

        assertTrue(predictAndSend(-1, single(RecipeFixtures.entry(1, padForLimit, (byte) 0)), "off, exactly 2,097,151").sendable());
        assertFalse(predictAndSend(-1, single(RecipeFixtures.entry(1, padForLimit + 1, (byte) 0)), "off, 2,097,152").sendable());
    }

    @Test
    void compressedLimitsAreExact() throws Exception {
        // Compressible and big: fine with compression on.
        assertTrue(predictAndSend(256, single(RecipeFixtures.entry(1, 4_500_000, (byte) 0)), "on, 4.5 MB zeros").sendable());
        // Over 8 MiB: CompressionEncoder refuses it whatever it would compress to.
        int base = encodedSize(single(RecipeFixtures.entry(1, 0, (byte) 0)));
        assertTrue(predictAndSend(256, single(RecipeFixtures.entry(1, ConnectionLimits.COMPRESSION_LIMIT_BYTES - base, (byte) 0)), "on, exactly 8,388,608 zeros").sendable());
        assertFalse(predictAndSend(256, single(RecipeFixtures.entry(1, ConnectionLimits.COMPRESSION_LIMIT_BYTES - base + 1, (byte) 0)), "on, 8,388,609 zeros").sendable());
        // Incompressible: the deflated frame decides, exactly.
        assertTrue(predictAndSend(256, single(RecipeFixtures.incompressibleEntry(1, 2_050_000, (byte) 0)), "on, 2.05 MB random").sendable());
        assertTrue(predictAndSend(256, single(RecipeFixtures.incompressibleEntry(1, 2_096_000, (byte) 0)), "on, 2,096,045 random").sendable());
        // 2,097,000 bytes would fit a frame uncompressed, but DEFLATE adds a few hundred bytes to random data.
        assertFalse(predictAndSend(256, single(RecipeFixtures.incompressibleEntry(1, 2_097_000 - base, (byte) 0)), "on, 2,097,000 random (fits uncompressed, not compressed)").sendable());
        assertFalse(predictAndSend(256, single(RecipeFixtures.incompressibleEntry(1, 3_000_000, (byte) 0)), "on, 3 MB random").sendable());
        // A threshold above the packet size sends it raw behind VarInt 0.
        assertFalse(predictAndSend(10_000_000, single(RecipeFixtures.entry(1, 2_200_000, (byte) 0)), "threshold 10M, 2.2 MB raw").sendable());
        assertTrue(predictAndSend(10_000_000, single(RecipeFixtures.entry(1, 2_000_000, (byte) 0)), "threshold 10M, 2.0 MB raw").sendable());
    }

    /** Below ALWAYS_FITS_COMPRESSED_BYTES the check does not deflate; random data at that size must still fit. */
    @Test
    void alwaysFitsBoundHoldsForIncompressibleData() throws Exception {
        int base = encodedSize(single(RecipeFixtures.entry(1, 0, (byte) 0)));
        ClientboundRecipeBookAddPacket packet = single(RecipeFixtures.incompressibleEntry(1, ConnectionLimits.ALWAYS_FITS_COMPRESSED_BYTES - base, (byte) 0));
        ByteBuf bytes = RecipeFixtures.encode(packet);
        try {
            assertEquals(ConnectionLimits.ALWAYS_FITS_COMPRESSED_BYTES, bytes.readableBytes());
            long frame = 4 + ConnectionLimits.deflatedSize(bytes);
            assertTrue(frame <= ConnectionLimits.FRAME_LIMIT_BYTES, "frame " + frame);
        } finally {
            bytes.release();
        }
    }
}
