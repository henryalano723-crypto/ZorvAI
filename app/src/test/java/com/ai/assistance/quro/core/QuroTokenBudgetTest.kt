package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuroTokenBudgetTest {
    @Test
    fun `ascii is estimated near four chars per token`() {
        assertEquals(4, estimateLlmTokens("abcdefghijklmnop"))
    }

    @Test
    fun `chinese is not underestimated as chars divided by four`() {
        val text = "打开美团找到搜索栏输入月饼"
        assertTrue(estimateLlmTokens(text) >= text.length)
    }

    @Test
    fun `mixed content accounts for ascii and chinese separately`() {
        assertEquals(5, estimateLlmTokens("abcd月饼测试"))
    }

    @Test
    fun `message estimate includes protocol overhead omitted by old budget`() {
        val message = QuroChatMessage(role = "user", content = "hello")
        assertTrue(estimateChatMessageTokens(message) >= CHAT_MESSAGE_PROTOCOL_TOKENS + 2)
    }

    @Test
    fun `conversation budget limits many small messages including json overhead`() {
        val store = QuroConversationStore()
        repeat(200) { store.add(QuroMessage(role = "user", content = "message-$it")) }
        val result = store.toLlmMessages(contextWindow = 1_000)
        assertTrue(result.sumOf(::estimateChatMessageTokens) <= 1_000)
        assertTrue(result.size < 40)
    }

    @Test
    fun `automatic visual fallback is one shot without deleting user images`() {
        val store = QuroConversationStore()
        val autoImage = QuroAttachment(
            type = "image", uri = "/tmp/auto.png", name = "auto.png", mime = "image/png", size = 123
        )
        val userImage = QuroAttachment(
            type = "image", uri = "/tmp/user.png", name = "user.png", mime = "image/png", size = 456
        )
        store.add(
            QuroMessage(
                role = "user",
                content = "${AUTO_VISUAL_FALLBACK_PREFIX}1080x432，左上角对应原屏幕 (0,0)]",
                attachments = listOf(autoImage),
                hidden = true,
            )
        )
        store.add(QuroMessage(role = "user", content = "请看这张图", attachments = listOf(userImage)))

        val firstRequest = store.toLlmMessages()
        assertEquals(2, firstRequest.count { !it.attachments.isNullOrEmpty() })
        assertEquals(1, store.discardAutoVisualFallbacks())

        val laterRequest = store.toLlmMessages()
        assertEquals(1, laterRequest.count { !it.attachments.isNullOrEmpty() })
        assertEquals("user.png", laterRequest.single { !it.attachments.isNullOrEmpty() }.attachments!!.single().name)
    }

    @Test
    fun `visual message continuation keeps transaction result and screenshot together`() {
        val store = QuroConversationStore()
        val callId = "call_message_p40_28"
        val transactionId = "transaction-p40-28"
        store.add(QuroMessage(role = "user", content = "打开微信给文件传输助手发送测试内容"))
        store.add(
            QuroMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    QuroToolCall(
                        id = callId,
                        name = "send_message_in_app",
                        arguments = "{\"contact\":\"文件传输助手\"}",
                    )
                ),
                hidden = true,
            )
        )
        store.add(
            QuroMessage(
                role = "tool",
                content = "{\"workflow\":\"message_send\",\"status\":\"needs_visual\"," +
                    "\"stage\":\"select_contact\",\"transaction_id\":\"$transactionId\"}",
                toolCallId = callId,
                toolLabel = "send_message_in_app",
                hidden = true,
            )
        )
        store.add(
            QuroMessage(
                role = "user",
                content = "${AUTO_VISUAL_FALLBACK_PREFIX}1080x2340，微信搜索结果页]",
                attachments = listOf(
                    QuroAttachment(
                        type = "image",
                        uri = "/tmp/wechat-search-results.png",
                        name = "wechat-search-results.png",
                        mime = "image/png",
                        size = 123,
                    )
                ),
                hidden = true,
            )
        )

        val request = store.toLlmMessages(historyRounds = VISUAL_MESSAGE_HISTORY_ROUNDS)

        assertTrue(request.any { message -> message.toolCalls.orEmpty().any { it.id == callId } })
        assertTrue(request.any { it.role == "tool" && it.toolCallId == callId && it.content.contains(transactionId) })
        assertTrue(request.any { it.attachments.orEmpty().any { attachment -> attachment.name == "wechat-search-results.png" } })
    }
}
