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

    @Test
    fun recognizesOnlyExplicitContactSectionLabels() {
        assertEquals(true, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "contact", "灵儿"))
        assertEquals(true, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "CONTACT", "灵儿｜微信号尾号 1234"))
        assertEquals(false, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "group", "东建群 包含：灵儿"))
        assertEquals(false, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "chat_history", "@灵儿"))
        assertEquals(false, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "web_search", "灵儿"))
        assertEquals(false, SendMessageInAppTool.isExactContactSectionCandidate("灵儿", "contact", "灵儿电竞"))
    }

    @Test
    fun filtersPageWideTextHitsBeforeCountingContacts() {
        val candidates = listOf(
            SendMessageInAppTool.ContactChoice("contact", "灵儿", 540, 420),
            SendMessageInAppTool.ContactChoice("group", "群成员：灵儿", 540, 690),
            SendMessageInAppTool.ContactChoice("chat_history", "聊天记录包含灵儿", 540, 930),
            SendMessageInAppTool.ContactChoice("web_search", "搜索灵儿", 540, 1180),
        )

        assertEquals(
            listOf(candidates.first()),
            SendMessageInAppTool.exactContactSectionCandidates("灵儿", candidates),
        )
    }

    @Test
    fun asksOnlyWhenTwoRealContactRowsRemain() {
        val candidates = listOf(
            SendMessageInAppTool.ContactChoice("contact", "灵儿｜微信号尾号 1234", 540, 420),
            SendMessageInAppTool.ContactChoice("contact", "灵儿｜地区：广州", 540, 590),
            SendMessageInAppTool.ContactChoice("chat_history", "灵儿", 540, 900),
        )

        assertEquals(2, SendMessageInAppTool.exactContactSectionCandidates("灵儿", candidates).size)
    }

    @Test
    fun doesNotCountRepeatedHighlightAtTheSameRowCentre() {
        val candidates = listOf(
            SendMessageInAppTool.ContactChoice("contact", "灵儿", 540, 420),
            SendMessageInAppTool.ContactChoice("CONTACT", "灵儿｜高亮文字", 540, 420),
        )

        assertEquals(1, SendMessageInAppTool.exactContactSectionCandidates("灵儿", candidates).size)
    }
}
