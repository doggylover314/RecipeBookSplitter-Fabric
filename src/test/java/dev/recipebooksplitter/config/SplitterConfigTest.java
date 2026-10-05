package dev.recipebooksplitter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                  "logOversizedPackets": false
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

        assertEquals(new SplitterConfig(1_048_576, false, false), SplitterConfig.loadOrCreate(file, LOG));
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void wrongTypesFallBackPerKey() throws IOException {
        SplitterConfig loaded = loadFile("{\"maxChunkBytes\": \"big\", \"logSplits\": \"yes\", \"logOversizedPackets\": true}");
        assertEquals(new SplitterConfig(1_048_576, true, true), loaded);
    }

    @Test
    void maxChunkBytesIsClamped() throws IOException {
        assertEquals(65_536, loadFile("{\"maxChunkBytes\": 1000}").maxChunkBytes());
        assertEquals(65_536, loadFile("{\"maxChunkBytes\": -5}").maxChunkBytes());
        assertEquals(65_536, loadFile("{\"maxChunkBytes\": 65535}").maxChunkBytes());
        assertEquals(65_536, loadFile("{\"maxChunkBytes\": 65536}").maxChunkBytes());
        assertEquals(2_000_000, loadFile("{\"maxChunkBytes\": 10000000}").maxChunkBytes());
        assertEquals(2_000_000, loadFile("{\"maxChunkBytes\": 1e20}").maxChunkBytes());
        assertEquals(2_000_000, loadFile("{\"maxChunkBytes\": 2000001}").maxChunkBytes());
        assertEquals(2_000_000, loadFile("{\"maxChunkBytes\": 2000000}").maxChunkBytes());
    }

    @Test
    void maxChunkBytesMustBeAWholeNumber() throws IOException {
        assertEquals(SplitterConfig.DEFAULT_MAX_CHUNK_BYTES, loadFile("{\"maxChunkBytes\": 1.5}").maxChunkBytes());
        // An integral value written with an exponent is still a whole number.
        assertEquals(2_000_000, loadFile("{\"maxChunkBytes\": 2e6}").maxChunkBytes());
        assertEquals(500_000, loadFile("{\"maxChunkBytes\": 500000.0}").maxChunkBytes());
    }

    @Test
    void customValuesRoundTrip() {
        for (SplitterConfig config : new SplitterConfig[] {
                new SplitterConfig(65_536, false, true),
                new SplitterConfig(2_000_000, true, true),
                new SplitterConfig(123_456, false, false)}) {
            assertEquals(config, SplitterConfig.parse(config.toJson(), LOG));
        }
    }

    @Test
    void unknownKeysAreIgnored() throws IOException {
        SplitterConfig loaded = loadFile("{\"maxChunkBytes\": 300000, \"maxChunkByte\": 5, \"logOversizedPackets\": true}");
        assertEquals(new SplitterConfig(300_000, true, true), loaded);
    }

    @Test
    void constructorRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new SplitterConfig(65_535, true, false));
        assertThrows(IllegalArgumentException.class, () -> new SplitterConfig(2_000_001, true, false));
        assertTrue(new SplitterConfig(65_536, true, false).logSplits());
    }
}
