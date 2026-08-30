package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFingerprintTest {
    @Test
    fun identicalScreensAreNotReportedAsChanged() {
        val pixels = IntArray(24 * 24) { 180 }
        assertFalse(visualFingerprintsDiffer(pixels, pixels.copyOf()))
    }

    @Test
    fun localizedNavigationChangeIsDetected() {
        val before = IntArray(24 * 24) { 180 }
        val after = before.copyOf()
        repeat(40) { after[it] = 40 }
        assertTrue(visualFingerprintsDiffer(before, after))
    }

    @Test
    fun tinyCompressionNoiseIsIgnored() {
        val before = IntArray(24 * 24) { 180 }
        val after = IntArray(24 * 24) { index -> 180 + (index % 3) - 1 }
        assertFalse(visualFingerprintsDiffer(before, after))
    }
}
