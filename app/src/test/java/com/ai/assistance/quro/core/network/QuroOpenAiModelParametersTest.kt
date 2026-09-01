package com.ai.assistance.quro.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuroOpenAiModelParametersTest {
    @Test
    fun `gpt 5 family uses reasoning compatible chat parameters`() {
        assertTrue(usesOpenAiReasoningChatParameters("gpt-5"))
        assertTrue(usesOpenAiReasoningChatParameters("gpt-5-mini"))
        assertTrue(usesOpenAiReasoningChatParameters("gpt-5.6-terra"))
        assertTrue(usesOpenAiReasoningChatParameters(" GPT-5.6-LUNA "))
    }

    @Test
    fun `o series keeps using reasoning compatible chat parameters`() {
        assertTrue(usesOpenAiReasoningChatParameters("o1"))
        assertTrue(usesOpenAiReasoningChatParameters("o3-mini"))
        assertTrue(usesOpenAiReasoningChatParameters("o4-mini"))
    }

    @Test
    fun `non reasoning model names keep ordinary chat parameters`() {
        assertFalse(usesOpenAiReasoningChatParameters("gpt-4o"))
        assertFalse(usesOpenAiReasoningChatParameters("gpt-4.1-mini"))
        assertFalse(usesOpenAiReasoningChatParameters("deepseek-chat"))
    }
}
