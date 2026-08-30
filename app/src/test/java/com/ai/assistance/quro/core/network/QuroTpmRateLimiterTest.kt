package com.ai.assistance.quro.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuroTpmRateLimiterTest {
    @Test
    fun `reserves requests while rolling total stays below safe limit`() {
        val limiter = QuroTpmRateLimiter(defaultLimit = 100, safetyRatio = 0.9, windowMs = 60_000)
        assertEquals(0L, limiter.reserveOrDelay(1_000, 40))
        assertEquals(0L, limiter.reserveOrDelay(2_000, 50))
        assertEquals(90, limiter.usedTokens(2_000))
    }

    @Test
    fun `delays request until enough old charges expire`() {
        val limiter = QuroTpmRateLimiter(defaultLimit = 100, safetyRatio = 0.9, windowMs = 60_000)
        limiter.reserveOrDelay(1_000, 60)
        limiter.reserveOrDelay(2_000, 30)
        assertEquals(59_250L, limiter.reserveOrDelay(2_000, 40))
    }

    @Test
    fun `server cooldown blocks otherwise affordable request`() {
        val limiter = QuroTpmRateLimiter(defaultLimit = 100, safetyRatio = 0.9, windowMs = 60_000)
        limiter.blockFor(nowMs = 5_000, delayMs = 3_500)
        assertEquals(3_500L, limiter.reserveOrDelay(5_000, 10))
        assertEquals(0L, limiter.reserveOrDelay(8_500, 10))
    }

    @Test
    fun `observed smaller server limit tightens future reservations`() {
        val limiter = QuroTpmRateLimiter(defaultLimit = 1_000, safetyRatio = 0.9, windowMs = 60_000)
        limiter.reserveOrDelay(1_000, 80)
        limiter.updateLimit(100)
        assertTrue(limiter.reserveOrDelay(2_000, 20) > 0L)
    }
}
