package dev.recipebooksplitter.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SizesTest {
    @Test
    void bytesUseGroupingSeparators() {
        assertEquals("9,227,553", Sizes.bytes(9_227_553));
        assertEquals("0", Sizes.bytes(0));
        assertEquals("999", Sizes.bytes(999));
    }

    @Test
    void mibUsesBinaryUnits() {
        assertEquals("8.8 MiB", Sizes.mib(9_227_553));
        assertEquals("9.0 MiB", Sizes.mib(9_437_184));
        assertEquals("0.0 MiB", Sizes.mib(0));
        assertEquals("1.0 MiB", Sizes.mib(1_048_576));
    }
}
