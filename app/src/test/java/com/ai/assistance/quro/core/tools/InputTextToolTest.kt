package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTextToolTest {
    @Test
    fun inputMatches_requiresActualReadback() {
        assertTrue(InputTextTool.inputMatches("蓝牙耳机", "蓝牙耳机"))
        assertFalse(InputTextTool.inputMatches("蓝牙耳机", ""))
        assertFalse(InputTextTool.inputMatches("蓝牙耳机", "搜索"))
    }

    @Test
    fun inputMatches_normalizesLineEndingsOnly() {
        assertTrue(InputTextTool.inputMatches("第一行\n第二行", "第一行\r\n第二行"))
        assertFalse(InputTextTool.inputMatches("第一行", "第一行 "))
    }

    @Test
    fun noEditableResult_isFailureAndNeverVisualSuccessJson() {
        val result = InputTextTool.noEditableResult(null)

        assertTrue(result.startsWith("❌"))
        assertTrue(result.contains("input_text 未执行"))
        assertFalse(result.startsWith("{"))
        assertFalse(result.contains("\"status\":\"captured\""))
    }

    @Test
    fun searchEntryResult_requiresClickAndReadback() {
        val candidate = SearchTargetResolver.Candidate(
            node = SearchTargetResolver.Node(
                text = "搜索",
                left = 100,
                top = 80,
                right = 900,
                bottom = 180,
                clickable = true,
            ),
            kind = SearchTargetResolver.Kind.SEARCH_ENTRY,
            score = 95,
            reasons = listOf("搜索语义"),
        )

        val result = InputTextTool.noEditableResult(candidate)
        assertTrue(result.startsWith("❌"))
        assertTrue(result.contains("SEARCH_ENTRY"))
        assertTrue(result.contains("重新 read_screen"))
        assertTrue(result.contains("禁止报告任务完成"))
    }
}
