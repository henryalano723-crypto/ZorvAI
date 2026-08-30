package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPageBacktrackPolicyTest {
    @Test
    fun backtrackingIsBoundedAndRequiresExactTargetPackage() {
        assertTrue(SearchPageBacktrackPolicy.mayGoBack(0, "chat.vendor", "chat.vendor"))
        assertTrue(SearchPageBacktrackPolicy.mayGoBack(2, "chat.vendor", "chat.vendor"))
        assertFalse(SearchPageBacktrackPolicy.mayGoBack(3, "chat.vendor", "chat.vendor"))
        assertFalse(SearchPageBacktrackPolicy.mayGoBack(0, "chat.vendor", "launcher.vendor"))
        assertFalse(SearchPageBacktrackPolicy.mayGoBack(0, "chat.vendor", null))
    }

    @Test
    fun activationRetryStopsAsSoonAsBackLeavesTargetApp() {
        assertTrue(SearchPageBacktrackPolicy.mayRetryActivation("chat.vendor", "chat.vendor"))
        assertFalse(SearchPageBacktrackPolicy.mayRetryActivation("chat.vendor", "launcher.vendor"))
        assertFalse(SearchPageBacktrackPolicy.mayRetryActivation("chat.vendor", null))
    }
}
