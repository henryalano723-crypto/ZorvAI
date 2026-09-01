package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExternalUiTargetSessionTest {
    @Test
    fun awaitTrustedSurface_waitsThroughLauncherTransitionUntilZorvIsReady() {
        val launcher = Any()
        val missing = Any()
        val zorv = Any()
        val packages = mapOf(
            launcher to "com.huawei.android.launcher",
            missing to null,
            zorv to "com.ai.assistance.quro",
        )
        val remaining = mutableListOf(missing, zorv)

        val actual = ExternalUiTargetSession.awaitTrustedSurface(
            initial = launcher,
            ownPackage = "com.ai.assistance.quro",
            targetPackage = "com.tencent.mm",
            attempts = 4,
            packageOf = { packages[it] },
            next = { remaining.removeAt(0) },
        )

        assertSame(zorv, actual)
    }

    @Test
    fun awaitTrustedSurface_rejectsStableUnrelatedApp() {
        val unrelated = Any()

        val actual = ExternalUiTargetSession.awaitTrustedSurface(
            initial = unrelated,
            ownPackage = "com.ai.assistance.quro",
            targetPackage = "com.tencent.mm",
            attempts = 3,
            packageOf = { "example.unrelated" },
            next = { unrelated },
        )

        assertNull(actual)
    }

    @Test
    fun awaitTrustedSurface_allowsColdLaunchLongerThanDefaultWindow() {
        val launcher = Any()
        val target = Any()
        var samples = 0

        val actual = ExternalUiTargetSession.awaitTrustedSurface(
            initial = launcher,
            ownPackage = "com.ai.assistance.quro",
            targetPackage = "com.tencent.mm",
            attempts = SendMessageInAppTool.COLD_LAUNCH_SETTLE_ATTEMPTS,
            packageOf = { if (it === target) "com.tencent.mm" else "com.huawei.android.launcher" },
            next = {
                samples += 1
                if (samples >= 28) target else launcher
            },
        )

        assertSame(target, actual)
    }

    @Test
    fun awaitStableSurface_requiresConsecutiveActiveTargetSamples() {
        val target1 = Any()
        val own = Any()
        val target2 = Any()
        val target3 = Any()
        val packages = mapOf(
            target1 to "chat.vendor",
            own to "com.ai.assistance.quro",
            target2 to "chat.vendor",
            target3 to "chat.vendor",
        )
        val remaining = mutableListOf(own, target2, target3)

        val actual = ExternalUiTargetSession.awaitStableSurface(
            initial = target1,
            expectedPackage = "chat.vendor",
            attempts = 4,
            requiredConsecutive = 2,
            packageOf = { packages[it] },
            next = { remaining.removeAt(0) },
        )

        assertSame(target3, actual)
    }

    @Test
    fun awaitStableSurface_rejectsSingleStaleTargetRoot() {
        val staleTarget = Any()
        val own = Any()
        val remaining = mutableListOf(own, own)

        val actual = ExternalUiTargetSession.awaitStableSurface(
            initial = staleTarget,
            expectedPackage = "chat.vendor",
            attempts = 3,
            requiredConsecutive = 2,
            packageOf = { if (it === staleTarget) "chat.vendor" else "com.ai.assistance.quro" },
            next = { remaining.removeAt(0) },
        )

        assertNull(actual)
    }

    @Test
    fun parseTaskIdentity_returnsExactTargetTaskInsteadOfMostRecentTask() {
        val dump = """
            * Recent #0: Task{aaa #500 type=standard A=100:example.other U=0}
              taskId=500 rootTaskId=500
              topActivity={example.other/example.other.Home}
            * Recent #1: Task{bbb #460 type=standard A=101:chat.vendor U=0}
              taskId=460 rootTaskId=460
              topActivity={chat.vendor/chat.vendor.Conversation}
        """.trimIndent()

        val actual = ExternalUiTargetSession.parseTaskIdentity(dump, "chat.vendor")

        assertEquals(460, actual?.taskId)
        assertEquals("chat.vendor/chat.vendor.Conversation", actual?.topActivity)
    }

    @Test
    fun parseTaskIdentity_rejectsSubstringAndUnsafePackage() {
        val dump = """
            * Recent #0: Task{aaa #12 type=standard A=100:chat.vendor.plus U=0}
              taskId=12 rootTaskId=12
              topActivity={chat.vendor.plus/chat.vendor.plus.Home}
        """.trimIndent()

        assertNull(ExternalUiTargetSession.parseTaskIdentity(dump, "chat.vendor"))
        assertNull(ExternalUiTargetSession.parseTaskIdentity(dump, "chat.vendor;id"))
    }

    @Test
    fun parseTaskIdentity_usesActivityFallbackWithoutBraceRegex() {
        val dump = """
            * Recent #0: Task{aaa #73 type=standard A=100:chat.vendor U=0}
              taskId=73 rootTaskId=73
              topActivity=missing
              mActivityComponent=chat.vendor/chat.vendor.Home
        """.trimIndent()

        val actual = ExternalUiTargetSession.parseTaskIdentity(dump, "chat.vendor")

        assertEquals(73, actual?.taskId)
        assertEquals("chat.vendor/chat.vendor.Home", actual?.topActivity)
    }
}
