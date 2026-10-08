package dev.recipebooksplitter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.testutil.LogCapture;
import dev.recipebooksplitter.testutil.TestConfigs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

class SplitterConfigTest {
    private static final Logger LOG = NOPLogger.NOP_LOGGER;

    @TempDir
    Path dir;

    private SplitterConfig loadFile(String content) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return SplitterConfig.loadOrCreate(file, LOG);
    }

    @Test
    void missingFileIsCreatedWithDefaults() throws IOException {
        Path file = dir.resolve("missing-dir").resolve("recipebooksplitter.json");

        SplitterConfig loaded = SplitterConfig.loadOrCreate(file, LOG);

        assertEquals(SplitterConfig.DEFAULTS, loaded);
        assertEquals(SplitterConfig.DEFAULTS.toJson() + "\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(SplitterConfig.DEFAULTS, SplitterConfig.loadOrCreate(file, LOG));
    }

    @Test
    void defaultFileContent() {
        assertEquals("""
                {
                  "maxChunkBytes": 1048576,
                  "logSplits": true,
                  "logOversizedPackets": false,
                  "undeliverableEntries": "drop",
                  "bundleChunks": false
                }""", SplitterConfig.DEFAULTS.toJson());
    }

    @Test
    void malformedFileFallsBackAndIsLeftUntouched() throws IOException {
        String content = "{\"maxChunkBytes\": ";
        Path file = dir.resolve("config.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(SplitterConfig.DEFAULTS, SplitterConfig.loadOrCreate(file, LOG));
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    }

    @Test
    void topLevelArrayFallsBackAndIsLeftUntouched() throws IOException {
        String content = "[1, 2, 3]";
        Path file = dir.resolve("config.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(SplitterConfig.DEFAULTS, SplitterConfig.loadOrCreate(file, LOG));
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
        assertThrows(JsonParseException.class, () -> SplitterConfig.parse(content, LOG));
    }

    @Test
    void missingKeysUseDefaultsWithoutRewritingTheFile() throws IOException {
        String content = "{\"logSplits\": false}";
        Path file = dir.resolve("config.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(new SplitterConfig(1_048_576, false, false, UndeliverableEntries.DROP, false), SplitterConfig.loadOrCreate(file, LOG));
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void wrongTypesFallBackPerKey() throws IOException {
        SplitterConfig loaded = loadFile("{\"maxChunkBytes\": \"big\", \"logSplits\": \"yes\", \"logOversizedPackets\": true}");
        assertEquals(new SplitterConfig(1_048_576, true, true, UndeliverableEntries.DROP, false), loaded);
    }

    @Test
    void maxChunkBytesIsClamped() throws IOException {
        assertEquals(262_144, loadFile("{\"maxChunkBytes\": 1000}").maxChunkBytes());
        assertEquals(262_144, loadFile("{\"maxChunkBytes\": -5}").maxChunkBytes());
        assertEquals(262_144, loadFile("{\"maxChunkBytes\": 65536}").maxChunkBytes(), "the 1.0.0 minimum");
        assertEquals(262_144, loadFile("{\"maxChunkBytes\": 262143}").maxChunkBytes());
        assertEquals(262_144, loadFile("{\"maxChunkBytes\": 262144}").maxChunkBytes());
        assertEquals(1_048_575, loadFile("{\"maxChunkBytes\": 1048575}").maxChunkBytes());
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 1048576}").maxChunkBytes());
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 1048577}").maxChunkBytes());
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 1500000}").maxChunkBytes(), "the ceiling of the earlier 1.1.0 development builds");
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 2000000}").maxChunkBytes(), "the 1.0.0 maximum");
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 1e20}").maxChunkBytes());
    }

    /** The config can only lower the budget: the ceiling is the default, 1 MiB. */
    @Test
    void theCeilingIsTheDefault() {
        assertEquals(1_048_576, SplitterConfig.MAX_MAX_CHUNK_BYTES);
        assertEquals(SplitterConfig.DEFAULT_MAX_CHUNK_BYTES, SplitterConfig.MAX_MAX_CHUNK_BYTES);
        assertEquals(262_144, SplitterConfig.MIN_MAX_CHUNK_BYTES);
        assertEquals(SplitterConfig.MAX_MAX_CHUNK_BYTES, SplitterConfig.DEFAULTS.maxChunkBytes());
        // A frame holds 2,097,151 bytes: the ceiling absorbs a growth by translation of up to about 99 %.
        assertTrue(SplitterConfig.MAX_MAX_CHUNK_BYTES * 1.99 < 2_097_151);
    }

    @Test
    void clampingIsLoggedWithTheReason() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            SplitterConfig.parse("{\"maxChunkBytes\": 65536, \"logSplits\": true, \"logOversizedPackets\": false, \"undeliverableEntries\": \"drop\", \"bundleChunks\": false}",
                    RecipeBookSplitter.LOGGER);
            SplitterConfig.parse("{\"maxChunkBytes\": 2000000, \"logSplits\": true, \"logOversizedPackets\": false, \"undeliverableEntries\": \"drop\", \"bundleChunks\": false}",
                    RecipeBookSplitter.LOGGER);

            assertEquals(List.of(
                    "[RecipeBookSplitter] maxChunkBytes 65536 is below the minimum 262144 (every chunk makes the client rebuild its recipe book); using 262144",
                    "[RecipeBookSplitter] maxChunkBytes 2000000 is above the maximum 1048576; using 1048576. A frame holds at most 2,097,151 bytes as sent, and ViaVersion can make a chunk bigger after the mod has measured it (by 25% and 63% in the two worst cases measured); a budget of 1048576 bytes still fits a growth of up to 99%, and bigger chunks would save the client only a few rebuilds"),
                    log.messages(Level.WARN));
            assertEquals(2, log.entries().size());
        }
    }

    /** The ceiling itself and everything below it is taken as written, without a WARN. */
    @Test
    void valuesUpToTheCeilingAreNotWarnedAbout() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            for (int value : new int[] {262_144, 262_145, 500_000, 1_048_575, 1_048_576}) {
                assertEquals(value, SplitterConfig.parse("{\"maxChunkBytes\": " + value + ", \"logSplits\": true, \"logOversizedPackets\": false, \"undeliverableEntries\": \"drop\", \"bundleChunks\": false}",
                        RecipeBookSplitter.LOGGER).maxChunkBytes());
            }
            assertEquals(List.of(), log.messages(Level.WARN));
            assertEquals(0, log.entries().size());

            assertEquals(1_048_576, SplitterConfig.parse("{\"maxChunkBytes\": 1048577, \"logSplits\": true, \"logOversizedPackets\": false, \"undeliverableEntries\": \"drop\", \"bundleChunks\": false}",
                    RecipeBookSplitter.LOGGER).maxChunkBytes());
            assertEquals(1, log.messages(Level.WARN).size());
            assertTrue(log.messages(Level.WARN).get(0).startsWith("[RecipeBookSplitter] maxChunkBytes 1048577 is above the maximum 1048576; using 1048576."), log.messages(Level.WARN).toString());
        }
    }

    @Test
    void maxChunkBytesMustBeAWholeNumber() throws IOException {
        assertEquals(SplitterConfig.DEFAULT_MAX_CHUNK_BYTES, loadFile("{\"maxChunkBytes\": 1.5}").maxChunkBytes());
        // An integral value written with an exponent is still a whole number (and is clamped like any other).
        assertEquals(1_048_576, loadFile("{\"maxChunkBytes\": 1.5e6}").maxChunkBytes());
        assertEquals(500_000, loadFile("{\"maxChunkBytes\": 5e5}").maxChunkBytes());
        assertEquals(500_000, loadFile("{\"maxChunkBytes\": 500000.0}").maxChunkBytes());
    }

    @Test
    void customValuesRoundTrip() {
        for (SplitterConfig config : new SplitterConfig[] {
                new SplitterConfig(262_144, false, true, UndeliverableEntries.SEND, true),
                new SplitterConfig(SplitterConfig.MAX_MAX_CHUNK_BYTES, true, true, UndeliverableEntries.DROP, false),
                new SplitterConfig(123_456 + 262_144, false, false, UndeliverableEntries.SEND, false)}) {
            assertEquals(config, SplitterConfig.parse(config.toJson(), LOG));
        }
    }

    @Test
    void unknownKeysAreIgnored() throws IOException {
        SplitterConfig loaded = loadFile("{\"maxChunkBytes\": 300000, \"maxChunkByte\": 5, \"logOversizedPackets\": true}");
        assertEquals(TestConfigs.of(300_000, true, UndeliverableEntries.DROP, false), loaded);
    }

    /** Some Windows editors write a UTF-8 byte order mark; Gson skips it. */
    @Test
    void byteOrderMarkIsIgnored() throws IOException {
        assertEquals(new SplitterConfig(300_000, false, true, UndeliverableEntries.DROP, false),
                loadFile("\uFEFF{\"maxChunkBytes\": 300000, \"logSplits\": false, \"logOversizedPackets\": true}"));
    }

    @Test
    void everyMissingKeyIsLoggedAtInfo() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            SplitterConfig.parse("{}", RecipeBookSplitter.LOGGER);

            assertEquals(5, log.entries().size());
            assertEquals(5, log.messages(Level.INFO).size());
            for (String key : new String[] {"maxChunkBytes", "logSplits", "logOversizedPackets", "undeliverableEntries", "bundleChunks"}) {
                assertTrue(log.messages(Level.INFO).stream().anyMatch(message -> message.contains("'" + key + "' missing")), key);
            }
            assertTrue(log.messages(Level.INFO).contains("[RecipeBookSplitter] 'undeliverableEntries' missing, using default drop"), log.messages(Level.INFO).toString());
        }
    }

    /** A file written by 1.0.0 has three keys: it loads, with the new keys at their defaults, and is not rewritten. */
    @Test
    void version100FileLoadsUnchanged() throws IOException {
        String content = """
                {
                  "maxChunkBytes": 1048576,
                  "logSplits": true,
                  "logOversizedPackets": false
                }""";
        Path file = dir.resolve("config.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            assertEquals(SplitterConfig.DEFAULTS, SplitterConfig.loadOrCreate(file, RecipeBookSplitter.LOGGER));

            assertEquals(2, log.messages(Level.INFO).size());
            assertEquals(List.of(), log.messages(Level.WARN));
        }
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void undeliverableEntriesValues() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            assertEquals(UndeliverableEntries.SEND, SplitterConfig.parse("{\"undeliverableEntries\": \"send\"}", RecipeBookSplitter.LOGGER).undeliverableEntries());
            assertEquals(UndeliverableEntries.DROP, SplitterConfig.parse("{\"undeliverableEntries\": \"drop\"}", RecipeBookSplitter.LOGGER).undeliverableEntries());
            assertEquals(List.of(), log.messages(Level.WARN));

            String[] bad = {"\"SEND\"", "\"\"", "1", "true", "null", "{}"};
            for (String value : bad) {
                assertEquals(UndeliverableEntries.DROP,
                        SplitterConfig.parse("{\"undeliverableEntries\": " + value + "}", RecipeBookSplitter.LOGGER).undeliverableEntries(), value);
            }
            List<String> warnings = log.messages(Level.WARN);
            assertEquals(bad.length, warnings.size(), warnings.toString());
            for (int i = 0; i < bad.length; i++) {
                assertEquals("[RecipeBookSplitter] 'undeliverableEntries' must be \"drop\" or \"send\", got " + bad[i] + "; using default \"drop\"", warnings.get(i));
            }
        }
    }

    @Test
    void bundleChunksValues() {
        try (LogCapture log = new LogCapture("RecipeBookSplitter")) {
            assertTrue(SplitterConfig.parse("{\"bundleChunks\": true}", RecipeBookSplitter.LOGGER).bundleChunks());
            assertEquals(List.of(), log.messages(Level.WARN));

            assertEquals(false, SplitterConfig.parse("{\"bundleChunks\": \"yes\"}", RecipeBookSplitter.LOGGER).bundleChunks());
            assertEquals(List.of("[RecipeBookSplitter] 'bundleChunks' must be true or false, got \"yes\"; using default false"), log.messages(Level.WARN));
        }
    }

    @Test
    void constructorRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> TestConfigs.budget(262_143));
        assertThrows(IllegalArgumentException.class, () -> TestConfigs.budget(1_048_577));
        assertThrows(IllegalArgumentException.class, () -> TestConfigs.budget(1_500_000));
        assertEquals(1_048_576, TestConfigs.budget(1_048_576).maxChunkBytes());
        assertThrows(NullPointerException.class, () -> new SplitterConfig(262_144, true, false, null, false));
        assertTrue(TestConfigs.budget(262_144).logSplits());
    }
}
