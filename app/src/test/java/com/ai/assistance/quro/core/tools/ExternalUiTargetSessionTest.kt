package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalUiTargetSessionTest {
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
