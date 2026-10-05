package dev.recipebooksplitter.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * @param maxChunkBytes upper bound for the encoded size of one recipe book packet (packet id plus payload,
 *                      before compression)
 * @param logSplits log an INFO line whenever a packet had to be split
 * @param logOversizedPackets log a WARN line for every encoded clientbound packet over 4 MiB
 */
public record SplitterConfig(int maxChunkBytes, boolean logSplits, boolean logOversizedPackets) {
    public static final int DEFAULT_MAX_CHUNK_BYTES = 1_048_576;
    public static final int MIN_MAX_CHUNK_BYTES = 65_536;
    /**
     * Stays below the 2,097,151-byte limit of the 3-byte VarInt frame length, which is the effective packet limit
     * when network compression is off (common behind Velocity), leaving room for compression and ViaVersion overhead.
     */
    public static final int MAX_MAX_CHUNK_BYTES = 2_000_000;

    public static final SplitterConfig DEFAULTS = new SplitterConfig(DEFAULT_MAX_CHUNK_BYTES, true, false);

    private static final String KEY_MAX_CHUNK_BYTES = "maxChunkBytes";
    private static final String KEY_LOG_SPLITS = "logSplits";
    private static final String KEY_LOG_OVERSIZED_PACKETS = "logOversizedPackets";
    private static final Set<String> KNOWN_KEYS = Set.of(KEY_MAX_CHUNK_BYTES, KEY_LOG_SPLITS, KEY_LOG_OVERSIZED_PACKETS);

    public SplitterConfig {
        if (maxChunkBytes < MIN_MAX_CHUNK_BYTES || maxChunkBytes > MAX_MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("maxChunkBytes out of range: " + maxChunkBytes);
        }
    }

    /**
     * Reads the config file, creating it with the defaults if it does not exist. A file that cannot be read or parsed
     * is reported and left untouched, and the defaults are used.
     */
    public static SplitterConfig loadOrCreate(Path file, Logger log) {
        if (Files.notExists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULTS.toJson() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                log.info("[RecipeBookSplitter] created default config {}", file);
                return DEFAULTS;
            } catch (FileAlreadyExistsException e) {
                // Created by someone else in the meantime: read it below.
            } catch (IOException e) {
                log.error("[RecipeBookSplitter] could not write default config {}: {}", file, e.toString());
                return DEFAULTS;
            }
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), log);
        } catch (IOException | RuntimeException e) {
            log.error("[RecipeBookSplitter] could not read config {} ({}); using defaults. The file was left unchanged.", file, e.toString());
            return DEFAULTS;
        }
    }

    /**
     * Invalid single values fall back to their default (or are clamped) with a warning; missing keys use the default.
     *
     * @throws JsonParseException if the text is not a JSON object
     */
    static SplitterConfig parse(String json, Logger log) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new JsonParseException("top-level value must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                log.warn("[RecipeBookSplitter] unknown config key '{}' ignored", key);
            }
        }
        return new SplitterConfig(
                parseMaxChunkBytes(value(object, KEY_MAX_CHUNK_BYTES, DEFAULT_MAX_CHUNK_BYTES, log), log),
                parseBoolean(object, KEY_LOG_SPLITS, DEFAULTS.logSplits(), log),
                parseBoolean(object, KEY_LOG_OVERSIZED_PACKETS, DEFAULTS.logOversizedPackets(), log));
    }

    /** The value of a key, or null (logged at INFO, the same for every key) if it is missing. */
    private static @Nullable JsonElement value(JsonObject object, String key, Object fallback, Logger log) {
        JsonElement element = object.get(key);
        if (element == null) {
            log.info("[RecipeBookSplitter] '{}' missing, using default {}", key, fallback);
        }
        return element;
    }

    private static int parseMaxChunkBytes(@Nullable JsonElement element, Logger log) {
        if (element == null) {
            return DEFAULT_MAX_CHUNK_BYTES;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            log.warn("[RecipeBookSplitter] '{}' must be a number, got {}; using default {}", KEY_MAX_CHUNK_BYTES, element, DEFAULT_MAX_CHUNK_BYTES);
            return DEFAULT_MAX_CHUNK_BYTES;
        }
        BigDecimal value = element.getAsBigDecimal();
        BigInteger integer;
        try {
            integer = value.toBigIntegerExact();
        } catch (ArithmeticException e) {
            log.warn("[RecipeBookSplitter] '{}' must be a whole number, got {}; using default {}", KEY_MAX_CHUNK_BYTES, value, DEFAULT_MAX_CHUNK_BYTES);
            return DEFAULT_MAX_CHUNK_BYTES;
        }
        if (integer.compareTo(BigInteger.valueOf(MIN_MAX_CHUNK_BYTES)) < 0) {
            log.warn("[RecipeBookSplitter] {} {} is below the minimum {}; using {}", KEY_MAX_CHUNK_BYTES, value, MIN_MAX_CHUNK_BYTES, MIN_MAX_CHUNK_BYTES);
            return MIN_MAX_CHUNK_BYTES;
        }
        if (integer.compareTo(BigInteger.valueOf(MAX_MAX_CHUNK_BYTES)) > 0) {
            log.warn("[RecipeBookSplitter] {} {} is above the maximum {} (the frame limit is 2,097,151 bytes when compression is off); using {}",
                    KEY_MAX_CHUNK_BYTES, value, MAX_MAX_CHUNK_BYTES, MAX_MAX_CHUNK_BYTES);
            return MAX_MAX_CHUNK_BYTES;
        }
        return integer.intValueExact();
    }

    private static boolean parseBoolean(JsonObject object, String key, boolean fallback, Logger log) {
        JsonElement element = value(object, key, fallback, log);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            log.warn("[RecipeBookSplitter] '{}' must be true or false, got {}; using default {}", key, element, fallback);
            return fallback;
        }
        return element.getAsBoolean();
    }

    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty(KEY_MAX_CHUNK_BYTES, maxChunkBytes);
        object.addProperty(KEY_LOG_SPLITS, logSplits);
        object.addProperty(KEY_LOG_OVERSIZED_PACKETS, logOversizedPackets);
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
