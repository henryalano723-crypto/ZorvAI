package com.ai.assistance.quro.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMessageIntentCompilerTest {
    @Test
    fun compilesChineseSearchTypeAndSendAsOneMessageTransaction() {
        val intent = AppMessageIntentCompiler.parse("打开微信搜索文件传输助手，然后输入“测试内容”并点击发送")
        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "文件传输助手", "测试内容", confirmSend = true),
            intent,
        )
        val rewritten = AppMessageIntentCompiler.rewriteFirstStep(
            calls = listOf(QuroToolCall(name = "search_in_app", arguments = "{}")),
            intent = intent,
            alreadyDispatched = false,
        )
        assertEquals("send_message_in_app", rewritten.single().name)
        val args = JSONObject(rewritten.single().arguments)
        assertEquals("微信", args.getString("app_name"))
        assertEquals("文件传输助手", args.getString("contact"))
        assertEquals("测试内容", args.getString("message"))
        assertEquals(true, args.getBoolean("confirm_send"))

        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "文件传输助手", "收到", confirmSend = true),
            AppMessageIntentCompiler.parse("在微信中搜索文件传输助手，然后输入收到并发送信息"),
        )
    }

    @Test
    fun compilesNaturalDirectSendCommand() {
        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "灵儿", "你好", confirmSend = true),
            AppMessageIntentCompiler.parse("打开微信给灵儿发送 你好"),
        )
        assertEquals(
            AppMessageIntentCompiler.Intent("QQ", "张三", "收到", confirmSend = true),
            AppMessageIntentCompiler.parse("用QQ给张三发消息：收到"),
        )
    }

    @Test
    fun compilesNaturalSearchThenSendVoiceCommand() {
        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "灵儿", "你好", confirmSend = true),
            AppMessageIntentCompiler.parse("打开微信搜索灵儿发送你好"),
        )
        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "灵儿", "你好", confirmSend = true),
            AppMessageIntentCompiler.parse("在微信里搜灵儿然后发你好"),
        )
    }

    @Test
    fun recognizesFlexibleNaturalSendWordingForTheMessageToolGate() {
        assertEquals(true, AppMessageIntentCompiler.hasExplicitSend("打开微信跟灵儿说你好"))
        assertEquals(
            AppMessageIntentCompiler.Intent("微信", "灵儿", "你好", confirmSend = true),
            AppMessageIntentCompiler.parse("打开微信跟灵儿说你好"),
        )
    }

    @Test
    fun onlyCompleteStructuredMessageStateKeepsVisualTransactionOpen() {
        assertEquals(
            true,
            AppMessageIntentCompiler.isVisualContinuation(
                """{"workflow":"message_send","status":"needs_visual","transaction_id":"tx-1","stage":"select_contact"}""",
            ),
        )
        assertEquals(
            false,
            AppMessageIntentCompiler.isVisualContinuation(
                """{"workflow":"message_send","status":"needs_visual","stage":"select_contact"}""",
            ),
        )
        assertEquals(
            false,
            AppMessageIntentCompiler.isVisualContinuation("❌ [选择联系人] 未确认进入会话"),
        )
        assertEquals(
            false,
            AppMessageIntentCompiler.isVisualContinuation(
                """{"workflow":"message_send","status":"needs_visual","transaction_id":"tx-1","stage":"unknown"}""",
            ),
        )
    }

    @Test
    fun pureSearchOrDraftDoesNotAuthorizeSending() {
        assertNull(AppMessageIntentCompiler.parse("打开微信搜索文件传输助手"))
        assertNull(AppMessageIntentCompiler.parse("打开微信搜索文件传输助手然后输入测试内容"))
    }

    @Test
    fun negatedSendLanguageNeverAuthorizesAMessageTransaction() {
        val readOnlyContactAudit =
            "打开微信，查看当前搜索结果。只统计联系人分区中与搜索词完全一致的联系人。" +
                "不要点击任何结果，不要进入聊天，不要发送任何消息。"

        assertEquals(false, AppMessageIntentCompiler.hasExplicitSend(readOnlyContactAudit))
        assertNull(AppMessageIntentCompiler.parse(readOnlyContactAudit))
        assertEquals(false, AppMessageIntentCompiler.hasExplicitSend("只输入草稿，不要点击发送"))
        assertNull(AppMessageIntentCompiler.parse("打开微信搜索灵儿，不要发送任何消息"))
    }

    @Test
    fun matchingPendingSearchHandsCurrentResultsToMessageTransaction() {
        val intent = AppMessageIntentCompiler.Intent("微信", "文件传输助手", "测试内容", confirmSend = true)
        val searchCall = QuroToolCall(
            name = "search_in_app",
            arguments = JSONObject()
                .put("app_name", "微信")
                .put("query", "文件传输助手")
                .toString(),
        )
        assertEquals(
            true,
            AppMessageIntentCompiler.isMatchingSearchResultsHandoff(
                searchCall,
                "⚠️ [SEARCH_QUERY_PENDING_VISUAL_VERIFICATION] pending",
                intent,
            ),
        )

        val rewritten = AppMessageIntentCompiler.rewriteFirstStep(
            calls = listOf(QuroToolCall(name = "visual_analysis", arguments = "{}")),
            intent = intent,
            alreadyDispatched = false,
            searchResultsReady = true,
        )
        assertEquals("send_message_in_app", rewritten.single().name)
        assertEquals(true, JSONObject(rewritten.single().arguments).getBoolean("_search_results_ready"))
    }

    @Test
    fun unrelatedSearchCannotOpenMessageHandoff() {
        val intent = AppMessageIntentCompiler.Intent("微信", "文件传输助手", "测试内容", confirmSend = true)
        val wrongContact = QuroToolCall(
            name = "search_in_app",
            arguments = JSONObject()
                .put("app_name", "微信")
                .put("query", "其他联系人")
                .toString(),
        )
        assertEquals(
            false,
            AppMessageIntentCompiler.isMatchingSearchResultsHandoff(
                wrongContact,
                "⚠️ [SEARCH_QUERY_PENDING_VISUAL_VERIFICATION] pending",
                intent,
            ),
        )
    }
}
