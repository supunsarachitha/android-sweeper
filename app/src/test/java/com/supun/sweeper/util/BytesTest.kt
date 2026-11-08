package com.supun.sweeper.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {
    @Test fun bytesBelowOneKb() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
    }

    @Test fun decimalUnitsLikeAndroidSettings() {
        assertEquals("1.00 KB", formatBytes(1_000))
        assertEquals("1.50 MB", formatBytes(1_500_000))
        assertEquals("64.0 GB", formatBytes(64_000_000_000))
        assertEquals("128 GB", formatBytes(128_000_000_000))
        assertEquals("2.00 TB", formatBytes(2_000_000_000_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeIsRejected() {
        formatBytes(-1)
    }

    @Test fun usedFractionIsClamped() {
        assertEquals(0f, usedFraction(5, 0), 0f)
        assertEquals(0.25f, usedFraction(25, 100), 0.0001f)
        assertEquals(1f, usedFraction(200, 100), 0f)
    }
}
