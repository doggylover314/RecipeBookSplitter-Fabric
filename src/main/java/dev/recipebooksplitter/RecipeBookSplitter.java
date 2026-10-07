package dev.recipebooksplitter;

import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.split.RecipeBookSendInterceptor;
import dev.recipebooksplitter.util.Sizes;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecipeBookSplitter implements DedicatedServerModInitializer {
    public static final String CONFIG_FILE_NAME = "recipebooksplitter.json";
    public static final Logger LOGGER = LoggerFactory.getLogger("RecipeBookSplitter");

    private static volatile SplitterConfig config = SplitterConfig.DEFAULTS;

    public static SplitterConfig config() {
        return config;
    }

    /** Also used by tests. */
    public static void setConfig(SplitterConfig newConfig) {
        config = Objects.requireNonNull(newConfig);
    }

    @Override
    public void onInitializeServer() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        setConfig(SplitterConfig.loadOrCreate(file, LOGGER));
        SplitterConfig loaded = config();
        LOGGER.info("[RecipeBookSplitter] loaded: maxChunkBytes={} ({}), logSplits={}, logOversizedPackets={}, undeliverableEntries={}, bundleChunks={}",
                Sizes.bytes(loaded.maxChunkBytes()), Sizes.mib(loaded.maxChunkBytes()), loaded.logSplits(), loaded.logOversizedPackets(),
                loaded.undeliverableEntries().json(), loaded.bundleChunks());
        RecipeBookSendInterceptor.logStartup();
    }
}
