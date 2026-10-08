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
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * @param maxChunkBytes upper bound for the encoded size of one recipe book packet (packet id plus payload,
 *                      before compression and before any ViaVersion translation); from
 *                      {@link #MIN_MAX_CHUNK_BYTES} to {@link #MAX_MAX_CHUNK_BYTES}, and the config can only lower
 *                      it from the default
 * @param logSplits log an INFO line whenever a packet had to be split
 * @param logOversizedPackets log a WARN line for every encoded clientbound packet over 4 MiB
 * @param undeliverableEntries what to do with a recipe display entry that this connection cannot send even in a
 *                             packet of its own
 * @param bundleChunks send the chunks of one split inside one bundle, so the client handles them in one tick
 */
public record SplitterConfig(int maxChunkBytes, boolean logSplits, boolean logOversizedPackets,
                             UndeliverableEntries undeliverableEntries, boolean bundleChunks) {
    public static final int DEFAULT_MAX_CHUNK_BYTES = 1_048_576;
    /**
     * Every chunk makes a vanilla client rebuild its recipe book and start a background rebuild of its search index. A
     * real client spent 4-8 times more render-thread time on 145 chunks (65,536 bytes each) than on 9 (1 MiB); this
     * minimum keeps a 9.2 MB book at about 36 chunks.
     */
    public static final int MIN_MAX_CHUNK_BYTES = 262_144;
    /**
     * The largest budget the config accepts. It equals the default, so the config can only lower the budget.
     * <p>
     * A frame holds at most 2,097,151 bytes, and that limit applies to the packet as it is sent: the raw packet when
     * network compression is off, the compressed packet when it is on, and the raw packet again behind a proxy that
     * forwards it uncompressed (Velocity with {@code compression-threshold = -1}). ViaVersion translates after the mod
     * has measured, so it can make a chunk bigger than the budget. Measured with a 26.2 client through ViaFabric and
     * network compression off: a book of single-item slots grew one chunk by 25.4 % (1,999,931 to 2,507,176 bytes,
     * which disconnected the client), and a book whose slots are direct lists of the 27 items whose id passes 127 grew
     * chunks by 61 to 63 % (1,499,629 to 2,416,673 bytes, which disconnected the client; 1,048,524 to 1,706,228 bytes,
     * which was delivered). These are measured examples, not a bound of the translation. A budget of 1,048,576 bytes
     * still fits a growth of up to about 99 % (2,097,151 / 1,048,576), and a bigger budget would save a client only a
     * few rebuilds of its recipe book, so there is no reason to go above it.
     */
    public static final int MAX_MAX_CHUNK_BYTES = DEFAULT_MAX_CHUNK_BYTES;

    public static final SplitterConfig DEFAULTS =
            new SplitterConfig(DEFAULT_MAX_CHUNK_BYTES, true, false, UndeliverableEntries.DROP, false);

    /** The values of the {@code undeliverableEntries} key. */
    public enum UndeliverableEntries {
        /** Leave out entries that certainly cannot be sent over this connection, and log an ERROR. */
        DROP("drop"),
        /** Send them anyway, as without the mod; the player is then disconnected. */
        SEND("send");

        private final String json;

        UndeliverableEntries(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        /** Exact, case-sensitive match; null for anything else. */
        static @Nullable UndeliverableEntries fromJson(String value) {
            for (UndeliverableEntries mode : values()) {
                if (mode.json.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    private static final String KEY_MAX_CHUNK_BYTES = "maxChunkBytes";
    private static final String KEY_LOG_SPLITS = "logSplits";
    private static final String KEY_LOG_OVERSIZED_PACKETS = "logOversizedPackets";
    private static final String KEY_UNDELIVERABLE_ENTRIES = "undeliverableEntries";
    private static final String KEY_BUNDLE_CHUNKS = "bundleChunks";
    private static final Set<String> KNOWN_KEYS = Set.of(KEY_MAX_CHUNK_BYTES, KEY_LOG_SPLITS, KEY_LOG_OVERSIZED_PACKETS,
            KEY_UNDELIVERABLE_ENTRIES, KEY_BUNDLE_CHUNKS);

    public SplitterConfig {
        if (maxChunkBytes < MIN_MAX_CHUNK_BYTES || maxChunkBytes > MAX_MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("maxChunkBytes out of range: " + maxChunkBytes);
        }
        Objects.requireNonNull(undeliverableEntries, "undeliverableEntries");
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
                parseBoolean(object, KEY_LOG_OVERSIZED_PACKETS, DEFAULTS.logOversizedPackets(), log),
                parseUndeliverableEntries(value(object, KEY_UNDELIVERABLE_ENTRIES, DEFAULTS.undeliverableEntries().json(), log), log),
                parseBoolean(object, KEY_BUNDLE_CHUNKS, DEFAULTS.bundleChunks(), log));
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
            log.warn("[RecipeBookSplitter] {} {} is below the minimum {} (every chunk makes the client rebuild its recipe book); using {}",
                    KEY_MAX_CHUNK_BYTES, value, MIN_MAX_CHUNK_BYTES, MIN_MAX_CHUNK_BYTES);
            return MIN_MAX_CHUNK_BYTES;
        }
        if (integer.compareTo(BigInteger.valueOf(MAX_MAX_CHUNK_BYTES)) > 0) {
            log.warn("[RecipeBookSplitter] {} {} is above the maximum {}; using {}. A frame holds at most 2,097,151 bytes as sent, and ViaVersion can make a chunk bigger after the mod has measured it (by 25% and 63% in the two worst cases measured); a budget of {} bytes still fits a growth of up to 99%, and bigger chunks would save the client only a few rebuilds",
                    KEY_MAX_CHUNK_BYTES, value, MAX_MAX_CHUNK_BYTES, MAX_MAX_CHUNK_BYTES, MAX_MAX_CHUNK_BYTES);
            return MAX_MAX_CHUNK_BYTES;
        }
        return integer.intValueExact();
    }

    private static UndeliverableEntries parseUndeliverableEntries(@Nullable JsonElement element, Logger log) {
        if (element == null) {
            return DEFAULTS.undeliverableEntries();
        }
        UndeliverableEntries mode = element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? UndeliverableEntries.fromJson(element.getAsString())
                : null;
        if (mode == null) {
            log.warn("[RecipeBookSplitter] '{}' must be \"drop\" or \"send\", got {}; using default \"{}\"",
                    KEY_UNDELIVERABLE_ENTRIES, element, DEFAULTS.undeliverableEntries().json());
            return DEFAULTS.undeliverableEntries();
        }
        return mode;
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
        object.addProperty(KEY_UNDELIVERABLE_ENTRIES, undeliverableEntries.json());
        object.addProperty(KEY_BUNDLE_CHUNKS, bundleChunks);
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
