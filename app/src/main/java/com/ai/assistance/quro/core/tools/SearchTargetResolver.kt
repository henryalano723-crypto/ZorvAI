package com.ai.assistance.quro.core.tools

/** Pure Kotlin ranking/selection logic shared by accessibility search and text input tools. */
internal object SearchTargetResolver {
    enum class Kind { EDITABLE_FIELD, SEARCH_ENTRY }

    data class Node(
        val text: String = "",
        val hint: String = "",
        val description: String = "",
        val resourceId: String = "",
        val className: String = "",
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val enabled: Boolean = true,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = left + width / 2
        val centerY: Int get() = top + height / 2
        val hasArea: Boolean get() = width > 0 && height > 0
    }

    data class Candidate(
        val node: Node,
        val kind: Kind,
        val score: Int,
        val reasons: List<String>,
    )

    enum class SelectionStatus { MATCH, NOT_FOUND, AMBIGUOUS }

    data class EditableSelection(
        val status: SelectionStatus,
        val index: Int? = null,
        val candidateIndexes: List<Int> = emptyList(),
    )

    private val searchIntentTerms = listOf(
        "搜索", "搜一搜", "查找", "search", "searchbox", "searchbar", "searchfield", "magnifier", "放大镜",
    )
    private val searchSemanticTerms = listOf(
        "搜索", "搜一搜", "查找", "search", "searchbox", "searchbar", "searchfield",
        "searchview", "searchinput", "query", "keyword", "magnifier", "放大镜",
    )
    private val messageTerms = listOf(
        "message", "chat", "reply", "comment", "composer", "send", "聊天", "消息", "回复", "评论", "发送",
    )
    private val auxiliarySearchTerms = listOf(
        "语音搜索", "语音", "voice", "图片搜索", "图片", "image", "拍照", "相机", "camera", "扫一扫", "scan",
    )

    fun isSearchIntent(query: String): Boolean {
        val normalized = normalize(query)
        return normalized.isNotEmpty() && searchIntentTerms.any { normalized.contains(normalize(it)) }
    }

    fun rank(nodes: List<Node>, allowGeometryFallback: Boolean = true): List<Candidate> {
        if (nodes.isEmpty()) return emptyList()
        val screenWidth = nodes.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val screenHeight = nodes.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1

        return nodes.mapNotNull { node ->
            if (!node.enabled || !node.hasArea) return@mapNotNull null
            val semanticFields = listOf(node.text, node.hint, node.description, node.resourceId)
            val normalizedFields = semanticFields.map(::normalize)
            val semanticHits = searchSemanticTerms.filter { term ->
                val needle = normalize(term)
                normalizedFields.any { it.contains(needle) }
            }
            val combined = normalize(
                semanticFields.joinToString(" ") + " " + node.className,
            )
            val isMessageField = messageTerms.any { combined.contains(normalize(it)) }
            val isTextField = node.editable || normalize(node.className).let {
                it.contains("edittext") || it.contains("textfield")
            }
            // A single-node snapshot may not contain the root bounds; retain an absolute
            // top-band fallback while preferring relative screen geometry when available.
            val inUpperArea = node.centerY <= (screenHeight * 0.58).toInt() || node.top <= 400
            val isWideField = node.width >= (screenWidth * 0.45).toInt()
            val hasNoOrdinaryLabel = listOf(node.text, node.hint, node.description).all { it.isBlank() }
            val hasSearchResourceId = searchSemanticTerms.any { term ->
                normalize(node.resourceId).contains(normalize(term))
            }
            val looksLikeTopSearchContainer = !isTextField && node.clickable && isWideField &&
                (node.top <= 420 || node.top <= (screenHeight * 0.22).toInt()) &&
                node.height in 40..300 && !isMessageField && (hasNoOrdinaryLabel || hasSearchResourceId)
            val geometryFallback = allowGeometryFallback &&
                ((isTextField && inUpperArea && isWideField && !isMessageField) || looksLikeTopSearchContainer)

            if (semanticHits.isEmpty() && !geometryFallback) return@mapNotNull null

            val reasons = mutableListOf<String>()
            var score = 0
            if (semanticHits.isNotEmpty()) {
                score += 70 + semanticHits.distinct().size.coerceAtMost(3) * 5
                reasons += "搜索语义=${semanticHits.distinct().take(3).joinToString("/")}"
            }
            if (isTextField) {
                score += 35
                reasons += "可编辑"
            }
            if (node.clickable) {
                score += 12
                reasons += "可点击"
            }
            if (inUpperArea) {
                score += 8
                reasons += "屏幕上部"
            }
            if (isWideField) score += 5
            if (geometryFallback && semanticHits.isEmpty()) {
                score += if (looksLikeTopSearchContainer) 70 else 15
                reasons += if (looksLikeTopSearchContainer) "顶部宽搜索容器兜底" else "顶部宽输入框兜底"
            }
            val isAuxiliarySearch = auxiliarySearchTerms.any { combined.contains(normalize(it)) }
            if (isAuxiliarySearch) {
                score -= 100
                reasons += "排除辅助搜索入口"
            }
            val compactLabel = normalize(listOf(node.text, node.hint, node.description).joinToString(""))
            val isNarrowSubmit = compactLabel in setOf("搜索", "search") &&
                node.width < (screenWidth * 0.35).toInt() && !isTextField
            if (isNarrowSubmit) {
                score -= 110
                reasons += "排除窄提交按钮"
            }
            if (isMessageField) score -= 55
            if (node.centerY > (screenHeight * 0.72).toInt()) score -= 20

            val kind = if (isTextField) Kind.EDITABLE_FIELD else Kind.SEARCH_ENTRY
            Candidate(node, kind, score, reasons)
        }.filter { it.score > 0 }
            .distinctBy { listOf(it.kind, it.node.left, it.node.top, it.node.right, it.node.bottom) }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { if (it.kind == Kind.EDITABLE_FIELD) 0 else 1 }
                    .thenBy { it.node.top },
            )
    }

    fun selectEditable(
        nodes: List<Node>,
        targetResourceId: String? = null,
        targetX: Int? = null,
        targetY: Int? = null,
        hint: String? = null,
        targetText: String? = null,
        targetDescription: String? = null,
    ): EditableSelection {
        val editable = nodes.withIndex().filter { (_, node) -> node.enabled && node.editable && node.hasArea }
        if (editable.isEmpty()) return EditableSelection(SelectionStatus.NOT_FOUND)

        fun match(candidates: List<IndexedValue<Node>>): EditableSelection = when (candidates.size) {
            0 -> EditableSelection(SelectionStatus.NOT_FOUND)
            1 -> EditableSelection(SelectionStatus.MATCH, candidates.single().index)
            else -> EditableSelection(
                SelectionStatus.AMBIGUOUS,
                candidateIndexes = candidates.map { it.index },
            )
        }

        targetResourceId.nonBlank()?.let { wanted ->
            val normalizedWanted = normalize(wanted)
            return match(editable.filter { (_, node) ->
                val full = normalize(node.resourceId)
                full == normalizedWanted || full.endsWith(normalizedWanted)
            })
        }
        if (targetX != null && targetY != null) {
            val containing = editable.filter { (_, node) ->
                targetX in node.left..node.right && targetY in node.top..node.bottom
            }.sortedBy { (_, node) -> node.width.toLong() * node.height }
            return if (containing.isEmpty()) EditableSelection(SelectionStatus.NOT_FOUND)
            else EditableSelection(SelectionStatus.MATCH, containing.first().index)
        }
        hint.nonBlank()?.let { wanted ->
            return match(editable.filter { (_, node) -> node.hint.contains(wanted, ignoreCase = true) })
        }
        targetDescription.nonBlank()?.let { wanted ->
            return match(editable.filter { (_, node) -> node.description.contains(wanted, ignoreCase = true) })
        }
        targetText.nonBlank()?.let { wanted ->
            return match(editable.filter { (_, node) -> node.text == wanted })
        }
        return match(editable)
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\s_\\-.:/]+"), "")
}
