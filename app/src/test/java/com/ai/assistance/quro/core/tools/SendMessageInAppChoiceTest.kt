package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendMessageInAppChoiceTest {
    private val labels = listOf("张三｜备注：同事｜微信号尾号 528", "张三｜备注：客户｜地区：佛山")

    @Test
    fun acceptsTypedAndTranscribedOrdinalChoices() {
        assertEquals(0, SendMessageInAppTool.parseContactChoiceIndex("1", labels))
        assertEquals(0, SendMessageInAppTool.parseContactChoiceIndex("第一个", labels))
        assertEquals(1, SendMessageInAppTool.parseContactChoiceIndex("选择第2个", labels))
        assertEquals(1, SendMessageInAppTool.parseContactChoiceIndex("第二个人", labels))
    }

    @Test
    fun rejectsOutOfRangeOrAmbiguousAnswers() {
        assertNull(SendMessageInAppTool.parseContactChoiceIndex("3", labels))
        assertNull(SendMessageInAppTool.parseContactChoiceIndex("张三", labels))
    }
}
