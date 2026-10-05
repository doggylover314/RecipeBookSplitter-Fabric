package dev.recipebooksplitter.util;

import java.util.Locale;

/** Number formatting for log messages. */
public final class Sizes {
    public static final long MIB = 1_048_576L;

    private Sizes() {}

    /** {@code 9227553} becomes {@code "9,227,553"}. */
    public static String bytes(long bytes) {
        return String.format(Locale.ROOT, "%,d", bytes);
    }

    /** Binary units: {@code 9227553} becomes {@code "8.8 MiB"}. */
    public static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) MIB);
    }
}
