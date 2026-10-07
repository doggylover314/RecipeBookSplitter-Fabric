package dev.recipebooksplitter.testutil;

import dev.recipebooksplitter.config.SplitterConfig;

/** Configurations for tests, so a change to the config record does not touch every test. */
public final class TestConfigs {
    private TestConfigs() {}

    /** Splits at {@code maxChunkBytes}, logs splits, does not log oversized packets. */
    public static SplitterConfig budget(int maxChunkBytes) {
        return budget(maxChunkBytes, false);
    }

    public static SplitterConfig budget(int maxChunkBytes, boolean logOversizedPackets) {
        return new SplitterConfig(maxChunkBytes, true, logOversizedPackets);
    }
}
