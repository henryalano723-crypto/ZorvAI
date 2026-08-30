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
            "resume_stage":{"type":"string","enum":["select_contact","verify_conversation","verify_draft","verify_sent"],"description":"必须与上一次 needs_visual 返回的 stage 完全一致"},
            "visual_verified":{"type":"boolean","description":"视觉上是否满足该阶段 instruction 的全部精确条件；不确定必须 false"},
            "action_x":{"type":"integer","description":"该阶段唯一目标的真实屏幕中心 x；只在 instruction 要求点击时提供"},
            "action_y":{"type":"integer","description":"该阶段唯一目标的真实屏幕中心 y；只在 instruction 要求点击时提供"}
        }
    }"""

    private enum class VisualStage(val wire: String) {
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
    )

    companion object {
        private const val VISUAL_TRANSACTION_TTL_MS = 5 * 60 * 1000L
        private val visualTransactions = ConcurrentHashMap<String, VisualTransaction>()
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

        val launched = SearchAndLaunchAppTool().run(
            context,
            JSONObject().put("app_name", appName).toString(),
        )
        if (!launched.startsWith("已")) return "❌ [打开应用] $launched"
        Thread.sleep(900)
        var root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ [恢复现场] 无法获得目标应用窗口，未执行后续操作"

        if (searchResultsReady) {
            return buildVisualContinuation(
                context = context,
                appName = appName,
                targetPackage = root.packageName?.toString().orEmpty(),
                contact = contact,
                message = message,
                confirmSend = confirmSend,
            )
        }

        var searchEditor = findSearchEditor(root)
        if (searchEditor == null) {
            val activated = ActivateAppSearchTool().run(context, "{}")
            if (!activated.startsWith("✅")) return "❌ [识别搜索入口] $activated"
            root = ExternalUiTargetSession.rootForAutomation(svc)
                ?: return "❌ [恢复搜索现场] 目标应用窗口已丢失"
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
                    )
                } else {
                    "❌ [输入联系人] $pasted"
                }
            }
        }
        searchEditor ?: return "❌ [识别搜索入口] 搜索已激活但没有可靠编辑框或输入法，已停止"
        if (!setAndVerify(searchEditor, contact)) {
            return "❌ [输入联系人] 未能覆盖输入并回读精确联系人，已停止"
        }

        Thread.sleep(700)
        root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ [读取搜索结果] 目标应用窗口已丢失"
        val contactMatch = exactClickableMatches(root, contact)
        if (contactMatch.size != 1) {
            return if (contactMatch.isEmpty()) {
                "❌ [选择联系人] 没有找到精确匹配“$contact”，拒绝点相似结果"
            } else {
                "❌ [选择联系人] 找到 ${contactMatch.size} 个可点击的精确匹配，无法唯一确定，拒绝猜测"
            }
        }
        val beforeConversation = screenFingerprint(root)
        if (!clickNode(svc, contactMatch.single())) return "❌ [选择联系人] 点击动作未能派发"
        root = waitForChangedRoot(svc, beforeConversation)
            ?: return "❌ [选择联系人] 点击后界面没有变化，未确认进入会话"

        if (!hasConversationIdentity(root, contact)) {
            return "❌ [核对会话] 页面顶部未找到精确联系人“$contact”，禁止输入或发送"
        }
        val messageEditor = selectMessageEditor(root, searchEditor)
            ?: return "❌ [定位消息框] 未找到唯一可靠的消息输入框"
        if (!setAndVerify(messageEditor, message)) {
            return "❌ [输入消息] 正文未通过回读验证，禁止发送"
        }
        if (!confirmSend) {
            return "✅ [MESSAGE_DRAFT_VERIFIED] 已核对会话“$contact”并回读确认正文；按授权要求停在草稿，未发送"
        }

        val sendRoot = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ [发送前复核] 目标应用窗口已丢失，禁止发送"
        if (!hasConversationIdentity(sendRoot, contact) || !editorContains(sendRoot, message)) {
            return "❌ [发送前复核] 联系人或正文与授权不一致，禁止发送"
        }
        val sendNode = findSendAction(sendRoot)
            ?: return "❌ [发送] 未找到语义明确且唯一的发送控件，禁止用回车猜测"
        if (!clickNode(svc, sendNode)) return "❌ [发送] 发送控件拒绝点击"

        repeat(10) {
            Thread.sleep(250)
            val after = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
            if (messageWasSent(after, message)) {
                return "✅ [MESSAGE_SEND_CONFIRMED] 已向“$contact”发送，并通过输入框清空及消息正文回读确认"
            }
        }
        return "⚠️ [MESSAGE_SEND_PENDING_VERIFICATION] 已点击发送，但未同时取得输入框清空和消息正文证据；不得报告发送成功"
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
            stage = VisualStage.SELECT_CONTACT,
        )
        visualTransactions[transaction.id] = transaction
        return captureVisualStage(context, transaction)
    }

    private fun resumeVisualTransaction(context: Context, args: JSONObject, transactionId: String): String {
        pruneExpiredVisualTransactions()
        val transaction = visualTransactions[transactionId]
            ?: return "❌ [消息事务已失效] transaction_id 不存在或已超过 5 分钟；禁止凭旧截图继续"
        val requestedStage = args.optString("resume_stage").trim()
        if (requestedStage != transaction.stage.wire) {
            return "❌ [消息事务阶段不匹配] 当前必须处理 ${transaction.stage.wire}，拒绝跳步"
        }
        if (!args.optBoolean("visual_verified", false)) {
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
            VisualStage.SELECT_CONTACT -> {
                val point = requiredVisualPoint(args, transaction) ?: return "❌ [选择联系人] 缺少唯一目标的有效中心坐标"
                val beforeConversation = captureAppSurfaceFingerprint(svc)
                    ?: return "❌ [选择联系人] 点击前无法建立目标页面验证基线，禁止继续"
                if (!dispatchPointClick(svc, point.first.toFloat(), point.second.toFloat())) {
                    return "❌ [选择联系人] 点击动作未能派发"
                }
                if (!waitForStableAppSurfaceChange(svc, beforeConversation)) {
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
                if (!dispatchPointClick(svc, point.first.toFloat(), point.second.toFloat())) {
                    return "❌ [定位消息框] 点击动作未能派发"
                }
                Thread.sleep(250)
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
                if (!dispatchPointClick(svc, point.first.toFloat(), point.second.toFloat())) {
                    return "❌ [发送] 点击动作未能派发"
                }
                Thread.sleep(700)
                transaction.stage = VisualStage.VERIFY_SENT
                captureVisualStage(context, transaction)
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
        val stageQuestion = when (transaction.stage) {
            VisualStage.SELECT_CONTACT ->
                "当前联系人结果列表中，精确定位名称为“${transaction.contact}”的唯一可点击结果"
            VisualStage.VERIFY_CONVERSATION ->
                "核对当前会话顶部名称是否精确等于“${transaction.contact}”，并定位唯一的消息输入框中心"
            VisualStage.VERIFY_DRAFT ->
                "核对当前会话名称为“${transaction.contact}”，且输入框内正文与用户原始正文逐字一致；${if (transaction.confirmSend) "同时定位唯一发送按钮中心" else "不要发送"}"
            VisualStage.VERIFY_SENT ->
                "核对发送后输入框已经清空，并且当前会话中出现与用户原始正文逐字一致的新消息"
        }
        val question = listOfNotNull(retryNotice, stageQuestion).joinToString(" ")
        val captured = VisualAnalysisTool().run(
            context,
            JSONObject().put("question", question).toString(),
        )
        val json = runCatching { JSONObject(captured) }.getOrNull()
            ?: return "❌ [截图核对联系人] $captured"
        if (!json.optBoolean("attach_to_next_model", false)) {
            return "❌ [截图核对联系人] $captured"
        }
        transaction.screenshotWidth = json.optInt("width", 0)
        transaction.screenshotHeight = json.optInt("height", 0)
        if (transaction.screenshotWidth <= 0 || transaction.screenshotHeight <= 0) {
            visualTransactions.remove(transaction.id)
            return "❌ [截图核对] 截图尺寸无效，消息事务已安全终止"
        }
        return json.apply {
            put("status", "needs_visual")
            put("workflow", "message_send")
            put("stage", transaction.stage.wire)
            put("transaction_id", transaction.id)
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
        svc: com.ai.assistance.quro.service.QuroAccessibilityService,
        before: IntArray,
    ): Boolean {
        var consecutiveChangedFrames = 0
        repeat(10) {
            Thread.sleep(250)
            val after = captureAppSurfaceFingerprint(svc)
            if (after != null && visualFingerprintsDiffer(before, after)) {
                consecutiveChangedFrames += 1
                if (consecutiveChangedFrames >= 2) return true
            } else {
                consecutiveChangedFrames = 0
            }
        }
        return false
    }

    private fun visualStageInstruction(transaction: VisualTransaction): String = when (transaction.stage) {
        VisualStage.SELECT_CONTACT ->
            "只有画面中恰好一个结果的可见名称与“${transaction.contact}”完全一致时，才调用 send_message_in_app，" +
                "原样回传 transaction_id、resume_stage=select_contact、visual_verified=true 和该结果中心 action_x/action_y。" +
                "不要再点搜索图标，不要直接调用 tap_screen，不要报告完成；不唯一或不确定时 visual_verified=false。"
        VisualStage.VERIFY_CONVERSATION ->
            "只有顶部会话名称精确等于“${transaction.contact}”且消息输入框唯一时，才调用 send_message_in_app，" +
                "原样回传 transaction_id、resume_stage=verify_conversation、visual_verified=true 和输入框中心 action_x/action_y。" +
                "标题不符或输入框不唯一时 visual_verified=false，禁止输入。"
        VisualStage.VERIFY_DRAFT ->
            "必须确认会话名称精确等于“${transaction.contact}”且输入框正文与原始正文逐字一致。" +
                if (transaction.confirmSend) {
                    "确认且发送按钮唯一时调用 send_message_in_app，回传 transaction_id、resume_stage=verify_draft、visual_verified=true 和发送按钮中心 action_x/action_y；否则 visual_verified=false。"
                } else {
                    "确认后调用 send_message_in_app，回传 transaction_id、resume_stage=verify_draft、visual_verified=true；禁止提供发送坐标。"
                }
        VisualStage.VERIFY_SENT ->
            "只有发送后输入框已清空，并且会话中出现与原始正文逐字一致的新消息时，才调用 send_message_in_app，" +
                "回传 transaction_id、resume_stage=verify_sent、visual_verified=true；任一证据缺失都必须 visual_verified=false。"
    }

    private fun requiredVisualPoint(args: JSONObject, transaction: VisualTransaction): Pair<Int, Int>? {
        if (!args.has("action_x") || !args.has("action_y")) return null
        val x = args.optInt("action_x", -1)
        val y = args.optInt("action_y", -1)
        return if (x in 0 until transaction.screenshotWidth && y in 0 until transaction.screenshotHeight) {
            x to y
        } else {
            null
        }
    }

    private fun pruneExpiredVisualTransactions() {
        val cutoff = System.currentTimeMillis() - VISUAL_TRANSACTION_TTL_MS
        visualTransactions.entries
            .filter { it.value.createdAtMs < cutoff }
            .map { it.key }
            .forEach(visualTransactions::remove)
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

    private fun exactClickableMatches(root: AccessibilityNodeInfo, expected: String): List<AccessibilityNodeInfo> {
        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        collectNodes(root).forEach { indexed ->
            if (normalize(indexed.node.text?.toString().orEmpty()) != normalize(expected)) return@forEach
            val target = clickableAncestor(indexed.node) ?: return@forEach
            val b = Rect().also { target.getBoundsInScreen(it) }
            unique["${b.left},${b.top},${b.right},${b.bottom}"] = target
        }
        return unique.values.toList()
    }

    private fun hasConversationIdentity(root: AccessibilityNodeInfo, contact: String): Boolean {
        val maxTop = root.window?.let { rootBounds(root).height() * 38 / 100 } ?: 900
        return collectNodes(root).any {
            !it.node.isEditable && it.snapshot.top <= maxTop &&
                normalize(it.node.text?.toString().orEmpty()) == normalize(contact)
        }
    }

    private fun selectMessageEditor(root: AccessibilityNodeInfo, oldSearchEditor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editors = collectNodes(root).filter { it.node.isEditable && it.node.isVisibleToUser }
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
