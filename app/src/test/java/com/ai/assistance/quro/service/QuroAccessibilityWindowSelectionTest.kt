package com.ai.assistance.quro.service

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class QuroAccessibilityWindowSelectionTest {

    @Test
    fun focusedTargetApplicationBeatsZorvOverlay() {
        val target = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = true,
            active = true,
            packageName = "com.taobao.taobao",
            selfPackage = "com.ai.assistance.quro",
        )
        val overlay = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            focused = true,
            active = true,
            packageName = "com.ai.assistance.quro",
            selfPackage = "com.ai.assistance.quro",
        )

        assertTrue(target > overlay)
    }

    @Test
    fun focusedZorvApplicationBeatsBackgroundApplication() {
        val zorvMain = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = true,
            active = true,
            packageName = "com.ai.assistance.quro",
            selfPackage = "com.ai.assistance.quro",
        )
        val background = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = false,
            active = false,
            packageName = "com.huawei.android.launcher",
            selfPackage = "com.ai.assistance.quro",
        )

        assertTrue(zorvMain > background)
    }

    @Test
    fun inputMethodNeverBeatsFocusedApplication() {
        val app = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = true,
            active = true,
            packageName = "com.xunmeng.pinduoduo",
            selfPackage = "com.ai.assistance.quro",
        )
        val ime = QuroAccessibilityService.actionableWindowScore(
            type = AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            focused = true,
            active = true,
            packageName = "com.iflytek.inputmethod",
            selfPackage = "com.ai.assistance.quro",
        )

        assertTrue(app > ime)
    }

    @Test
    fun foregroundSelectionRejectsStaleBackgroundApplication() {
        val stale = QuroAccessibilityService.foregroundApplicationWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = false,
            active = false,
            layer = 0,
        )

        assertNull(stale)
    }

    @Test
    fun foregroundSelectionAcceptsActiveAppAndRejectsOverlay() {
        val app = QuroAccessibilityService.foregroundApplicationWindowScore(
            type = AccessibilityWindowInfo.TYPE_APPLICATION,
            focused = true,
            active = true,
            layer = 0,
        )
        val overlay = QuroAccessibilityService.foregroundApplicationWindowScore(
            type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            focused = true,
            active = true,
            layer = 20,
        )

        assertTrue(app != null)
        assertNull(overlay)
    }
}
