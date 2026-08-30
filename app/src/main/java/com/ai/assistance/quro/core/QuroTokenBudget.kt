package com.ai.assistance.quro.core

import kotlin.math.ceil

/**
 * 无 tokenizer 依赖的保守输入 token 估算。
 *
 * 旧实现统一使用 chars/4，会把中文、日文、韩文和 emoji 严重低估。这里把 ASCII
 * 近似按 4 字符/token 计算，非 ASCII 码点按 1 token 计算。它不是计费级 tokenizer，
 * 但适合在请求发出前做安全预算，宁可留少量余量，也不要把中文上下文放大约四倍。
 */
internal fun estimateLlmTokens(text: String?): Int {
    if (text.isNullOrEmpty()) return 0
    var ascii = 0
    var nonAscii = 0
    text.codePoints().forEach { cp ->
        if (cp <= 0x7f) ascii++ else nonAscii++
    }
    return ceil(ascii / 4.0).toInt() + nonAscii
}

/** OpenAI chat JSON 每条消息的 role/content/key/逗号等固定协议开销保守预留。 */
internal const val CHAT_MESSAGE_PROTOCOL_TOKENS = 28

/** 用于历史裁剪的消息总估算，包含正文之外此前漏掉的 ID、工具名、reasoning 和协议字段。 */
internal fun estimateChatMessageTokens(message: QuroChatMessage): Int {
    var total = CHAT_MESSAGE_PROTOCOL_TOKENS + estimateLlmTokens(message.content) + estimateLlmTokens(message.reasoning)
    total += estimateLlmTokens(message.toolCallId) + estimateLlmTokens(message.toolName)
    message.toolCalls.orEmpty().forEach { call ->
        total += 24 + estimateLlmTokens(call.id) + estimateLlmTokens(call.name) + estimateLlmTokens(call.arguments)
    }
    // 图片按一次中等分辨率视觉输入预留；真实计费由模型按像素/细节决定，不按 base64 字符数计算。
    total += message.attachments.orEmpty().count { it.type == "image" } * 1_100
    return total
}

/** API tools 字段预算；按真正发出的 name + description + parameters 估算并预留 JSON 结构。 */
internal fun estimateToolSpecsTokens(specs: List<QuroToolSpec>): Int = specs.sumOf { spec ->
    32 + estimateLlmTokens(spec.name) + estimateLlmTokens(spec.description) + estimateLlmTokens(spec.parametersJson)
}
