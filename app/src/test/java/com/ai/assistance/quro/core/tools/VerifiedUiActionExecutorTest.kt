package com.ai.assistance.quro.core.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedUiActionExecutorTest {
    @After
    fun clearCache() = VerifiedUiActionExecutor.clearRouteCache()

    @Test
    fun observationVersionRejectsMissingStaleAndOutOfBoundsCoordinates() {
        val version = VerifiedUiActionExecutor.nextObservationVersion()
        assertTrue(VerifiedUiActionExecutor.acceptsObservation(version, version, 10, 20, 100, 200))
        assertFalse(VerifiedUiActionExecutor.acceptsObservation(version, 0, 10, 20, 100, 200))
        assertFalse(VerifiedUiActionExecutor.acceptsObservation(version, version - 1, 10, 20, 100, 200))
        assertFalse(VerifiedUiActionExecutor.acceptsObservation(version, version, 100, 20, 100, 200))
        assertNotEquals(version, VerifiedUiActionExecutor.nextObservationVersion())
    }

    @Test
    fun safeActionUsesAtMostOneFallbackAndCachesOnlyVerifiedRoute() {
        val dispatches = mutableListOf<VerifiedUiActionExecutor.Route>()
        var verifications = 0
        val result = VerifiedUiActionExecutor.execute(
            cacheKey = "focus",
            retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
            dispatch = { route -> dispatches += route; true },
            verify = { ++verifications == 2 },
        )
        assertTrue(result.verified)
        assertEquals(listOf(VerifiedUiActionExecutor.Route.SHIZUKU, VerifiedUiActionExecutor.Route.ACCESSIBILITY), dispatches)
        assertEquals(VerifiedUiActionExecutor.Route.ACCESSIBILITY, VerifiedUiActionExecutor.cachedRoute("focus"))
    }

    @Test
    fun irreversibleActionNeverRetriesAfterAnUnverifiedDispatch() {
        val dispatches = mutableListOf<VerifiedUiActionExecutor.Route>()
        val result = VerifiedUiActionExecutor.execute(
            cacheKey = "send",
            retrySafety = VerifiedUiActionExecutor.RetrySafety.DISPATCH_ONCE,
            dispatch = { route -> dispatches += route; true },
            verify = { false },
        )
        assertFalse(result.verified)
        assertTrue(result.uncertainDispatch)
        assertEquals(listOf(VerifiedUiActionExecutor.Route.SHIZUKU), dispatches)
    }

    @Test
    fun cachedSuccessStillRunsVerificationAndCanFallBack() {
        VerifiedUiActionExecutor.execute("tap", VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT, { true }, { true })
        var checks = 0
        val result = VerifiedUiActionExecutor.execute(
            "tap",
            VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
            { true },
            { ++checks == 2 },
        )
        assertTrue(result.verified)
        assertEquals(2, checks)
    }

    @Test
    fun requiresTwoConsecutiveChangedFrames() {
        assertFalse(VerifiedUiActionExecutor.hasStableChange(listOf(true, false, true)))
        assertTrue(VerifiedUiActionExecutor.hasStableChange(listOf(false, true, true)))
    }

    @Test
    fun observationModePrefersNodesAndFallsBackToScreenshot() {
        assertEquals("xml_nodes", VerifiedUiActionExecutor.observationMode(true).wire)
        assertEquals("screenshot_visual", VerifiedUiActionExecutor.observationMode(false).wire)
    }

    @Test
    fun inputRequiresVerifiedFocusAndMayBeDispatchedOnlyOnce() {
        assertFalse(VerifiedUiActionExecutor.canDispatchInput(focusVerified = false, alreadyAttempted = false))
        assertTrue(VerifiedUiActionExecutor.canDispatchInput(focusVerified = true, alreadyAttempted = false))
        assertFalse(VerifiedUiActionExecutor.canDispatchInput(focusVerified = true, alreadyAttempted = true))
    }
}
