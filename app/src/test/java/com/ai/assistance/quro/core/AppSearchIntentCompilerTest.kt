package com.ai.assistance.quro.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSearchIntentCompilerTest {
    @Test
    fun parsesGenericChineseAppSearch() {
        assertEquals(
            AppSearchIntentCompiler.Intent("微信", "欣玲"),
            AppSearchIntentCompiler.parse("打开微信搜索欣玲"),
        )
        assertEquals(
            AppSearchIntentCompiler.Intent("QQ", "张三"),
            AppSearchIntentCompiler.parse("在QQ中查找张三"),
        )
    }

    @Test
    fun ordinaryLaunchOrTypingIsNotRewritten() {
        assertNull(AppSearchIntentCompiler.parse("打开微信"))
        assertNull(AppSearchIntentCompiler.parse("在输入框写你好"))
    }

    @Test
    fun messagingRequestIsNeverCollapsedIntoPureSearch() {
        assertNull(AppSearchIntentCompiler.parse("打开微信搜索文件传输助手然后发送消息你好"))
        assertNull(AppSearchIntentCompiler.parse("在微信中查找文件传输助手并回复收到"))
        assertNull(AppSearchIntentCompiler.parse("open WeChat and search File Transfer then send a message"))
    }

    @Test
    fun replacesBlindLaunchWithAtomicSearchTransaction() {
        val intent = AppSearchIntentCompiler.parse("打开微信搜索欣玲")
        val rewritten = AppSearchIntentCompiler.rewriteFirstStep(
            listOf(QuroToolCall(name = "search_and_launch_app", arguments = "{\"app_name\":\"微信\"}")),
            intent,
            alreadyDispatched = false,
        )

        assertEquals(1, rewritten.size)
        assertEquals("search_in_app", rewritten.single().name)
        val args = JSONObject(rewritten.single().arguments)
        assertEquals("微信", args.getString("app_name"))
        assertEquals("欣玲", args.getString("query"))
    }

    @Test
    fun compilerRunsOnlyOncePerRequest() {
        val original = listOf(QuroToolCall(name = "read_screen", arguments = "{}"))
        val actual = AppSearchIntentCompiler.rewriteFirstStep(
            original,
            AppSearchIntentCompiler.Intent("微信", "欣玲"),
            alreadyDispatched = true,
        )
        assertEquals(original, actual)
    }
}
