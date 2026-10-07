package dev.recipebooksplitter.testutil;

import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;

/** Configurations for tests, so a change to the config record does not touch every test. */
public final class TestConfigs {
    private TestConfigs() {}

    /** Splits at {@code maxChunkBytes}, logs splits, does not log oversized packets, otherwise the defaults. */
    public static SplitterConfig budget(int maxChunkBytes) {
        return budget(maxChunkBytes, false);
    }

    public static SplitterConfig budget(int maxChunkBytes, boolean logOversizedPackets) {
        return of(maxChunkBytes, logOversizedPackets, SplitterConfig.DEFAULTS.undeliverableEntries(), SplitterConfig.DEFAULTS.bundleChunks());
    }

    /** Like {@link #budget(int)}, with the two options that came after 1.0.0 set explicitly. */
    public static SplitterConfig of(int maxChunkBytes, boolean logOversizedPackets, UndeliverableEntries undeliverableEntries, boolean bundleChunks) {
        return new SplitterConfig(maxChunkBytes, true, logOversizedPackets, undeliverableEntries, bundleChunks);
    }
}
