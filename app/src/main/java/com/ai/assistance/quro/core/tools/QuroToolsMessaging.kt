package com.ai.assistance.quro.core.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.privilege.QuroShizukuBridge
import com.ai.assistance.quro.service.QuroAiKeyboardService
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Activate the current app's top-level search without package rules or fixed coordinates. */
class ActivateAppSearchTool : QuroTool {
    override val name = "activate_app_search"
    override val description =
        "激活当前目标应用首页顶部的全局搜索。优先无障碍语义；节点树为空时本地截图识别放大镜，明确排除加号。" +
            "候选不唯一或点击后未出现搜索编辑框/输入法窗口时停止。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"
        val root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ 无法恢复目标应用现场，未执行搜索点击"
        val nodes = snapshotVisibleNodes(root)
        val ranked = SearchTargetResolver.rank(nodes.map { it.second })
        ranked.firstOrNull { it.kind == SearchTargetResolver.Kind.EDITABLE_FIELD }?.let {
            return "✅ [SEARCH_ACTIVATED] 当前已经存在可编辑搜索框，无需重复点击"
        }
        ranked.firstOrNull { it.kind == SearchTargetResolver.Kind.SEARCH_ENTRY }?.let { candidate ->
            val actual = nodes.firstOrNull { it.second == candidate.node }?.first
                ?: return "❌ 搜索入口节点已失效"
            val imeBefore = hasInputMethodWindow(svc)
            val visualBefore = captureAppSurfaceFingerprint(svc)
            if (!dispatchNodeClick(svc, clickableParent(actual) ?: actual)) return "❌ 搜索入口拒绝点击"
            return verifySearchActivated(svc, "accessibility", imeBefore, visualBefore)
        }

        val before = ScreenshotTool().captureWithAccessibility(svc)
            ?: return "❌ 无障碍无搜索候选且本地截图失败；已停止，不猜坐标"
        val imeBefore = hasInputMethodWindow(svc)
        val (visualBefore, candidate) = try {
            appSurfaceFingerprint(before) to LocalSearchIconDetector.detect(before)
        } finally {
            before.recycle()
        }
        candidate ?: return "❌ 无障碍无搜索候选，本地截图也没有唯一高置信度放大镜；已停止，不会误点加号"
        if (!dispatchPointClick(svc, candidate.x.toFloat(), candidate.y.toFloat())) {
            return "❌ 本地放大镜候选点击未能派发"
        }
        return verifySearchActivated(
            svc,
            "local_pixels score=${"%.3f".format(candidate.score)} runnerUp=${"%.3f".format(candidate.runnerUp)}",
            imeBefore,
            visualBefore,
        )
    }

    private fun verifySearchActivated(
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        route: String,
        imeBefore: Boolean,
        visualBefore: IntArray?,
    ): String {
        repeat(10) {
            Thread.sleep(250)
            // Keep verification bound to the remembered external task.  A Zorv overlay/chat
            // editor must never count as proof that the target app opened its search field.
            val root = ExternalUiTargetSession.rootForAutomation(svc)
            if (root != null && snapshotVisibleNodes(root).any { it.first.isEditable }) {
                return "✅ [SEARCH_ACTIVATED] 已通过 $route 激活搜索，可编辑框已确认"
            }
            val imeNow = hasInputMethodWindow(svc)
            if (imeNow && !imeBefore) {
                return "✅ [SEARCH_ACTIVATED] 已通过 $route 激活搜索，输入法由隐藏变为显示，焦点已确认"
            }
            if (imeNow && visualBefore != null) {
                val visualNow = captureAppSurfaceFingerprint(svc)
                if (visualNow != null && visualFingerprintsDiffer(visualBefore, visualNow)) {
                    return "✅ [SEARCH_ACTIVATED] 已通过 $route 激活搜索，目标画面变化且输入法可见，焦点已确认"
                }
            }
        }
        return "❌ 搜索点击已派发，但没有取得目标编辑框、新输入法窗口或画面变化证据；禁止继续输入"
    }
}

/** Paste Unicode text into an already-focused custom-drawn editor, then require visual readback. */
class PasteFocusedTextTool : QuroTool {
    override val name = "paste_focused_text"
    override val description =
        "当目标应用是自绘界面、无障碍找不到 EditText，但输入框已聚焦且键盘已出现时，通过临时剪贴板和系统粘贴键输入中文。" +
            "该工具只返回待视觉核验，之后必须 screenshot/visual_analysis 回读，不能直接算输入成功。"
    override val parametersJson = """{
        "type":"object",
        "properties":{"text":{"type":"string","description":"要粘贴的完整文字"}},
        "required":["text"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val text = runCatching { JSONObject(arguments).optString("text") }.getOrDefault("")
        if (text.isEmpty()) return "❌ text 不能为空"
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"
        val root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ 无法恢复目标应用现场，未执行粘贴"
        if (root.packageName?.toString() == context.packageName) return "❌ 当前仍是 Zorv，拒绝粘贴"
        if (!hasInputMethodWindow(svc)) {
            return "❌ 未检测到输入法窗口，无法证明输入框已聚焦，拒绝粘贴"
        }
        val agentImeResult = typeWithTemporaryAgentIme(context, svc, text)
        if (agentImeResult != null) return agentImeResult

        // Compatibility fallback for devices where the Agent IME has not been enabled.  This is
        // intentionally evidence-gated; a dispatched KEYCODE_PASTE is never reported as input.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        clipboard.setPrimaryClip(ClipData.newPlainText("zorv_focused_input", text))
        val staged = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        if (staged != text) {
            runCatching { if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip() }
            return "❌ 剪贴板暂存文字与目标文字不一致，拒绝执行粘贴"
        }
        // Build the baseline after staging the clipboard so Android's clipboard UI cannot be
        // mistaken for evidence that text appeared in the target editor.  Sample only the app
        // surface above the keyboard/clipboard affordances.
        Thread.sleep(150)
        val visualBefore = captureAppSurfaceFingerprint(svc)
            ?: run {
                runCatching { if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip() }
                return "❌ 粘贴前截图失败，无法建立输入验证基线"
            }
        val result = QuroShizukuBridge.exec(context, "input keyevent 279")
        var visualChanged = false
        repeat(5) {
            Thread.sleep(250)
            val visualNow = captureAppSurfaceFingerprint(svc)
            if (visualNow != null && visualFingerprintsDiffer(visualBefore, visualNow)) {
                visualChanged = true
                return@repeat
            }
        }
        runCatching {
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
        }
        if (result.startsWith("❌")) return "❌ 系统粘贴键执行失败：$result"
        if (!visualChanged) return "❌ 粘贴键已派发，但目标 App 画面没有变化；未证明文字已输入，已停止"
        return "⚠️ [FOCUSED_PASTE_PENDING_VERIFICATION] 剪贴板已校验，粘贴后目标画面已变化，并已恢复原剪贴板；" +
            "仍必须截图回读确认输入文字完全等于目标文字，否则禁止继续"
    }

    private fun typeWithTemporaryAgentIme(
        context: Context,
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        text: String,
    ): String? {
        val component = "${context.packageName}/.service.QuroAiKeyboardService"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            .orEmpty().split(':').any { it == component }
        if (!enabled) return null

        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty().takeIf(::isSafeImeComponent)
        val switched = QuroShizukuBridge.exec(context, "ime set $component")
        if (switched.startsWith("❌")) return "❌ 无法临时切换到 Zorv Agent IME：$switched"

        try {
            var keyboard: QuroAiKeyboardService? = null
            for (attempt in 0 until 12) {
                Thread.sleep(150)
                keyboard = QuroAiKeyboardService.instance?.takeIf { it.isInputActive() }
                if (keyboard != null) break
            }
            val activeKeyboard = keyboard
                ?: return "❌ Zorv Agent IME 已切换，但目标搜索框没有提供 InputConnection，已停止"
            val visualBefore = captureAppSurfaceFingerprint(svc)
                ?: return "❌ Agent IME 输入前截图失败，无法建立验证基线"
            if (!activeKeyboard.clearText()) return "❌ Agent IME 无法清空搜索框旧内容，拒绝追加输入"
            if (!activeKeyboard.typeText(text)) return "❌ Agent IME commitText 失败，未输入任何内容"

            var changed = false
            repeat(6) {
                Thread.sleep(200)
                val visualNow = captureAppSurfaceFingerprint(svc)
                if (visualNow != null && visualFingerprintsDiffer(visualBefore, visualNow)) changed = true
            }
            if (!changed) {
                return "❌ Agent IME commitText 已返回成功，但目标 App 画面没有变化；未证明文字已落入搜索框"
            }
            return "⚠️ [FOCUSED_PASTE_PENDING_VERIFICATION] 已通过 Zorv Agent IME 清空旧内容并 commitText ${text.length} 个字符，" +
                "目标 App 画面变化已确认；下一轮必须用 visual_analysis 回读完整文字"
        } finally {
            if (previousIme != null && previousIme != component) {
                QuroShizukuBridge.exec(context, "ime set $previousIme")
            }
        }
    }

    private fun isSafeImeComponent(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))
}

private fun hasInputMethodWindow(service: AccessibilityService): Boolean =
    service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

private fun captureAppSurfaceFingerprint(
    service: com.ai.assistance.quro.service.QuroAccessibilityService,
): IntArray? = ScreenshotTool().captureWithAccessibility(service)?.let { bitmap ->
    try {
        appSurfaceFingerprint(bitmap)
    } finally {
        bitmap.recycle()
    }
}

private fun captureRealAppSurfaceFingerprint(
    context: Context,
    service: com.ai.assistance.quro.service.QuroAccessibilityService,
): IntArray? {
    val shizukuPath = ScreenshotTool().captureWithShizuku(context)
    val bitmap = shizukuPath?.let(android.graphics.BitmapFactory::decodeFile)
    if (bitmap != null) {
        if (bitmap.width < 360 || bitmap.height < 640) {
            bitmap.recycle()
        } else {
            return try {
                appSurfaceFingerprint(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }
    return captureAppSurfaceFingerprint(service)
}

private fun appSurfaceFingerprint(bitmap: android.graphics.Bitmap): IntArray {
    val surfaceHeight = (bitmap.height * 0.68f).toInt().coerceIn(1, bitmap.height)
    val surface = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, surfaceHeight)
    return try {
        visualFingerprint(surface)
    } finally {
        if (surface !== bitmap) surface.recycle()
    }
}

private fun snapshotVisibleNodes(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, SearchTargetResolver.Node>> {
    val out = mutableListOf<Pair<AccessibilityNodeInfo, SearchTargetResolver.Node>>()
    var visited = 0
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 16 || visited++ >= 1200) return
        if (node.isVisibleToUser) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            out += node to SearchTargetResolver.Node(
                text = node.text?.toString().orEmpty(),
                hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else "",
                description = node.contentDescription?.toString().orEmpty(),
                resourceId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                clickable = node.isClickable, editable = node.isEditable, enabled = node.isEnabled,
            )
        }
        for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
    }
    visit(root, 0)
    return out
}

private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    repeat(8) {
        val candidate = current ?: return null
        if (candidate.isClickable && candidate.isEnabled && candidate.isVisibleToUser) return candidate
        current = candidate.parent
    }
    return null
}

private fun dispatchNodeClick(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    return if (bounds.isEmpty) false else dispatchPointClick(service, bounds.exactCenterX(), bounds.exactCenterY())
}

private fun dispatchPointClick(service: AccessibilityService, x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
        .build()
    return service.dispatchGesture(gesture, null, null)
}

/**
 * Generic, evidence-gated messaging transaction for any app exposing a usable accessibility tree.
 * No package, activity, resource id, coordinate, or product-specific page rule is embedded here.
 */
class SendMessageInAppTool : QuroTool {
    override val name = "send_message_in_app"
    override val description =
        "在任意已安装应用中完成“搜索联系人→唯一选择→核对会话→输入→发送后验证”的通用事务。" +
            "不绑定应用包名或坐标。只有用户当前指令同时明确给出应用、联系人、正文并允许发送时，才可将 confirm_send=true；" +
            "否则必须保持 false，只输入草稿不发送。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "app_name":{"type":"string","description":"目标应用显示名"},
            "contact":{"type":"string","description":"联系人或群聊的完整名称，必须精确匹配"},
            "message":{"type":"string","description":"要输入的完整正文"},
            "confirm_send":{"type":"boolean","description":"当前用户是否明确授权立即发送；默认 false","default":false},
            "transaction_id":{"type":"string","description":"自绘页面返回 needs_visual 后必须原样回传的事务 ID"},
            "resume_stage":{"type":"string","enum":["verify_search_field","select_contact","verify_conversation","verify_draft","verify_sent"],"description":"必须与上一次 needs_visual 返回的 stage 完全一致"},
            "observation_version":{"type":"integer","description":"needs_visual 返回的页面观察版本；坐标只对该版本有效，必须原样回传"},
            "visual_verified":{"type":"boolean","description":"视觉上是否满足该阶段 instruction 的全部精确条件；不确定必须 false"},
            "action_x":{"type":"integer","description":"该阶段唯一目标的真实屏幕中心 x；只在 instruction 要求点击时提供"},
            "action_y":{"type":"integer","description":"该阶段唯一目标的真实屏幕中心 y；只在 instruction 要求点击时提供"},
            "candidate_options":{"type":"array","items":{"type":"object","properties":{"section":{"type":"string","enum":["contact","group","chat_history","web_search","other"]},"label":{"type":"string"},"action_x":{"type":"integer"},"action_y":{"type":"integer"},"row_role_label":{"type":"string","description":"该候选行内可见的对象类型原文，例如联系人；没有则为空"},"row_role_x":{"type":"integer"},"row_role_y":{"type":"integer"},"row_left":{"type":"integer"},"row_top":{"type":"integer"},"row_right":{"type":"integer"},"row_bottom":{"type":"integer"}},"required":["section","label","action_x","action_y","row_role_label","row_role_x","row_role_y","row_left","row_top","row_right","row_bottom"]},"description":"select_contact 阶段必须每个可点击结果行只返回一个候选和一个点击中心；行内类型文字及坐标必须绑定在同一行矩形内。代码根据可见分区或行内类型证据判定，绝不相信数量"},
            "section_headers":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string"},"action_x":{"type":"integer"},"action_y":{"type":"integer"}},"required":["label","action_x","action_y"]},"description":"select_contact 阶段必须按屏幕从上到下列出所有可见分区标题及标题中心；本地代码依据标题与候选纵向位置重新归类，不信任 candidate_options.section"},
            "cancel_contact_choice":{"type":"boolean","description":"用户在候选选择中明确回答都不是或取消时为 true"}
        }
    }"""

    private enum class VisualStage(val wire: String) {
        VERIFY_SEARCH_FIELD("verify_search_field"),
        SELECT_CONTACT("select_contact"),
        VERIFY_CONVERSATION("verify_conversation"),
        VERIFY_DRAFT("verify_draft"),
        VERIFY_SENT("verify_sent"),
    }

    private data class VisualTransaction(
        val id: String,
        val appName: String,
        val targetPackage: String,
        val contact: String,
        val message: String,
        val confirmSend: Boolean,
        val createdAtMs: Long,
        var stage: VisualStage,
        var screenshotWidth: Int = 0,
        var screenshotHeight: Int = 0,
        var observationVersion: Long = 0L,
        var observationMode: String = VerifiedUiActionExecutor.ObservationMode.SCREENSHOT_VISUAL.wire,
        var searchInputAttempted: Boolean = false,
        var messageInputAttempted: Boolean = false,
        var contactVisualRetries: Int = 0,
        var pendingContactChoices: List<ContactChoice> = emptyList(),
    )

    internal data class ContactChoice(
        val section: String,
        val label: String,
        val x: Int,
        val y: Int,
        val rowRoleLabel: String = "",
        val rowRoleX: Int = -1,
        val rowRoleY: Int = -1,
        val rowLeft: Int = -1,
        val rowTop: Int = -1,
        val rowRight: Int = -1,
        val rowBottom: Int = -1,
    )
    internal data class SectionHeader(val label: String, val x: Int, val y: Int)

    companion object {
        // A cold launch of large apps can spend several seconds on a splash/launcher transition,
        // especially on Huawei devices. Keep the ordinary foreground check short, but give the
        // one check immediately following launch enough time to observe a stable target window.
        internal const val COLD_LAUNCH_SETTLE_ATTEMPTS = 40
        private const val VISUAL_TRANSACTION_TTL_MS = 5 * 60 * 1000L
        private val visualTransactions = ConcurrentHashMap<String, VisualTransaction>()

        /** Continue the newest unresolved contact choice for both typed and transcribed speech. */
        fun pendingContactChoiceCall(userText: String): QuroToolCall? {
            pruneExpiredVisualTransactions()
            val transaction = visualTransactions.values
                .filter { it.stage == VisualStage.SELECT_CONTACT && it.pendingContactChoices.isNotEmpty() }
                .maxByOrNull { it.createdAtMs }
                ?: return null
            val normalized = userText.trim().trim('，', ',', '。', '.', '！', '!', '？', '?')
            val displayedCancelNumber = transaction.pendingContactChoices.size + 1
            val numericAnswer = Regex("^(?:选(?:择)?\\s*)?(\\d+)(?:号|个)?$")
                .matchEntire(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val cancel = normalized in setOf("都不是", "都不对", "取消", "取消发送", "没有", "无") ||
                numericAnswer == displayedCancelNumber
            val selectedIndex = if (cancel) null else parseContactChoiceIndex(
                normalized,
                transaction.pendingContactChoices.map { it.label },
            )
            if (!cancel && selectedIndex == null) return null
            return QuroToolCall(
                name = "send_message_in_app",
                arguments = JSONObject()
                    .put("transaction_id", transaction.id)
                    .put("resume_stage", transaction.stage.wire)
                    .put("observation_version", transaction.observationVersion)
                    .put("visual_verified", !cancel)
                    .put("cancel_contact_choice", cancel)
                    .apply {
                        selectedIndex?.let { index ->
                            val choice = transaction.pendingContactChoices[index]
                            put("action_x", choice.x)
                            put("action_y", choice.y)
                        }
                    }
                    .toString(),
            )
        }

        internal fun parseContactChoiceIndex(userText: String, labels: List<String>): Int? {
            val text = userText.trim().lowercase().removePrefix("选择").removePrefix("选").trim()
            val number = Regex("^(?:第)?(\\d+)(?:号|个)?$")
                .matchEntire(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (number != null && number in 1..labels.size) return number - 1
            val ordinal = mapOf(
                "一" to 0, "第一" to 0, "第一个" to 0, "第一个人" to 0,
                "二" to 1, "第二" to 1, "第二个" to 1, "第二个人" to 1,
                "三" to 2, "第三" to 2, "第三个" to 2, "第三个人" to 2,
                "四" to 3, "第四" to 3, "第四个" to 3, "第四个人" to 3,
                "五" to 4, "第五" to 4, "第五个" to 4, "第五个人" to 4,
                "六" to 5, "第六" to 5, "第六个" to 5, "第六个人" to 5,
                "七" to 6, "第七" to 6, "第七个" to 6, "第七个人" to 6,
                "八" to 7, "第八" to 7, "第八个" to 7, "第八个人" to 7,
            )[text]
            if (ordinal != null && ordinal in labels.indices) return ordinal
            val exactMatches = labels.indices.filter { labels[it].trim().equals(userText.trim(), ignoreCase = true) }
            return exactMatches.singleOrNull()
        }

        internal fun isExactContactSectionCandidate(contact: String, section: String, label: String): Boolean {
            val wanted = contact.trim()
            val visible = label.trim()
            if (wanted.isEmpty() || visible.isEmpty()) return false
            if (section.trim().lowercase() != "contact") return false
            if (visible.contains("群聊") || visible.contains("聊天记录") ||
                visible.contains("群成员") || visible.contains("网络搜索")
            ) return false
            // Repeated text, font size and highlight colour are not separate targets.  Accept a
            // candidate only when the vision result explicitly assigns the exact name to the
            // Contacts section/row (for example "灵儿｜微信号尾号 1234" or "灵儿 联系人").
            if (visible == wanted) return true
            if (!visible.startsWith(wanted)) return false
            val suffix = visible.removePrefix(wanted)
            return listOf("｜", "|", " ", "（", "(", "·", "-").any(suffix::startsWith)
        }

        internal fun exactContactSectionCandidates(
            contact: String,
            candidates: List<ContactChoice>,
            sectionHeaders: List<SectionHeader>,
        ): List<ContactChoice> {
            val orderedHeaders = sectionHeaders
                .filter { it.label.isNotBlank() && it.x >= 0 && it.y >= 0 }
                .sortedBy { it.y }
            return candidates
            // Ignore the model's self-declared section. A row is a contact only when either its
            // nearest visible heading is explicitly a contacts section, or a visible contact-role
            // label is geometrically bound inside that same result row. Generic headings such as
            // "Frequently used" therefore remain app-agnostic instead of being hard-coded.
            .filter { candidate ->
                val nearestHeader = orderedHeaders.lastOrNull { it.y < candidate.y }
                val sectionProvesContact = nearestHeader?.let { isContactSectionHeader(it.label) } == true
                val sectionProvesNonContact = nearestHeader?.let { isNonContactSectionHeader(it.label) } == true
                val rowProvesContact = hasBoundContactRoleEvidence(candidate)
                !sectionProvesNonContact && (sectionProvesContact || rowProvesContact) &&
                    isExactContactSectionCandidate(contact, "contact", candidate.label)
            }
            // A highlighted glyph, name and role label may be reported more than once. One visual
            // row always maps to one choice and one click coordinate.
            .distinctBy { candidate ->
                if (candidate.hasValidRowBounds()) {
                    "${candidate.rowLeft},${candidate.rowTop},${candidate.rowRight},${candidate.rowBottom}"
                } else {
                    "${candidate.x},${candidate.y}"
                }
            }
        }

        internal fun isContactSectionHeader(label: String): Boolean {
            val normalized = label.trim().lowercase().replace(" ", "")
            return Regex("^(?:联系人|contacts?)(?:[（(]?\\d+[）)]?)?$").matches(normalized)
        }

        internal fun isNonContactSectionHeader(label: String): Boolean {
            val normalized = label.trim().lowercase().replace(" ", "").replace("_", "")
            return Regex(
                "^(?:群聊|群组|聊天记录|网络搜索|搜索网络结果|groups?|groupchats?|chathistory|websearch)(?:[（(]?\\d+[）)]?)?$",
            ).matches(normalized)
        }

        internal fun hasBoundContactRoleEvidence(candidate: ContactChoice): Boolean {
            val role = candidate.rowRoleLabel.trim().lowercase()
            val roleIsContact = role in setOf("联系人", "好友", "个人", "contact", "contacts", "person")
            if (!roleIsContact || !candidate.hasValidRowBounds()) return false
            return candidate.x in candidate.rowLeft..candidate.rowRight &&
                candidate.y in candidate.rowTop..candidate.rowBottom &&
                candidate.rowRoleX in candidate.rowLeft..candidate.rowRight &&
                candidate.rowRoleY in candidate.rowTop..candidate.rowBottom
        }

        private fun ContactChoice.hasValidRowBounds(): Boolean =
            rowLeft >= 0 && rowTop >= 0 && rowRight > rowLeft && rowBottom > rowTop

        private fun pruneExpiredVisualTransactions() {
            val cutoff = System.currentTimeMillis() - VISUAL_TRANSACTION_TTL_MS
            visualTransactions.entries
                .filter { it.value.createdAtMs < cutoff }
                .map { it.key }
                .forEach(visualTransactions::remove)
        }
    }

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { return "❌ 参数不是有效 JSON" }
        val transactionId = args.optString("transaction_id").trim()
        if (transactionId.isNotEmpty()) return resumeVisualTransaction(context, args, transactionId)
        val appName = args.optString("app_name").trim()
        val contact = args.optString("contact").trim()
        val message = args.optString("message")
        val confirmSend = args.optBoolean("confirm_send", false)
        val searchResultsReady = args.optBoolean("_search_results_ready", false)
        if (appName.isEmpty() || contact.isEmpty() || message.isEmpty()) {
            return "❌ app_name、contact、message 均不能为空；缺少明确对象或正文时禁止发送"
        }
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"

        // search_in_app may have already left the target app on a populated, custom-drawn
        // search-results page. Re-launching the app here can destroy that exact FTS surface and
        // return to its launcher activity. The handoff is generated only from a matching,
        // evidence-gated search result in this same assistant turn, so resume the remembered
        // external window directly and continue with contact selection.
        if (searchResultsReady) {
            val searchResultsRoot = ExternalUiTargetSession.rootForAutomation(svc)
                ?: return "❌ [恢复搜索结果] 无法获得目标应用窗口，已停止"
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = searchResultsRoot.packageName?.toString().orEmpty(),
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.SELECT_CONTACT,
            )
        }

        val launched = SearchAndLaunchAppTool().run(
            context,
            JSONObject().put("app_name", appName).toString(),
        )
        if (!launched.startsWith("已")) return "❌ [打开应用] $launched"
        Thread.sleep(900)
        var root = ExternalUiTargetSession.rootForAutomation(
            svc,
            settleAttempts = COLD_LAUNCH_SETTLE_ATTEMPTS,
        )
            ?: return "❌ [恢复现场] 无法获得目标应用窗口，未执行后续操作"
        val targetPackage = root.packageName?.toString().orEmpty()

        var searchEditor = findSearchEditor(root)
        if (searchEditor == null) {
            val activated = ActivateAppSearchTool().run(context, "{}")
            if (!activated.startsWith("✅")) {
                return buildVisualContinuation(
                    context = context,
                    appName = appName,
                    targetPackage = targetPackage,
                    contact = contact,
                    message = message,
                    confirmSend = confirmSend,
                    initialStage = VisualStage.VERIFY_SEARCH_FIELD,
                    retryNotice = "搜索入口动作已尝试，但无障碍无法确认自绘搜索框；请直接核对当前画面，禁止重新打开应用或调用普通输入工具。",
                )
            }
            root = ExternalUiTargetSession.rootForAutomation(svc) ?: return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_SEARCH_FIELD,
                retryNotice = "搜索动作已完成，但无障碍无法恢复自绘页面；请从当前截图确认唯一搜索框。",
            )
            searchEditor = findSearchEditor(root)
            if (searchEditor == null && svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                val pasted = PasteFocusedTextTool().run(
                    context,
                    JSONObject().put("text", contact).toString(),
                )
                return if (pasted.startsWith("⚠️")) {
                    buildVisualContinuation(
                        context = context,
                        appName = appName,
                        targetPackage = root.packageName?.toString().orEmpty(),
                        contact = contact,
                        message = message,
                        confirmSend = confirmSend,
                        initialStage = VisualStage.SELECT_CONTACT,
                    )
                } else {
                    buildVisualContinuation(
                        context = context,
                        appName = appName,
                        targetPackage = targetPackage,
                        contact = contact,
                        message = message,
                        confirmSend = confirmSend,
                        initialStage = VisualStage.VERIFY_SEARCH_FIELD,
                        retryNotice = "自绘搜索框输入未取得证据；请重新核对搜索框中心，禁止输入消息正文。",
                    )
                }
            }
        }
        searchEditor ?: return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.VERIFY_SEARCH_FIELD,
            retryNotice = "搜索页已激活，但无障碍没有暴露编辑框；请视觉确认唯一搜索框。",
        )
        if (!setAndVerify(searchEditor, contact)) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_SEARCH_FIELD,
                retryNotice = "无障碍编辑框无法覆盖并回读联系人；请视觉核对搜索框并改用 Agent 输入法，禁止输入消息正文。",
            )
        }

        Thread.sleep(700)
        root = ExternalUiTargetSession.rootForAutomation(svc) ?: return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.SELECT_CONTACT,
            retryNotice = "联系人已输入，但无障碍无法读取自绘搜索结果；请仅核对联系人分区。",
        )
        val contactMatch = exactContactMatches(root, contact)
        if (contactMatch.size != 1) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.SELECT_CONTACT,
                retryNotice = if (contactMatch.isEmpty()) {
                    "无障碍没有暴露联系人结果；必须从截图的联系人分区核对，禁止把群聊或聊天记录当联系人。"
                } else {
                    "无障碍发现多个同名结果；必须从截图区分真实联系人，无法唯一时返回候选供用户选择。"
                },
            )
        }
        val beforeConversation = screenFingerprint(root)
        if (!clickNode(svc, contactMatch.single())) return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.SELECT_CONTACT,
            retryNotice = "无障碍联系人点击未能派发；请从截图重新定位联系人分区中的唯一精确结果。",
        )
        root = waitForChangedRoot(svc, beforeConversation) ?: return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.SELECT_CONTACT,
            retryNotice = "联系人点击后无障碍没有确认页面变化；请重新核对结果，禁止输入正文。",
        )

        if (!hasConversationIdentity(root, contact)) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_CONVERSATION,
                retryNotice = "无障碍无法读取会话标题；必须视觉确认已经离开搜索页且标题精确匹配。",
            )
        }
        val messageEditor = selectMessageEditor(root)
            ?: return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_CONVERSATION,
                retryNotice = "无障碍无法定位唯一消息框；请视觉确认会话标题及底部输入框。",
            )
        if (!setAndVerify(messageEditor, message)) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_CONVERSATION,
                retryNotice = "无障碍消息框无法写入并回读正文；请视觉确认会话后改用 Agent 输入法。",
            )
        }
        if (!confirmSend) {
            return "✅ [MESSAGE_DRAFT_VERIFIED] 已核对会话“$contact”并回读确认正文；按授权要求停在草稿，未发送"
        }

        val sendRoot = ExternalUiTargetSession.rootForAutomation(svc) ?: return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.VERIFY_DRAFT,
            retryNotice = "发送前无障碍无法恢复页面；必须从截图复核会话、正文和发送按钮。",
        )
        if (!hasConversationIdentity(sendRoot, contact) || !editorContains(sendRoot, message)) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_DRAFT,
                retryNotice = "无障碍无法同时复核联系人和正文；必须视觉逐字确认后才允许发送。",
            )
        }
        val sendNode = findSendAction(sendRoot)
            ?: return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = targetPackage,
                contact = contact,
                message = message,
                confirmSend = confirmSend,
                initialStage = VisualStage.VERIFY_DRAFT,
                retryNotice = "无障碍没有暴露唯一发送按钮；请视觉复核正文并定位发送按钮，禁止用回车猜测。",
            )
        if (!clickNode(svc, sendNode)) return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.VERIFY_DRAFT,
            retryNotice = "无障碍发送点击未能派发；请重新视觉复核并定位发送按钮。",
        )

        repeat(10) {
            Thread.sleep(250)
            val after = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
            if (messageWasSent(after, message)) {
                return "✅ [MESSAGE_SEND_CONFIRMED] 已向“$contact”发送，并通过输入框清空及消息正文回读确认"
            }
        }
        return buildVisualContinuation(
            context = context,
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            initialStage = VisualStage.VERIFY_SENT,
            retryNotice = "发送点击已派发，但无障碍未同时取得输入框清空和新消息正文证据；必须视觉核对，不得提前报告成功。",
        )
    }

    /**
     * Custom-drawn apps cannot expose a contact result through Accessibility. Return the actual
     * screenshot as a structured continuation instead of a prose-only warning. QuroAssistant
     * recognizes attach_to_next_model and sends this image to the immediately following model
     * round. Do not include the message body here: it remains in the original user instruction
     * and tool arguments, while this result only carries the minimum routing state.
     */
    private fun buildVisualContinuation(
        context: Context,
        appName: String,
        targetPackage: String,
        contact: String,
        message: String,
        confirmSend: Boolean,
        initialStage: VisualStage = VisualStage.SELECT_CONTACT,
        retryNotice: String? = null,
    ): String {
        if (targetPackage.isBlank() || targetPackage == context.packageName) {
            return "❌ [创建消息事务] 目标应用身份无效，已停止"
        }
        pruneExpiredVisualTransactions()
        val transaction = VisualTransaction(
            id = UUID.randomUUID().toString(),
            appName = appName,
            targetPackage = targetPackage,
            contact = contact,
            message = message,
            confirmSend = confirmSend,
            createdAtMs = System.currentTimeMillis(),
            stage = initialStage,
        )
        visualTransactions[transaction.id] = transaction
        return captureVisualStage(context, transaction, retryNotice)
    }

    private fun resumeVisualTransaction(context: Context, args: JSONObject, transactionId: String): String {
        pruneExpiredVisualTransactions()
        val transaction = visualTransactions[transactionId]
            ?: return "❌ [消息事务已失效] transaction_id 不存在或已超过 5 分钟；禁止凭旧截图继续"
        val requestedStage = args.optString("resume_stage").trim()
        if (requestedStage != transaction.stage.wire) {
            // A model may infer the next page from the picture even when the previous click did
            // not actually leave the search page. Discard coordinates whose stage meaning is
            // wrong, but keep the transaction alive and attach authoritative current evidence.
            val service = com.ai.assistance.quro.service.QuroAccessibilityService.instance
                ?: return "❌ 无障碍服务未连接"
            val currentRoot = ExternalUiTargetSession.rootForAutomation(service)
                ?: return "❌ [恢复消息事务] 无法获得目标应用窗口"
            if (currentRoot.packageName?.toString() != transaction.targetPackage) {
                visualTransactions.remove(transactionId)
                return "❌ [恢复消息事务] 前台应用已经变化，事务已安全终止"
            }
            return captureVisualStage(
                context,
                transaction,
                "上一调用回传 resume_stage=$requestedStage，但事务实际仍为 ${transaction.stage.wire}；" +
                    "已丢弃上一坐标。只按当前截图和当前阶段重新核对，禁止猜测已经进入下一页。",
            )
        }
        val suppliedObservation = args.optLong("observation_version", 0L)
        if (suppliedObservation != transaction.observationVersion || suppliedObservation <= 0L) {
            return captureVisualStage(
                context,
                transaction,
                "回传 observation_version=$suppliedObservation 已缺失或过期；旧截图坐标已丢弃。" +
                    "只能按新截图和新版本重新观察，禁止复用坐标。",
            )
        }
        if (args.optBoolean("cancel_contact_choice", false)) {
            visualTransactions.remove(transactionId)
            return "✅ [MESSAGE_CONTACT_CHOICE_CANCELLED] 已取消本次联系人选择，未输入或发送消息"
        }
        if (transaction.stage == VisualStage.SELECT_CONTACT) {
            // Never trust the model's count or visual_verified flag for a search result page.
            // Every textual hit must carry a section and local code alone decides which rows are
            // real contacts. This keeps OCR/highlight fluctuations from changing the outcome.
            val options = args.optJSONArray("candidate_options")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val section = item.optString("section").trim().lowercase()
                    val label = item.optString("label").trim()
                    val x = item.optInt("action_x", -1)
                    val y = item.optInt("action_y", -1)
                    ContactChoice(
                        section = section,
                        label = label,
                        x = x,
                        y = y,
                        rowRoleLabel = item.optString("row_role_label").trim(),
                        rowRoleX = item.optInt("row_role_x", -1),
                        rowRoleY = item.optInt("row_role_y", -1),
                        rowLeft = item.optInt("row_left", -1),
                        rowTop = item.optInt("row_top", -1),
                        rowRight = item.optInt("row_right", -1),
                        rowBottom = item.optInt("row_bottom", -1),
                    ).takeIf {
                        label.isNotEmpty() && x in 0 until transaction.screenshotWidth &&
                            y in 0 until transaction.screenshotHeight
                    }
                }
            }.orEmpty()
            val sectionHeaders = args.optJSONArray("section_headers")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val label = item.optString("label").trim()
                    val x = item.optInt("action_x", -1)
                    val y = item.optInt("action_y", -1)
                    SectionHeader(label, x, y).takeIf {
                        label.isNotEmpty() && x in 0 until transaction.screenshotWidth &&
                            y in 0 until transaction.screenshotHeight
                    }
                }
            }.orEmpty()
            val contactOptions = exactContactSectionCandidates(transaction.contact, options, sectionHeaders)
            val singleton = contactOptions.singleOrNull()
            if (singleton != null) {
                args.put("visual_verified", true)
                    .put("action_x", singleton.x)
                    .put("action_y", singleton.y)
            } else if (contactOptions.size >= 2) {
                transaction.pendingContactChoices = contactOptions
                return buildString {
                    append("⚠️ [MESSAGE_CONTACT_CHOICE_REQUIRED] 找到多个联系人“${transaction.contact}”，请选择：\n")
                    contactOptions.forEachIndexed { index, option -> append("${index + 1}. ${option.label}\n") }
                    append("${contactOptions.size + 1}. 都不是")
                }
            } else if (transaction.contactVisualRetries < 1) {
                transaction.contactVisualRetries += 1
                return captureVisualStage(
                    context,
                    transaction,
                    "上一张图缺少可验证的候选行身份与位置证据。请每个结果行只列一个候选和一个点击中心，" +
                        "并同时返回分区标题以及同一行内可见的类型文字、坐标和行矩形；本地代码将重新绑定。",
                )
            } else {
                visualTransactions.remove(transactionId)
                return "❌ [联系人分区核对未通过] 没有可验证的联系人分区精确候选；事务已安全终止"
            }
        } else if (!args.optBoolean("visual_verified", false)) {
            visualTransactions.remove(transactionId)
            return "❌ [视觉核对未通过] 目标不唯一、内容不符或无法确认；事务已安全终止"
        }
        val svc = com.ai.assistance.quro.service.QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"
        val root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ [恢复消息事务] 无法获得目标应用窗口"
        if (root.packageName?.toString() != transaction.targetPackage) {
            visualTransactions.remove(transactionId)
            return "❌ [恢复消息事务] 前台应用已经变化，事务已安全终止"
        }

        return when (transaction.stage) {
            VisualStage.VERIFY_SEARCH_FIELD -> {
                val point = requiredVisualPoint(args, transaction)
                    ?: return "❌ [定位搜索框] 缺少唯一搜索框的有效中心坐标"
                val focused = executeVerifiedVisualClick(
                    context = context,
                    svc = svc,
                    cacheKey = "message_search_focus",
                    point = point,
                    retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
                    verify = { waitForTargetInputFocus(svc, transaction.targetPackage) },
                )
                if (!focused.verified) {
                    return captureVisualStage(
                        context,
                        transaction,
                        "搜索框动作最多已尝试一个备用通道，但未证明目标应用获得输入焦点；已重新观察，禁止输入。",
                    )
                }
                if (!VerifiedUiActionExecutor.canDispatchInput(focusVerified = true, alreadyAttempted = transaction.searchInputAttempted)) {
                    return "❌ [输入联系人] 本事务已经派发过一次联系人输入；结果不确定时禁止重复输入，必须终止后重新开始"
                }
                transaction.searchInputAttempted = true
                val pasted = PasteFocusedTextTool().run(
                    context,
                    JSONObject().put("text", transaction.contact).toString(),
                )
                if (!pasted.startsWith("⚠️")) {
                    return captureVisualStage(
                        context,
                        transaction,
                        "搜索框点击后仍无法通过 Agent 输入法输入联系人：$pasted 请重新核对搜索框，禁止输入消息正文。",
                    )
                }
                Thread.sleep(700)
                transaction.stage = VisualStage.SELECT_CONTACT
                captureVisualStage(context, transaction)
            }
            VisualStage.SELECT_CONTACT -> {
                val point = requiredVisualPoint(args, transaction) ?: return "❌ [选择联系人] 缺少唯一目标的有效中心坐标"
                transaction.pendingContactChoices = emptyList()
                val beforeConversation = captureRealAppSurfaceFingerprint(context, svc)
                    ?: return "❌ [选择联系人] 点击前无法建立目标页面验证基线，禁止继续"
                val selected = executeVerifiedVisualClick(
                    context = context,
                    svc = svc,
                    cacheKey = "message_select_contact",
                    point = point,
                    retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
                    verify = { waitForStableAppSurfaceChange(context, svc, beforeConversation) },
                )
                if (!selected.verified) {
                    return captureVisualStage(
                        context,
                        transaction,
                        "上一次联系人点击后目标页面没有稳定变化，尚未进入会话；必须重新核对并选择联系人，禁止定位消息输入框。",
                    )
                }
                transaction.stage = VisualStage.VERIFY_CONVERSATION
                captureVisualStage(context, transaction)
            }
            VisualStage.VERIFY_CONVERSATION -> {
                val point = requiredVisualPoint(args, transaction) ?: return "❌ [定位消息框] 缺少唯一消息输入框的有效中心坐标"
                val focused = executeVerifiedVisualClick(
                    context = context,
                    svc = svc,
                    cacheKey = "message_body_focus",
                    point = point,
                    retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
                    verify = { waitForTargetInputFocus(svc, transaction.targetPackage) },
                )
                if (!focused.verified) {
                    return captureVisualStage(
                        context,
                        transaction,
                        "消息框动作最多已尝试一个备用通道，但未证明目标应用获得输入焦点；已重新观察，禁止输入正文。",
                    )
                }
                if (!VerifiedUiActionExecutor.canDispatchInput(focusVerified = true, alreadyAttempted = transaction.messageInputAttempted)) {
                    return "❌ [输入消息] 本事务已经派发过一次正文输入；结果不确定时禁止重复输入，必须终止后重新开始"
                }
                transaction.messageInputAttempted = true
                val pasted = PasteFocusedTextTool().run(
                    context,
                    JSONObject().put("text", transaction.message).toString(),
                )
                if (!pasted.startsWith("⚠️")) return "❌ [输入消息] $pasted"
                transaction.stage = VisualStage.VERIFY_DRAFT
                captureVisualStage(context, transaction)
            }
            VisualStage.VERIFY_DRAFT -> {
                if (!transaction.confirmSend) {
                    visualTransactions.remove(transactionId)
                    return "✅ [MESSAGE_DRAFT_VERIFIED] 已视觉核对会话“${transaction.contact}”和完整正文；按授权要求停在草稿，未发送"
                }
                val point = requiredVisualPoint(args, transaction) ?: return "❌ [发送] 缺少唯一发送按钮的有效中心坐标"
                val beforeSend = captureRealAppSurfaceFingerprint(context, svc)
                    ?: return "❌ [发送] 无法建立发送前验证基线，禁止发送"
                val sent = executeVerifiedVisualClick(
                    context = context,
                    svc = svc,
                    cacheKey = "message_send_once",
                    point = point,
                    retrySafety = VerifiedUiActionExecutor.RetrySafety.DISPATCH_ONCE,
                    verify = { waitForStableAppSurfaceChange(context, svc, beforeSend) },
                )
                if (!sent.verified && !sent.uncertainDispatch) {
                    return "❌ [发送] 点击动作未能派发"
                }
                transaction.stage = VisualStage.VERIFY_SENT
                captureVisualStage(
                    context,
                    transaction,
                    if (sent.verified) null else
                        "发送动作已派发一次但同步结果不确定；为避免重复消息绝不换通道重试，只能从当前画面核对发送结果。",
                )
            }
            VisualStage.VERIFY_SENT -> {
                visualTransactions.remove(transactionId)
                "✅ [MESSAGE_SEND_CONFIRMED] 已向“${transaction.contact}”发送，并通过发送后截图核对输入框清空和完整消息正文"
            }
        }
    }

    private fun captureVisualStage(
        context: Context,
        transaction: VisualTransaction,
        retryNotice: String? = null,
    ): String {
        val service = com.ai.assistance.quro.service.QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接"
        val activeTarget = ExternalUiTargetSession.rootForAutomation(service)
            ?: return "❌ [截图核对] 无法稳定恢复目标应用窗口"
        if (activeTarget.packageName?.toString() != transaction.targetPackage) {
            return "❌ [截图核对] 当前活动窗口不属于目标应用，已停止"
        }
        val stageQuestion = when (transaction.stage) {
            VisualStage.VERIFY_SEARCH_FIELD ->
                "确认当前目标应用已进入全局搜索页，并定位顶部唯一搜索输入框中心；不要定位联系人结果或消息输入框"
            VisualStage.SELECT_CONTACT ->
                "核对名称精确等于“${transaction.contact}”且分区或同行类型证据证明为联系人的结果行；排除群聊、聊天记录和网络搜索"
            VisualStage.VERIFY_CONVERSATION ->
                "确认已经离开搜索结果页：顶部会话名称精确等于“${transaction.contact}”，画面不再显示联系人/群聊/聊天记录结果分区；定位底部唯一消息输入框中心，禁止选择顶部搜索框"
            VisualStage.VERIFY_DRAFT ->
                "核对当前会话名称为“${transaction.contact}”，且输入框内正文与用户原始正文逐字一致；${if (transaction.confirmSend) "同时定位唯一发送按钮中心" else "不要发送"}"
            VisualStage.VERIFY_SENT ->
                "核对发送后输入框已经清空，并且当前会话中出现与用户原始正文逐字一致的新消息"
        }
        val question = listOfNotNull(retryNotice, stageQuestion).joinToString(" ")
        val captured = try {
            VisualAnalysisTool().run(
                context,
                JSONObject()
                    .put("question", question)
                    .put("full_screen", transaction.stage != VisualStage.VERIFY_SEARCH_FIELD)
                    .put("prefer_shizuku", true)
                    .toString(),
            )
        } finally {
            // Visual reasoning is a potentially slow network/model round. Keep Zorv foreground on
            // aggressive OEM power managers; the next tool continuation will restore the exact
            // external task again before touching it.
            ExternalUiTargetSession.returnToOwnApp(service)
        }
        val json = runCatching { JSONObject(captured) }.getOrNull()
            ?: return "❌ [截图核对联系人] $captured"
        if (!json.optBoolean("attach_to_next_model", false)) {
            return "❌ [截图核对联系人] $captured"
        }
        transaction.screenshotWidth = json.optInt("width", 0)
        transaction.screenshotHeight = json.optInt("height", 0)
        if (transaction.screenshotWidth < 360 || transaction.screenshotHeight < 640) {
            visualTransactions.remove(transaction.id)
            return "❌ [截图核对] 未取得真实屏幕像素（尺寸过小），消息事务已安全终止"
        }
        val currentNodes = snapshotVisibleNodes(activeTarget)
        transaction.observationMode = VerifiedUiActionExecutor.observationMode(
            currentNodes.any { (_, node) ->
                node.text.isNotBlank() || node.hint.isNotBlank() || node.description.isNotBlank() || node.editable
            },
        ).wire
        transaction.observationVersion = VerifiedUiActionExecutor.nextObservationVersion()
        return json.apply {
            put("status", "needs_visual")
            put("workflow", "message_send")
            put("stage", transaction.stage.wire)
            put("transaction_id", transaction.id)
            put("observation_version", transaction.observationVersion)
            put("observation_mode", transaction.observationMode)
            put("app_name", transaction.appName)
            put("exact_target", transaction.contact)
            put("confirm_send", transaction.confirmSend)
            put("message_body_retained_in_tool_arguments", true)
            put(
                "instruction",
                listOfNotNull(retryNotice, visualStageInstruction(transaction)).joinToString(" "),
            )
        }.toString()
    }

    private fun waitForStableAppSurfaceChange(
        context: Context,
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        before: IntArray,
    ): Boolean {
        var consecutiveChangedFrames = 0
        repeat(10) {
            Thread.sleep(250)
            val after = captureRealAppSurfaceFingerprint(context, svc)
            if (after != null && visualFingerprintsDiffer(before, after)) {
                consecutiveChangedFrames += 1
                if (consecutiveChangedFrames >= 2) return true
            } else {
                consecutiveChangedFrames = 0
            }
        }
        return false
    }

    private fun executeVerifiedVisualClick(
        context: Context,
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        cacheKey: String,
        point: Pair<Int, Int>,
        retrySafety: VerifiedUiActionExecutor.RetrySafety,
        verify: () -> Boolean,
    ): VerifiedUiActionExecutor.Result = VerifiedUiActionExecutor.execute(
        cacheKey = cacheKey,
        retrySafety = retrySafety,
        dispatch = { route ->
            when (route) {
                VerifiedUiActionExecutor.Route.SHIZUKU -> QuroShizukuBridge.exec(
                    context,
                    "input tap ${point.first} ${point.second} && echo ZORV_UI_ACTION_OK",
                ).contains("ZORV_UI_ACTION_OK")
                VerifiedUiActionExecutor.Route.ACCESSIBILITY ->
                    dispatchPointClick(svc, point.first.toFloat(), point.second.toFloat())
            }
        },
        verify = verify,
    )

    private fun waitForTargetInputFocus(
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        targetPackage: String,
    ): Boolean {
        val samples = mutableListOf<Boolean>()
        repeat(8) {
            Thread.sleep(200)
            val root = ExternalUiTargetSession.rootForAutomation(svc)
            val targetActive = root?.packageName?.toString() == targetPackage
            val focusedNode = root?.let(::snapshotVisibleNodes)
                ?.any { (node, _) -> node.isEditable && node.isFocused }
                ?: false
            samples += targetActive && (focusedNode || hasInputMethodWindow(svc))
            if (VerifiedUiActionExecutor.hasStableChange(samples)) return true
        }
        return false
    }

    private fun visualStageInstruction(transaction: VisualTransaction): String = when (transaction.stage) {
        VisualStage.VERIFY_SEARCH_FIELD ->
            "只有画面明确是目标应用的全局搜索页且顶部搜索输入框唯一时，才调用 send_message_in_app，" +
                "原样回传 transaction_id、observation_version、resume_stage=verify_search_field、visual_verified=true 和搜索框中心 action_x/action_y。" +
                "不得调用 input_text、tap_screen、search_and_launch_app 或 activate_app_search；无法确认时 visual_verified=false。"
        VisualStage.SELECT_CONTACT ->
            "把每个可点击结果整行作为一个候选；文字出现次数、字号大小和高亮颜色都不代表候选数量。" +
                "联系人身份必须由最近的联系人分区标题，或候选行矩形内明确可见的联系人类型标签证明；禁止把群聊名称、群成员命中、聊天记录正文或网络搜索当作联系人。" +
                "只有过滤后恰好一个结果的可见名称与“${transaction.contact}”完全一致时，才调用 send_message_in_app，" +
                "原样回传 transaction_id、observation_version、resume_stage=select_contact、visual_verified=true 和该结果中心 action_x/action_y。" +
                "无论你认为有几个联系人，都必须用 candidate_options 给画面中每个包含同名文字的可点击结果行只列一个候选；label 写姓名和区分信息，action_x/action_y 是整行点击中心，row_left/top/right/bottom 是整行矩形。" +
                "若行内明确显示对象类型，row_role_label 必须写可见原文，row_role_x/row_role_y 写该类型文字中心；没有则返回空字符串和 -1。" +
                "同时必须用 section_headers 按屏幕从上到下列出所有可见分区标题的原文和中心坐标。" +
                "不要自行省略其他分区的同名命中；本地代码不信任数量或 section 字段，而是按分区、行内证据和几何绑定重新归类。" +
                "不要再点搜索图标，不要直接调用 tap_screen，不要报告完成。"
        VisualStage.VERIFY_CONVERSATION ->
            "必须确认已离开搜索页，画面不再显示联系人、群聊或聊天记录结果分区；" +
                "只有顶部会话名称精确等于“${transaction.contact}”且底部消息输入框唯一时，才调用 send_message_in_app，" +
                "原样回传 transaction_id、observation_version、resume_stage=verify_conversation、visual_verified=true 和输入框中心 action_x/action_y。" +
                "顶部搜索框绝不是消息输入框；标题不符、仍在搜索页或底部输入框不唯一时 visual_verified=false，禁止输入。"
        VisualStage.VERIFY_DRAFT ->
            "必须确认会话名称精确等于“${transaction.contact}”且输入框正文与原始正文逐字一致。" +
                if (transaction.confirmSend) {
                    "确认且发送按钮唯一时调用 send_message_in_app，回传 transaction_id、observation_version、resume_stage=verify_draft、visual_verified=true 和发送按钮中心 action_x/action_y；否则 visual_verified=false。"
                } else {
                    "确认后调用 send_message_in_app，回传 transaction_id、observation_version、resume_stage=verify_draft、visual_verified=true；禁止提供发送坐标。"
                }
        VisualStage.VERIFY_SENT ->
            "只有发送后输入框已清空，并且会话中出现与原始正文逐字一致的新消息时，才调用 send_message_in_app，" +
                "回传 transaction_id、observation_version、resume_stage=verify_sent、visual_verified=true；任一证据缺失都必须 visual_verified=false。"
    }

    private fun requiredVisualPoint(args: JSONObject, transaction: VisualTransaction): Pair<Int, Int>? {
        if (!args.has("action_x") || !args.has("action_y")) return null
        val x = args.optInt("action_x", -1)
        val y = args.optInt("action_y", -1)
        val suppliedVersion = args.optLong("observation_version", 0L)
        return if (VerifiedUiActionExecutor.acceptsObservation(
                transaction.observationVersion,
                suppliedVersion,
                x,
                y,
                transaction.screenshotWidth,
                transaction.screenshotHeight,
            )) {
            x to y
        } else {
            null
        }
    }

    private fun findOrOpenSearchEditor(
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        initialRoot: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? {
        findSearchEditor(initialRoot)?.let { return it }
        val indexed = collectNodes(initialRoot)
        val ranked = SearchTargetResolver.rank(indexed.map { it.snapshot })
        val best = ranked.firstOrNull { it.kind == SearchTargetResolver.Kind.SEARCH_ENTRY } ?: return null
        val actual = indexed.firstOrNull { it.snapshot == best.node }?.node ?: return null
        if (!clickNode(svc, clickableAncestor(actual) ?: actual)) return null
        repeat(8) {
            Thread.sleep(250)
            val root = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
            findSearchEditor(root)?.let { return it }
        }
        return null
    }

    private fun findSearchEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editors = collectNodes(root).filter { it.node.isEditable }
        if (editors.size == 1) return editors.single().node
        val ranked = SearchTargetResolver.rank(editors.map { it.snapshot })
            .filter { it.kind == SearchTargetResolver.Kind.EDITABLE_FIELD }
        val winner = ranked.firstOrNull()?.node ?: return null
        if (ranked.drop(1).firstOrNull()?.score == ranked.first().score) return null
        return editors.firstOrNull { it.snapshot == winner }?.node
    }

    /**
     * Accept an accessibility match only when its nearest preceding result-section header proves
     * that it belongs to contacts. A bare exact text match is insufficient: the same text may be
     * a group name, a group-member hit, or chat-record content. Layouts without section evidence
     * intentionally fall back to the visual transaction.
     */
    private fun exactContactMatches(root: AccessibilityNodeInfo, expected: String): List<AccessibilityNodeInfo> {
        val indexedNodes = collectNodes(root)
        val contactHeaders = setOf("联系人", "contacts", "contact")
        val nonContactHeaders = setOf("群聊", "群组", "聊天记录", "groupchats", "groups", "chathistory")
        val sectionHeaders = indexedNodes.mapNotNull { indexed ->
            val label = normalize(indexed.node.text?.toString().orEmpty())
            when (label) {
                in contactHeaders -> indexed.snapshot.top to true
                in nonContactHeaders -> indexed.snapshot.top to false
                else -> null
            }
        }.sortedBy { it.first }
        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        indexedNodes.forEach { indexed ->
            if (normalize(indexed.node.text?.toString().orEmpty()) != normalize(expected)) return@forEach
            val target = clickableAncestor(indexed.node) ?: return@forEach
            val b = Rect().also { target.getBoundsInScreen(it) }
            val nearestSection = sectionHeaders.lastOrNull { (top, _) -> top < b.centerY() }
            if (nearestSection?.second != true) return@forEach
            unique["${b.left},${b.top},${b.right},${b.bottom}"] = target
        }
        return unique.values.toList()
    }

    private fun hasConversationIdentity(root: AccessibilityNodeInfo, contact: String): Boolean {
        val nodes = collectNodes(root)
        val height = rootBounds(root).height().coerceAtLeast(1)
        val maxTop = height * 30 / 100
        val resultSectionLabels = setOf("联系人", "群聊", "群组", "聊天记录", "contacts", "groupchats", "chathistory")
        if (nodes.any { normalize(it.node.text?.toString().orEmpty()) in resultSectionLabels }) return false
        // A top-half editable field is a search surface, never a conversation composer.
        if (nodes.any { it.node.isEditable && it.snapshot.top < height * 45 / 100 }) return false
        val exactTitle = nodes.any {
            !it.node.isEditable && it.snapshot.top <= maxTop &&
                normalize(it.node.text?.toString().orEmpty()) == normalize(contact)
        }
        val bottomComposer = nodes.any { it.node.isEditable && it.snapshot.top >= height * 45 / 100 }
        return exactTitle && bottomComposer
    }

    private fun selectMessageEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val minTop = rootBounds(root).height().coerceAtLeast(1) * 45 / 100
        val editors = collectNodes(root).filter {
            it.node.isEditable && it.node.isVisibleToUser && it.snapshot.top >= minTop
        }
        if (editors.size == 1) return editors.single().node
        // Conversation composers are normally the lowest visible editor. Require a strict vertical
        // winner; equal/overlapping candidates remain ambiguous and stop the transaction.
        val sorted = editors.sortedByDescending { it.snapshot.bottom }
        val first = sorted.firstOrNull() ?: return null
        val second = sorted.getOrNull(1)
        return if (second == null || first.snapshot.top > second.snapshot.bottom) first.node else null
    }

    private fun findSendAction(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val accepted = setOf("发送", "send")
        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        collectNodes(root).forEach { indexed ->
            val labels = listOf(indexed.node.text, indexed.node.contentDescription)
                .map { normalize(it?.toString().orEmpty()) }
            if (labels.none { it in accepted }) return@forEach
            val target = clickableAncestor(indexed.node) ?: return@forEach
            val b = Rect().also { target.getBoundsInScreen(it) }
            unique["${b.left},${b.top},${b.right},${b.bottom}"] = target
        }
        return unique.values.singleOrNull()
    }

    private fun messageWasSent(root: AccessibilityNodeInfo, message: String): Boolean {
        val editorCleared = collectNodes(root).filter { it.node.isEditable }
            .any { it.node.text?.toString().orEmpty().isEmpty() }
        val visibleMessage = collectNodes(root).any {
            !it.node.isEditable && normalize(it.node.text?.toString().orEmpty()) == normalize(message)
        }
        return editorCleared && visibleMessage
    }

    private fun editorContains(root: AccessibilityNodeInfo, message: String): Boolean =
        collectNodes(root).any { it.node.isEditable && it.node.text?.toString().orEmpty() == message }

    private fun setAndVerify(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        repeat(5) {
            Thread.sleep(150)
            runCatching { node.refresh() }
            if (node.text?.toString().orEmpty() == text) return true
        }
        return false
    }

    private fun waitForChangedRoot(
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        before: Int,
    ): AccessibilityNodeInfo? {
        repeat(10) {
            Thread.sleep(250)
            val root = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
            if (screenFingerprint(root) != before) return root
        }
        return null
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled && candidate.isVisibleToUser) return candidate
            current = candidate.parent
        }
        return null
    }

    private data class IndexedNode(val node: AccessibilityNodeInfo, val snapshot: SearchTargetResolver.Node)

    private fun collectNodes(root: AccessibilityNodeInfo): List<IndexedNode> {
        val out = mutableListOf<IndexedNode>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1400) return
            if (node.isVisibleToUser) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                out += IndexedNode(
                    node,
                    SearchTargetResolver.Node(
                        text = node.text?.toString().orEmpty(),
                        hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else "",
                        description = node.contentDescription?.toString().orEmpty(),
                        resourceId = node.viewIdResourceName.orEmpty(),
                        className = node.className?.toString().orEmpty(),
                        left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                        clickable = node.isClickable, editable = node.isEditable, enabled = node.isEnabled,
                    ),
                )
            }
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out
    }

    private fun rootBounds(root: AccessibilityNodeInfo): Rect = Rect().also { root.getBoundsInScreen(it) }

    private fun screenFingerprint(root: AccessibilityNodeInfo): Int = collectNodes(root).take(160)
        .map { "${it.snapshot.text}|${it.snapshot.description}|${it.snapshot.resourceId}|${it.snapshot.left},${it.snapshot.top}" }
        .hashCode()

    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), "")
}
