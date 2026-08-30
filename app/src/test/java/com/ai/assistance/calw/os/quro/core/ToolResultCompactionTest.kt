package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultCompactionTest {
    @Test
    fun immediateCompaction_usesPerToolCaps() {
        val results = listOf(
            QuroToolResult("read_screen", "屏".repeat(8_000)),
            QuroToolResult("find_ui_element", "找".repeat(8_000)),
            QuroToolResult("terminal_exec", "终".repeat(8_000)),
        )

        val compacted = compactImmediateToolResults(results)

        assertEquals(3, compacted.size)
        assertTrue(compacted[0].result.length < compacted[1].result.length)
        assertTrue(compacted[1].result.length < compacted[2].result.length)
        assertTrue(compacted.sumOf { it.result.length } <= TOOL_RESULTS_ROUND_CAP + 600)
        assertTrue(compacted.all { it.result.contains("工具输出过长已截断") })
    }

    @Test
    fun immediateCompaction_limitsCombinedParallelResults() {
        val results = (1..8).map { QuroToolResult("http_request", "x".repeat(3_500)) }
        val compacted = compactImmediateToolResults(results)

        assertTrue(compacted.sumOf { it.result.length } <= TOOL_RESULTS_ROUND_CAP + 1_200)
        assertTrue(compacted.all { it.result.length < 2_000 })
    }
}
