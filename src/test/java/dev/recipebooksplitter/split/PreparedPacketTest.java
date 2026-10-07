package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Writing a packet from measured bytes, and what happens when that does not work or is being checked. */
class PreparedPacketTest {
    private static final byte[] PREFIX = {7, 7, 7};

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @AfterEach
    void reset() {
        RecipeBookSendInterceptor.verifyEncodeOnce = false;
    }

    /** The packet of the entries {@code [0, 10)} of a book, with the bytes measured for the whole book. */
    private static PreparedPacket prepare(boolean replace, long sizeOffset) throws Exception {
        List<Entry> book = RecipeFixtures.entries(20);
        EntrySizer.Measurement measurement = EntrySizer.measure(book, RecipeFixtures.writer(), false, true);
        List<Entry> entries = book.subList(0, 10);
        long entryBytes = 0;
        for (int i = 0; i < 10; i++) {
            entryBytes += measurement.entryBytes()[i];
        }
        int[] indexes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        long size = ChunkPlanner.packetBytes(measurement.fixedOverheadBytes(), 10, entryBytes) + sizeOffset;
        return new PreparedPacket(new ClientboundRecipeBookAddPacket(entries, replace), new Object(), measurement.encoded(), indexes, size);
    }

    private static byte[] normalBytes(PreparedPacket prepared) throws Exception {
        ByteBuf buf = RecipeFixtures.encode(prepared.packet);
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static ByteBuf outWithPrefix() {
        return Unpooled.buffer().writeBytes(PREFIX);
    }

    @Test
    void reuseWritesKeptBytes() throws Exception {
        for (boolean replace : new boolean[] {false, true}) {
            PreparedPacket prepared = prepare(replace, 0);
            ByteBuf out = outWithPrefix();
            AtomicInteger encodedNormally = new AtomicInteger();

            boolean written = prepared.write(out, encodedNormally::incrementAndGet);

            assertTrue(written);
            assertEquals(PreparedPacket.Outcome.REUSED, prepared.outcome);
            assertEquals(0, encodedNormally.get());
            assertArrayEquals(normalBytes(prepared), ByteBufUtil.getBytes(out, PREFIX.length, out.readableBytes() - PREFIX.length));
            assertArrayEquals(PREFIX, ByteBufUtil.getBytes(out, 0, PREFIX.length));
            out.release();
        }
    }

    @Test
    void failedWriteResetsAndFallsBack() throws Exception {
        PreparedPacket prepared = prepare(true, 1);
        ByteBuf out = outWithPrefix();

        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            boolean written = prepared.write(out, () -> {
                throw new AssertionError("the caller encodes, not the hook");
            });

            assertFalse(written);
            assertEquals(PreparedPacket.Outcome.FAILED, prepared.outcome);
            assertEquals(PREFIX.length, out.writerIndex());
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(1, errors.size(), log.entries().toString());
            assertTrue(errors.get(0).startsWith("[RecipeBookSplitter] could not write the measured bytes of a recipe book packet (10 entries); encoding it normally"), errors.get(0));
        }
        out.release();
    }

    @Test
    void verifyModeSendsNormalBytesAndFlagsMismatch() throws Exception {
        RecipeBookSendInterceptor.verifyEncodeOnce = true;

        // The normal encode agrees: nothing to report, and out holds the normally encoded bytes.
        PreparedPacket same = prepare(true, 0);
        ByteBuf out = outWithPrefix();
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            assertTrue(same.write(out, () -> normalEncode(same, out, false)));

            assertEquals(PreparedPacket.Outcome.VERIFIED, same.outcome);
            assertEquals(List.of(), log.entries());
        }
        assertArrayEquals(normalBytes(same), ByteBufUtil.getBytes(out, PREFIX.length, out.readableBytes() - PREFIX.length));
        out.release();

        // The normal encode differs (here: one byte): flagged, and the normal bytes are what stays in out.
        PreparedPacket different = prepare(true, 0);
        ByteBuf other = outWithPrefix();
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            assertTrue(different.write(other, () -> normalEncode(different, other, true)));

            assertEquals(PreparedPacket.Outcome.MISMATCH, different.outcome);
            List<String> errors = log.messages(Level.ERROR);
            assertEquals(1, errors.size(), log.entries().toString());
            assertTrue(errors.get(0).startsWith("[RecipeBookSplitter] measured bytes of a recipe book packet (10 entries) differ from a normal encode ("), errors.get(0));
            assertTrue(errors.get(0).endsWith("); the normally encoded bytes were sent"), errors.get(0));
        }
        byte[] expected = normalBytes(different);
        expected[expected.length - 1] ^= 1;
        assertArrayEquals(expected, ByteBufUtil.getBytes(other, PREFIX.length, other.readableBytes() - PREFIX.length));
        other.release();
    }

    private static void normalEncode(PreparedPacket prepared, ByteBuf out, boolean flipLastByte) {
        try {
            RecipeFixtures.writer().write(prepared.packet, out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        if (flipLastByte) {
            out.setByte(out.writerIndex() - 1, out.getByte(out.writerIndex() - 1) ^ 1);
        }
    }
}
