package com.ai.assistance.quro.core

import org.json.JSONObject

/** Deterministic compiler for explicit “open app and search content” instructions. */
internal object AppSearchIntentCompiler {
    data class Intent(val appName: String, val query: String)

    private val patterns = listOf(
        Regex("^\\s*打开\\s*(.{1,30}?)\\s*(?:并|然后)?\\s*(?:搜索|搜|查找)\\s*(.+?)\\s*[。.!！]?$"),
        Regex("^\\s*在\\s*(.{1,30}?)(?:里|中|内)?\\s*(?:搜索|搜|查找)\\s*(.+?)\\s*[。.!！]?$"),
        Regex("^\\s*去\\s*(.{1,30}?)(?:里|中|内)?\\s*(?:搜索|搜|查找)\\s*(.+?)\\s*[。.!！]?$"),
        Regex("^\\s*open\\s+(.{1,30}?)\\s+(?:and\\s+)?search(?:\\s+for)?\\s+(.+?)\\s*[.!]?$", RegexOption.IGNORE_CASE),
    )

    fun parse(userText: String): Intent? {
        val normalized = userText.trim()
        // Search is atomic only when it is the user's final goal. Never compile away an explicit
        // downstream messaging action; QuroAssistant/send_message_in_app must own that transaction.
        if (AppMessageIntentCompiler.hasExplicitSend(normalized)) return null
        for (pattern in patterns) {
            val match = pattern.matchEntire(normalized) ?: continue
            val app = match.groupValues[1].trim().trim('，', ',', '。', '.')
            val query = match.groupValues[2].trim().trim('，', ',', '。', '.', '！', '!')
            if (app.isNotEmpty() && query.isNotEmpty()) return Intent(app, query)
        }
        return null
    }

    /**
     * Small models often expand this intent into launch → blind input. Replace that unsafe first
     * step with the atomic search transaction while preserving ordinary app launches and typing.
     */
    fun rewriteFirstStep(calls: List<QuroToolCall>, intent: Intent?, alreadyDispatched: Boolean): List<QuroToolCall> {
        if (intent == null || alreadyDispatched || calls.isEmpty()) return calls
        if (calls.any { it.name == "search_in_app" }) return calls
        val mistakenSearchChain = calls.any {
            it.name in setOf("launch_app", "search_and_launch_app", "input_text", "read_screen", "find_ui_element")
        }
        if (!mistakenSearchChain) return calls
        return listOf(
            QuroToolCall(
                name = "search_in_app",
                arguments = JSONObject()
                    .put("app_name", intent.appName)
                    .put("query", intent.query)
                    .toString(),
            ),
        )
    }
}
