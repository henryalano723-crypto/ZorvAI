package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuroLlmRetryTest {
    @Test
    fun `429 honors response retry duration with safety margin`() {
        assertEquals(10_870L, retryDelayMillis(429, null, "Please try again in 10.62s.", 1))
    }

    @Test
    fun `429 header takes precedence when it asks for longer wait`() {
        assertEquals(12_250L, retryDelayMillis(429, "12", "try again in 3s", 1))
    }

    @Test
    fun `429 without hint uses conservative wait`() {
        assertTrue(retryDelayMillis(429, null, "rate limit", 1) >= 10_000L)
    }

    @Test
    fun `server errors keep short exponential retry`() {
        assertEquals(1_600L, retryDelayMillis(503, null, "", 2))
    }
}
