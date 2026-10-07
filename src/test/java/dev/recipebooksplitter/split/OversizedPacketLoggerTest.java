package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import dev.recipebooksplitter.testutil.TestConfigs;
import java.util.List;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The {@code logOversizedPackets} option: the encoder hook in {@code PacketEncoderMixin} and its message. */
class OversizedPacketLoggerTest {
    private static final String OVERSIZED = "oversized clientbound packet clientbound/minecraft:recipe_book_add";

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @AfterEach
    void tearDown() {
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    /** Sends the packet through a real connection and encoder and returns the mod's WARN messages. */
    private static List<String> warningsWhenSending(boolean logOversizedPackets, ClientboundRecipeBookAddPacket packet) throws Exception {
        // "send": one-entry packets are then left to vanilla, as in 1.0.0, so only the logger speaks.
        RecipeBookSplitter.setConfig(TestConfigs.of(262_144, logOversizedPackets, UndeliverableEntries.SEND, false));
        try (TestConnection test = TestConnection.create(new PacketEncoder<>(RecipeFixtures.protocol()));
             LogCapture log = new LogCapture("RecipeBookSplitter")) {
            test.connection().send(packet);
            test.channel().runPendingTasks();
            return log.messages(Level.WARN);
        }
    }

    private static ClientboundRecipeBookAddPacket oneEntry(int padBytes) {
        return new ClientboundRecipeBookAddPacket(List.of(RecipeFixtures.entry(1, padBytes, (byte) 0)), true);
    }

    @Test
    void warnsForAPacketOverFourMiB() throws Exception {
        List<String> warnings = warningsWhenSending(true, oneEntry(5_000_000));

        assertEquals(1, warnings.size(), warnings.toString());
        String message = warnings.get(0);
        assertTrue(message.contains(OVERSIZED + " for embedded: 4.8 MiB ("), message);
        assertTrue(message.endsWith(" - over 2,097,151 bytes: it only fits in a frame if network compression shrinks it below that"), message);
    }

    @Test
    void mentionsTheEightMiBLimitWhenExceeded() throws Exception {
        List<String> warnings = warningsWhenSending(true, oneEntry(9_000_000));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).endsWith(" - exceeds the 8,388,608-byte limit"), warnings.get(0));
    }

    @Test
    void silentWhenDisabled() throws Exception {
        assertEquals(List.of(), warningsWhenSending(false, oneEntry(5_000_000)));
    }

    @Test
    void silentForPacketsUnderFourMiB() throws Exception {
        assertEquals(List.of(), warningsWhenSending(true, new ClientboundRecipeBookAddPacket(RecipeFixtures.entries(400), true)));
    }

    /**
     * Measuring a 5 MB entry encodes a 5 MB probe packet; the hook must not report probes, only what is really sent.
     */
    @Test
    void probeEncodesAreNotReported() throws Exception {
        ClientboundRecipeBookAddPacket book = new ClientboundRecipeBookAddPacket(
                List.of(RecipeFixtures.entry(1, 100, (byte) 0), RecipeFixtures.entry(2, 5_000_000, (byte) 0), RecipeFixtures.entry(3, 100, (byte) 0)), true);

        List<String> warnings = warningsWhenSending(true, book);

        assertEquals(1, warnings.stream().filter(message -> message.contains(OVERSIZED)).count(), warnings.toString());
        assertEquals(1, warnings.stream().filter(message -> message.contains("on its own, more than maxChunkBytes")).count(), warnings.toString());
    }
}
