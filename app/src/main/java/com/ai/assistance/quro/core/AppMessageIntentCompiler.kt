package com.ai.assistance.quro.core

import org.json.JSONObject

/**
 * Deterministic compiler for the common one-shot task:
 * open/search a contact, type a body, and explicitly send it.
 *
 * This runs before the pure-search compiler so a messaging task can never be reduced to
 * search_in_app merely because the model selected that lower-level tool first.
 */
internal object AppMessageIntentCompiler {
    private const val SEARCH_RESULTS_READY_ARG = "_search_results_ready"
    private val visualStages = setOf(
        "verify_search_field",
        "select_contact",
        "verify_conversation",
        "verify_draft",
        "verify_sent",
    )

    data class Intent(
        val appName: String,
        val contact: String,
        val message: String,
        val confirmSend: Boolean,
    )

    private val explicitSend = Regex(
        "(?:发送(?:消息|信息)?|发出|发消息|发信息|发给|给.{1,40}?发|(?:打开|去|在|用).{1,30}?(?:跟|对).{1,40}?说|点击发送|按发送|回复|转发|send\\s+(?:a\\s+)?message)",
        RegexOption.IGNORE_CASE,
    )
    private val searchWord = Regex("(?:搜索|搜|查找)")
    private val inputWord = Regex("(?:然后|并|，|,)?\\s*(?:点击[^，,。.!！]{0,20})?\\s*(?:输入|填写|写入)")
    private val trailingSend = Regex(
        "\\s*(?:然后|并|，|,)?\\s*(?:点击|按)?\\s*(?:发送(?:消息|信息)?|发出|发消息|发信息)\\s*[。.!！]?$",
    )
    private val directSend = Regex(
        "^\\s*(?:打开|去|在|用)?\\s*(.{1,30}?)(?:里|中|内)?\\s*(?:给|跟|对)\\s*(.{1,40}?)\\s*" +
            "(?:发送(?:消息|信息)?|发消息|发信息|发|说)\\s*[：:]?\\s*(.+?)\\s*[。.!！]?$",
    )
    private val searchThenSend = Regex(
        "^\\s*(?:打开|去|在|用)?\\s*(.{1,30}?)(?:里|中|内)?\\s*(?:搜索|搜|查找)\\s*(.{1,40}?)\\s*" +
            "(?:然后|并|再)?\\s*(?:发送(?:消息|信息)?|发消息|发信息|发|说)\\s*[：:]?\\s*(.+?)\\s*[。.!！]?$",
    )

    fun hasExplicitSend(userText: String): Boolean = explicitSend.containsMatchIn(userText)

    /** Only this structured marker may keep a message turn open for another visual step. */
    fun isVisualContinuation(toolResult: String): Boolean {
        val json = runCatching { JSONObject(toolResult) }.getOrNull() ?: return false
        val stage = json.optString("stage")
        return json.optString("workflow") == "message_send" &&
            json.optString("status") == "needs_visual" &&
            json.optString("transaction_id").isNotBlank() &&
            stage in visualStages
    }

    fun parse(userText: String): Intent? {
        val text = userText.trim()
        if (!hasExplicitSend(text)) return null
        directSend.matchEntire(text)?.let { match ->
            val app = match.groupValues[1].trim().trimEnd('里', '中', '内').trim()
            val contact = match.groupValues[2].trim().trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
            val message = match.groupValues[3].trim().trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
            if (app.isNotEmpty() && contact.isNotEmpty() && message.isNotEmpty()) {
                return Intent(app, contact, message, confirmSend = true)
            }
        }
        searchThenSend.matchEntire(text)?.let { match ->
            val app = match.groupValues[1].trim().trimEnd('里', '中', '内').trim()
            val contact = match.groupValues[2].trim().trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
            val message = match.groupValues[3].trim().trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
            if (app.isNotEmpty() && contact.isNotEmpty() && message.isNotEmpty()) {
                return Intent(app, contact, message, confirmSend = true)
            }
        }
        val search = searchWord.find(text) ?: return null
        val input = inputWord.find(text, search.range.last + 1) ?: return null
        val rawApp = text.substring(0, search.range.first)
            .trim()
            .removePrefix("打开")
            .removePrefix("去")
            .removePrefix("在")
            .trim()
            .removeSuffix("点击")
            .trimEnd('里', '中', '内')
            .trim()
        val contact = text.substring(search.range.last + 1, input.range.first)
            .trim()
            .trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
        val afterInput = text.substring(input.range.last + 1).trim()
        val sendSuffix = trailingSend.find(afterInput) ?: return null
        val message = afterInput.substring(0, sendSuffix.range.first)
            .trim()
            .trim('，', ',', '。', '.', '！', '!', '“', '”', '"', '\'')
        if (rawApp.isEmpty() || contact.isEmpty() || message.isEmpty()) return null
        return Intent(rawApp, contact, message, confirmSend = true)
    }

    fun rewriteFirstStep(
        calls: List<QuroToolCall>,
        intent: Intent?,
        alreadyDispatched: Boolean,
        searchResultsReady: Boolean = false,
    ): List<QuroToolCall> {
        if (intent == null || alreadyDispatched || calls.isEmpty()) return calls
        if (calls.any { it.name == "send_message_in_app" }) {
            return if (searchResultsReady) calls.map { call ->
                if (call.name == "send_message_in_app") call.withSearchResultsReady() else call
            } else {
                calls
            }
        }
        val lowerLevelAttempt = calls.any {
            it.name in setOf(
                "search_in_app", "activate_app_search", "launch_app", "search_and_launch_app",
                "read_screen", "find_ui_element", "tap_screen", "input_text", "paste_focused_text",
                "screenshot", "visual_analysis", "scroll_screen", "swipe_screen",
            )
        }
        if (!lowerLevelAttempt) return calls
        val sendCall = QuroToolCall(
            name = "send_message_in_app",
            arguments = JSONObject()
                .put("app_name", intent.appName)
                .put("contact", intent.contact)
                .put("message", intent.message)
                .put("confirm_send", intent.confirmSend)
                .toString(),
        )
        return listOf(
            if (searchResultsReady) sendCall.withSearchResultsReady() else sendCall,
        )
    }

    fun isMatchingSearchResultsHandoff(
        call: QuroToolCall,
        result: String,
        intent: Intent?,
    ): Boolean {
        if (intent == null || call.name != "search_in_app") return false
        if (!result.contains("[SEARCH_QUERY_PENDING_VISUAL_VERIFICATION]")) return false
        val args = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return false
        return args.optString("app_name").trim().equals(intent.appName, ignoreCase = true) &&
            args.optString("query").trim() == intent.contact
    }

    private fun QuroToolCall.withSearchResultsReady(): QuroToolCall {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        args.put(SEARCH_RESULTS_READY_ARG, true)
        return copy(arguments = args.toString())
    }
}
