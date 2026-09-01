package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.assistance.quro.service.QuroAccessibilityService
import com.ai.assistance.quro.core.privilege.QuroShizukuBridge
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 兼容以 AccessibilityService 基类传递的内部手势辅助方法。 */
private fun AccessibilityService.actionableRoot(): AccessibilityNodeInfo? =
    (this as? QuroAccessibilityService)?.actionableRoot() ?: rootInActiveWindow

/** 让后续读屏/点击继续作用于 Zorv 刚打开的目标 App，而不是 Zorv 自己。 */
internal object ExternalUiTargetSession {
    private const val TAG = "ExternalUiTargetSession"
    private const val PREFS = "external_ui_target"
    private const val MAX_AGE_MS = 15 * 60 * 1000L
    private const val FOREGROUND_SETTLE_ATTEMPTS = 12
    private const val FOREGROUND_SETTLE_DELAY_MS = 125L
    private const val FOREGROUND_STABLE_SAMPLES = 2

    fun remember(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("package", packageName)
            .remove("task_id")
            .remove("top_activity")
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
        // The task is created asynchronously after startActivity. Capture its identity once it
        // exists so returning from Zorv can restore the exact task stack instead of relaunching
        // the package's launcher activity.
        Thread {
            runCatching {
                Thread.sleep(700L)
                captureTask(context.applicationContext, packageName)
            }.onFailure { Log.e(TAG, "Unable to capture external task identity", it) }
        }.start()
    }

    private fun current(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("updated_at", 0L) > MAX_AGE_MS) return null
        return prefs.getString("package", null)?.takeIf { it.isNotBlank() && it != context.packageName }
    }

    internal fun rememberedPackage(context: Context): String? = current(context)

    /**
     * Returning from a target app to Zorv briefly exposes the launcher (or no accessibility
     * root) before QuroMainActivity becomes resumed. A model/tool continuation can arrive in
     * that transition window, so sample for a short bounded period before treating another
     * package as a real unsafe foreground change.
     */
    internal fun <T> awaitTrustedSurface(
        initial: T?,
        ownPackage: String,
        targetPackage: String,
        attempts: Int,
        packageOf: (T?) -> String?,
        next: () -> T?,
    ): T? {
        var candidate = initial
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            val packageName = packageOf(candidate)
            if (packageName == ownPackage || packageName == targetPackage) return candidate
            if (attempt + 1 < attempts) candidate = next()
        }
        return null
    }

    internal fun <T> awaitStableSurface(
        initial: T?,
        expectedPackage: String,
        attempts: Int,
        requiredConsecutive: Int,
        packageOf: (T?) -> String?,
        next: () -> T?,
    ): T? {
        var candidate = initial
        var consecutive = 0
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (packageOf(candidate) == expectedPackage) {
                consecutive += 1
                if (consecutive >= requiredConsecutive.coerceAtLeast(1)) return candidate
            } else {
                consecutive = 0
            }
            if (attempt + 1 < attempts) candidate = next()
        }
        return null
    }

    private fun awaitStableActiveRoot(
        service: QuroAccessibilityService,
        expectedPackage: String,
        initial: AccessibilityNodeInfo? = service.foregroundApplicationRoot(),
        attempts: Int = FOREGROUND_SETTLE_ATTEMPTS,
    ): AccessibilityNodeInfo? = awaitStableSurface(
        initial = initial,
        expectedPackage = expectedPackage,
        attempts = attempts,
        requiredConsecutive = FOREGROUND_STABLE_SAMPLES,
        packageOf = { it?.packageName?.toString() },
        next = {
            Thread.sleep(FOREGROUND_SETTLE_DELAY_MS)
            service.foregroundApplicationRoot()
        },
    )

    fun rootForAutomation(
        service: QuroAccessibilityService,
        settleAttempts: Int = FOREGROUND_SETTLE_ATTEMPTS,
    ): AccessibilityNodeInfo? {
        val target = current(service) ?: return service.actionableRoot()
        // Use only the APPLICATION window Android marks focused/active. On Huawei,
        // rootInActiveWindow can be null while a Zorv overlay is present over WeChat; conversely,
        // selecting an arbitrary application root could accept a stale background app.
        val root = awaitTrustedSurface(
            initial = service.foregroundApplicationRoot(),
            ownPackage = service.packageName,
            targetPackage = target,
            attempts = settleAttempts,
            packageOf = { it?.packageName?.toString() },
            next = {
                Thread.sleep(FOREGROUND_SETTLE_DELAY_MS)
                service.foregroundApplicationRoot()
            },
        ) ?: return null
        val foreground = root.packageName?.toString()
        if (foreground == target) {
            return awaitStableActiveRoot(service, target, root, settleAttempts)
        }
        // Never silently operate on an unrelated external app. The old behaviour accepted any
        // non-Zorv root here, which allowed a stale instruction to click the wrong application.
        if (foreground != service.packageName) return null

        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val taskId = prefs.getInt("task_id", -1).takeIf { it >= 0 }
            ?: discoverTask(service, target)?.taskId
            ?: return null
        val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // MOVE_TASK_WITH_HOME inserts the launcher behind the restored task and causes visible
        // returnToHome transitions on Huawei. A plain task promotion is the intended operation.
        runCatching { activityManager.moveTaskToFront(taskId, 0) }
            .getOrElse { return null }
        return awaitStableActiveRoot(service, target, attempts = settleAttempts)
    }

    fun returnToOwnApp(service: QuroAccessibilityService): Boolean {
        val ownPackage = service.packageName
        // Do not spend the full settle timeout waiting for Zorv when the target app is plainly
        // active. Only perform stability sampling when the first raw root already belongs to Zorv.
        val initial = service.foregroundApplicationRoot()
        if (initial?.packageName?.toString() == ownPackage) {
            return awaitStableActiveRoot(service, ownPackage, initial) != null
        }
        val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ownTaskId = activityManager.appTasks.firstOrNull()?.taskInfo?.taskId
        val promoted = ownTaskId != null && runCatching {
            activityManager.moveTaskToFront(ownTaskId, 0)
            true
        }.getOrDefault(false)
        if (!promoted) {
            val launchIntent = service.packageManager.getLaunchIntentForPackage(ownPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ?: return false
            if (runCatching { service.startActivity(launchIntent) }.isFailure) return false
        }
        return awaitStableActiveRoot(service, ownPackage) != null
    }

    internal data class TaskIdentity(val taskId: Int, val topActivity: String)

    internal fun parseTaskIdentity(output: String, packageName: String): TaskIdentity? {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return null
        val blocks = output.split(Regex("(?m)^\\s*\\* Recent #"))
        for (block in blocks) {
            val belongsToTarget = Regex("(?:A=\\d+:|pkg=|\\{|/)$packageName(?:\\s|/|\\})")
                .containsMatchIn(block)
            if (!belongsToTarget) continue
            val taskId = Regex("\\btaskId=(\\d+)").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("Task\\{[^#]*#(\\d+)").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            // Do not use a brace regex here. Android's ICU regex rejects a closing literal `}`
            // which the desktop JVM Pattern implementation accepts, so JVM tests alone cannot
            // prove that expression is safe on a device.
            val top = block.substringAfter("topActivity={", "")
                .substringBefore("}", "")
                .takeIf { it.isNotBlank() }
                ?: Regex("mActivityComponent=([^\\s]+)").find(block)?.groupValues?.getOrNull(1)
                ?: ""
            return TaskIdentity(taskId, top)
        }
        return null
    }

    private fun captureTask(context: Context, packageName: String) {
        val identity = discoverTask(context, packageName) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("task_id", identity.taskId)
            .putString("top_activity", identity.topActivity)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun discoverTask(context: Context, packageName: String): TaskIdentity? {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return null
        return runCatching {
            val output = QuroShizukuBridge.exec(context, "dumpsys activity recents")
            if (output.startsWith("❌")) null else parseTaskIdentity(output, packageName)
        }.onFailure { Log.e(TAG, "Unable to parse external task identity", it) }.getOrNull()
    }
}

private fun compactUiFingerprint(root: AccessibilityNodeInfo): Int {
    var hash = 1
    var visited = 0
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 16 || visited++ >= 400) return
        val b = Rect().also { node.getBoundsInScreen(it) }
        hash = 31 * hash + listOf(
            node.packageName, node.viewIdResourceName, node.text, node.contentDescription,
            b.left, b.top, b.right, b.bottom,
        ).hashCode()
        for (i in 0 until node.childCount.coerceAtMost(60)) visit(node.getChild(i), depth + 1)
    }
    visit(root, 0)
    return hash
}

/** Screenshot coordinates and Accessibility gestures both use the physical display space. */
private fun physicalDisplaySize(context: Context): Pair<Int, Int> {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    @Suppress("DEPRECATION")
    display.getRealMetrics(metrics)
    return metrics.widthPixels to metrics.heightPixels
}

internal fun visualFingerprint(bitmap: android.graphics.Bitmap): IntArray {
    val grid = 24
    return IntArray(grid * grid) { index ->
        val gx = index % grid
        val gy = index / grid
        val x = ((gx + 0.5f) * bitmap.width / grid).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((gy + 0.5f) * bitmap.height / grid).toInt().coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(x, y)
        (android.graphics.Color.red(pixel) * 30 + android.graphics.Color.green(pixel) * 59 +
            android.graphics.Color.blue(pixel) * 11) / 100
    }
}

internal fun visualFingerprintsDiffer(before: IntArray, after: IntArray): Boolean {
    if (before.size != after.size || before.isEmpty()) return true
    var changed = 0
    var totalDelta = 0L
    for (i in before.indices) {
        val delta = kotlin.math.abs(before[i] - after[i])
        totalDelta += delta
        if (delta >= 18) changed++
    }
    return changed >= before.size / 20 || totalDelta >= before.size * 5L
}

private fun waitForStableSurfaceChange(
    svc: QuroAccessibilityService,
    beforePage: Int,
    beforeVisual: IntArray?,
): Boolean {
    val samples = mutableListOf<Boolean>()
    repeat(10) {
        Thread.sleep(250)
        val root = ExternalUiTargetSession.rootForAutomation(svc)
        val nodeChanged = root != null && root.packageName?.toString() != svc.packageName &&
            compactUiFingerprint(root) != beforePage
        val visualChanged = beforeVisual != null && ScreenshotTool().captureWithAccessibility(svc)?.let { bitmap ->
            try { visualFingerprintsDiffer(beforeVisual, visualFingerprint(bitmap)) }
            finally { bitmap.recycle() }
        } == true
        samples += nodeChanged || visualChanged
        if (VerifiedUiActionExecutor.hasStableChange(samples)) return true
    }
    return false
}

/**
 * L1 无障碍屏幕控制与感知工具集（CapOS 通道）。
 *
 * 通过 QuroAccessibilityService（已声明于 Manifest、用户在系统设置授权后可用）实现：
 *   - 屏幕感知：读取当前界面节点树 / 获取前台应用包名
 *   - 屏幕操控：点击 / 长按 / 文本输入 / 滑动手势 / 滚动列表
 *   - 全局动作：返回键 / 最近任务 / 展开通知栏 / 锁屏
 *
 * 所有工具在无障碍服务未连接时返回友好错误提示，不会崩溃。
 * 工具名称保持与 v108 移除前一致，确保 AI 已有的工具调用知识可直接复用。
 */

// ──────────────────────────── 屏幕感知 ────────────────────────────

/** 读取当前屏幕的 UI 节点树摘要。 */
class ReadScreenTool : QuroTool {
    override val name = "read_screen"
    override val description = "读取当前屏幕的 UI 内容（文本 / 描述 / 资源 ID），返回节点树摘要。无需参数 {}。要求已开启无障碍服务。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接：请到 CapOS 权限子系统 → L1 无障碍服务 → 请求授权"
        return try {
            val root = ExternalUiTargetSession.rootForAutomation(svc)
                ?: return "❌ 无法恢复刚才的目标 App，已停止；不会读取或操作 Zorv 自己"
            val sb = StringBuilder()
            appendNode(root, sb, 0)
            // 另收集可交互元素（clickable/editable/scrollable）做索引，便于 AI 定位「发送/输入框」等
            val interactive = mutableListOf<String>()
            collectInteractive(root, interactive, 0)
            val tree = sb.toString()
            val cappedTree = if (tree.length > 2400) {
                "${tree.take(2400)}\n... (节点树截断，见下方可交互元素索引)"
            } else tree
            val inter = if (interactive.isNotEmpty())
                "\n\n## 可交互元素索引（clickable/editable/scrollable，共 ${interactive.size}）\n" +
                    interactive.take(30).joinToString("\n")
            else "\n\n⚠️ 当前应用未暴露可交互节点；不要重复 read_screen，应改用一次 visual_analysis 视觉定位。"
            (cappedTree + inter).take(3600)
        } catch (e: Exception) {
            "❌ 读取屏幕失败: ${e.message}"
        }
    }

    /** 收集可交互节点（点击/编辑/滚动），输出带坐标的紧凑索引，便于 AI 直接定位「发送」按钮或输入框。 */
    private fun collectInteractive(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || out.size >= 80) return
        if (node.isClickable || node.isEditable || node.isScrollable) {
            val text = node.text?.toString()?.take(40)?.ifBlank { null }
            val desc = node.contentDescription?.toString()?.take(40)?.ifBlank { null }
            val rid = node.viewIdResourceName?.toString()?.substringAfterLast(":")?.take(40)?.ifBlank { null }
            val b = Rect().also { node.getBoundsInScreen(it) }
            val label = text ?: desc ?: rid ?: (node.className?.toString()?.substringAfterLast(".") ?: "?")
            val tag = buildString {
                if (node.isEditable) append(" [editable]")
                if (node.isScrollable) append(" [scroll]")
            }
            out.add("· $label [${b.left},${b.top}][${b.right},${b.bottom}]$tag")
        }
        for (i in 0 until node.childCount.coerceAtMost(50)) {
            node.getChild(i)?.let { collectInteractive(it, out, depth + 1) }
        }
    }

    private fun appendNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth.coerceAtMost(8))
        val text = node.text?.toString()?.take(80)?.ifBlank { null }
        val desc = node.contentDescription?.toString()?.take(80)?.ifBlank { null }
        val rid = node.viewIdResourceName?.toString()?.ifBlank { null }
        val cls = node.className?.toString()?.substringAfterLast(".")?.take(30)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val boundStr = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
        val parts = mutableListOf<String>().apply {
            add(cls ?: "?")
            if (text != null) add("text=\"$text\"")
            if (desc != null) add("desc=\"$desc\"")
            if (rid != null) add("id=$rid")
            add(boundStr)
            if (node.isClickable) add("[clickable]")
            if (node.isScrollable) add("[scrollable]")
            if (node.isEditable) add("[editable]")
            if (node.isChecked) add("[checked=${node.isChecked}]")
        }
        sb.appendLine("$indent${parts.joinToString(" ")}")
        for (i in 0 until node.childCount.coerceAtMost(50)) {
            appendNode(node.getChild(i), sb, depth + 1)
        }
    }
}

/** 获取当前前台应用信息（Activity 组件名）。 */
/** 按目标词定向查找控件，只返回少量匹配项，避免把整棵无障碍树回传给模型。 */
class FindUiElementTool : QuroTool {
    override val name = "find_ui_element"
    override val description = "按文字、内容描述或资源ID定向查找当前屏幕控件，返回匹配项坐标。查询搜索栏时会统一识别搜索入口和可编辑搜索框，并给出下一步。"
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"目标词，如 搜索、提交、输入框"},"max_results":{"type":"integer","minimum":1,"maximum":12,"default":6}},"required":["query"]}"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance
            ?: return "❌ 无障碍服务未连接：请到 CapOS 权限子系统开启"
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val query = args.optString("query").trim()
        if (query.isEmpty()) return "❌ query 不能为空"
        val limit = args.optInt("max_results", 6).coerceIn(1, 12)
        val root = svc.actionableRoot() ?: return "⚠️ 无法获取当前窗口根节点"
        val matches = mutableListOf<String>()
        val searchNodes = mutableListOf<SearchTargetResolver.Node>()
        val searchIntent = SearchTargetResolver.isSearchIntent(query)
        var visited = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visited++ >= 1200) return
            val text = node.text?.toString().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            val desc = node.contentDescription?.toString().orEmpty()
            val rid = node.viewIdResourceName.orEmpty()
            val cls = node.className?.toString().orEmpty()
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (searchIntent && node.isVisibleToUser) {
                searchNodes += SearchTargetResolver.Node(
                    text = text,
                    hint = hint,
                    description = desc,
                    resourceId = rid,
                    className = cls,
                    left = b.left,
                    top = b.top,
                    right = b.right,
                    bottom = b.bottom,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                )
            }
            val haystack = "$text\n$desc\n$rid\n$cls"
            if (!searchIntent && matches.size < limit && haystack.contains(query, ignoreCase = true)) {
                val label = listOf(text, desc, rid.substringAfterLast(':')).firstOrNull { it.isNotBlank() }
                    ?.take(60) ?: cls.substringAfterLast('.').take(40)
                val flags = buildList {
                    if (node.isEditable) add("editable")
                    if (node.isClickable) add("clickable")
                    if (node.isScrollable) add("scrollable")
                }.joinToString(",")
                matches += "· $label [${b.left},${b.top}][${b.right},${b.bottom}]${if (flags.isBlank()) "" else " [$flags]"}"
            }
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let(::visit)
        }
        visit(root)
        if (searchIntent) {
            val candidates = SearchTargetResolver.rank(searchNodes).take(limit)
            if (candidates.isEmpty()) {
                return "⚠️ 未找到搜索候选（已检查 $visited 个节点）。只使用一次 visual_analysis 视觉定位；不要盲目滑动或重复同类查找。"
            }
            val rendered = candidates.mapIndexed { index, candidate ->
                val node = candidate.node
                val label = listOf(node.text, node.hint, node.description, node.resourceId.substringAfterLast(':'))
                    .firstOrNull { it.isNotBlank() }?.take(60) ?: node.className.substringAfterLast('.').take(40)
                val locator = buildList {
                    if (node.resourceId.isNotBlank()) add("target_resource_id=\"${node.resourceId.take(100)}\"")
                    add("target_x=${node.centerX}")
                    add("target_y=${node.centerY}")
                }.joinToString(",")
                val next = when (candidate.kind) {
                    SearchTargetResolver.Kind.EDITABLE_FIELD -> "用上述定位参数调用 input_text"
                    SearchTargetResolver.Kind.SEARCH_ENTRY -> "tap_screen 点击一次，然后重新 read_screen；不要直接输入"
                }
                "${index + 1}. 类型=${candidate.kind} 标签=$label " +
                    "[${node.left},${node.top}][${node.right},${node.bottom}] " +
                    "定位={$locator} 依据=${candidate.reasons.joinToString("+")} 下一步=$next"
            }
            return "✅ 找到 ${candidates.size} 个搜索候选（按可靠性排序，前台=${root.packageName}）：\n${rendered.joinToString("\n")}"
        }
        return if (matches.isEmpty()) {
            "⚠️ 未找到“$query”（已检查 $visited 个节点）。不要重复读取整棵树；只使用一次 visual_analysis 视觉定位。"
        } else {
            "✅ 找到 ${matches.size} 个“$query”匹配项（前台=${root.packageName}）：\n${matches.joinToString("\n")}"
        }
    }
}

class GetForegroundAppTool : QuroTool {
    override val name = "get_foreground_app"
    override val description = "获取当前前台应用包名与 Activity 名称，无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        return try {
            val root = svc.actionableRoot() ?: return "⚠️ 无法获取窗口信息"
            // 从根节点的 packageName 和 Activity 的 viewId 推断
            val pkg = root.packageName?.toString() ?: "未知"
            // Android 5+ 可通过 WindowManager 或 UsageStats 辅助确认
            val info = context.packageManager.getPackageInfo(pkg, 0)
            val label = info.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: pkg
            """{"package":"$pkg","label":"$label"}"""
        } catch (e: Exception) {
            "❌ 获取前台应用失败: ${e.message}"
        }
    }
}

/** 获取屏幕状态（是否亮屏 / 方向 / 尺寸）。 */
class GetScreenStateTool : QuroTool {
    override val name = "get_screen_state"
    override val description = "获取屏幕状态（亮灭 / 方向），无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val pm = context.packageManager
        val display = context.display ?: context.getSystemService(Context.WINDOW_SERVICE)?.let {
            @Suppress("DEPRECATION") it.javaClass.getMethod("getDefaultDisplay").invoke(it)
        } ?: return "❌ 无法获取 Display"
        // 使用 Display API 判断方向
        val rotation = try {
            val m = display.javaClass.getMethod("getRotation")
            when (m.invoke(display) as Int) {
                0 -> "竖直(0°)"
                1 -> "横屏左转(90°)"
                2 -> "倒置(180°)"
                3 -> "横屏右转(270°)"
                else -> "未知"
            }
        } catch (_: Exception) { "未知" }
        val dm = context.resources.displayMetrics
        val (physicalWidth, physicalHeight) = physicalDisplaySize(context)
        return """{"screen_on":true,"rotation":"$rotation","width_px":$physicalWidth,"height_px":$physicalHeight,"density":${dm.densityDpi},"coordinate_space":"physical_display"}"""
    }
}

// ──────────────────────────── 屏幕操控 ────────────────────────────

private const val TAG = "QuroAccTool"

/** 点击屏幕指定坐标或查找包含目标文本的第一个可点击节点并点击。 */
class TapScreenTool : QuroTool {
    override val name = "tap_screen"
    override val description = "点击屏幕上的元素。支持两种模式：(1) 按 x,y 坐标点击；(2) 按文本内容查找并点击第一个匹配的可点击节点。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "x":{"type":"number","description":"X 坐标（像素）"},
            "y":{"type":"number","description":"Y 坐标（像素）"},
            "text":{"type":"string","description":"要点击的按钮/元素的文本内容"},
            "description":{"type":"string","description":"要点击的内容描述（contentDescription）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            when {
                args.has("x") && args.has("y") -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    clickAt(context, svc, x, y)
                }
                args.has("text") -> {
                    val text = args.getString("text")!!
                    findAndClick(context, svc, byText = text)
                }
                args.has("description") -> {
                    val desc = args.getString("description")!!
                    findAndClick(context, svc, byDesc = desc)
                }
                else -> "❌ 缺少参数：需要 x+y / text / description 任一"
            }
        } catch (e: Exception) {
            "❌ 点击失败: ${e.message}"
        }
    }

    private fun clickAt(context: Context, svc: QuroAccessibilityService, x: Float, y: Float): String {
        // 坐标越界保护：派发到屏幕外的手势在高版本可能返回 true 却什么都不做（语义成功≠执行成功）
        val (screenWidth, screenHeight) = physicalDisplaySize(svc)
        if (x < 0 || y < 0 || x >= screenWidth || y >= screenHeight)
            return "❌ 坐标越界(物理屏幕 ${screenWidth}×${screenHeight}): (${x.toInt()},${y.toInt()})"

        val root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ 无法恢复刚才的目标 App，已停止点击；不会误点 Zorv"
        if (root.packageName?.toString() == svc.packageName)
            return "❌ 当前仍是 Zorv，已拒绝对自身界面执行外部点击"
        val beforePage = compactUiFingerprint(root)
        val beforeVisual = ScreenshotTool().captureWithAccessibility(svc)?.let { bitmap ->
            try { visualFingerprint(bitmap) } finally { bitmap.recycle() }
        }
        // 坐标模式：用户给了明确坐标 → 在 (x,y) 精确派发触摸。
        // 关键修复：不再重定向到「可点击祖先的中心」。此前命中一个整行宽度的列表项时，
        // 会去点该容器的中心，导致指定 x=980 实际点中 x=540（系统性坐标偏移）。
        val hit = hitTestNode(root, x, y)
        val label = hit?.let {
            (it.text?.toString() ?: it.contentDescription?.toString()
                ?: it.className?.toString()?.substringAfterLast(".") ?: "节点")
        } ?: "坐标(${x.toInt()},${y.toInt()})"
        val result = VerifiedUiActionExecutor.execute(
            cacheKey = "tap_coordinate",
            retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
            dispatch = { route ->
                when (route) {
                    VerifiedUiActionExecutor.Route.SHIZUKU -> QuroShizukuBridge.exec(
                        context,
                        "input tap ${x.toInt()} ${y.toInt()} && echo ZORV_UI_ACTION_OK",
                    ).contains("ZORV_UI_ACTION_OK")
                    VerifiedUiActionExecutor.Route.ACCESSIBILITY -> tapGestureAt(svc, x, y)
                }
            },
            verify = { waitForStableSurfaceChange(svc, beforePage, beforeVisual) },
        )
        return if (result.verified) "✅ 已点击「$label」，连续两帧页面变化已确认"
        else if (result.uncertainDispatch) "⚠️ 点击最多已尝试一个备用通道，但页面未稳定变化；必须重新观察，禁止复用坐标"
        else "❌ 点击「$label」未能派发"
    }

    /**
     * 对可勾选节点（开关/复选框），点击后重新读取同一中心点的节点状态，
     * 用 isChecked 是否翻转来「真正确认」点击生效——这是少数能闭环验证的场景。
     */
    private fun verifyToggle(svc: AccessibilityService, cx: Float, cy: Float, before: Boolean): String {
        Thread.sleep(300)
        val root = svc.actionableRoot() ?: return "✅ 已派发点击(可勾选节点，回读失败)"
        val hit = hitTestNode(root, cx, cy)
        val node = (hit?.let { findClickableAncestor(it) } ?: hit) ?: return "✅ 已派发点击(可勾选节点，回读失败)"
        val after = node.isChecked
        return if (after != before) "✅ 已点击并确认状态翻转($before→$after)（真实节点中心触摸）"
        else "⚠️ 已派发点击但状态未翻转($before)，可能点击未生效（建议重试）"
    }

    private fun verifyPageChange(
        svc: QuroAccessibilityService,
        before: Int,
        beforeVisual: IntArray?,
        label: String,
    ): String {
        repeat(10) {
            Thread.sleep(250)
            val after = svc.actionableRoot() ?: return@repeat
            if (after.packageName?.toString() != svc.packageName && compactUiFingerprint(after) != before)
                return "✅ 已点击「$label」，页面变化已确认"
        }
        if (beforeVisual != null) {
            val changed = ScreenshotTool().captureWithAccessibility(svc)?.let { bitmap ->
                try { visualFingerprintsDiffer(beforeVisual, visualFingerprint(bitmap)) }
                finally { bitmap.recycle() }
            }
            if (changed == true) return "✅ 已点击「$label」，视觉页面变化已确认"
        }
        return "⚠️ 点击动作已派发，但无障碍与视觉均未确认页面变化；禁止自动重试同一坐标"
    }

    /**
     * 命中测试：返回包含 (x,y) 的最深节点（不限是否可点击）。
     * 调用方再用 [findClickableAncestor] 向上回溯到真正可点击的祖先——
     * 很多可点项的文本在不可点子节点、点击监听在父容器，必须点父容器才生效。
     */
    private fun hitTestNode(root: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                candidates.add(node to depth)
            }
            for (i in 0 until node.childCount.coerceAtMost(80)) {
                node.getChild(i)?.let { collect(it, depth + 1) }
            }
        }
        collect(root, 0)
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * 从给定节点向上回溯到最近的「可点击」祖先（含自身）。
     * ColorOS / 多数列表项的可点击容器是父级，文本/图标在不可点子节点——
     * 只对子节点 performAction 往往返回 true 却什么都不做，必须点父容器。
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var guard = 0
        while (cur != null && !cur.isClickable && guard++ < 24) {
            cur = cur.parent
        }
        return cur
    }

    /**
     * 在指定屏幕坐标派发一次真实触摸手势（UIAutomator 同款可靠方案）。
     * 相比 performAction(ACTION_CLICK)，真触摸事件在 ColorOS 等定制 View 上更不容易被吞。
     * 返回系统是否成功「派发」手势（派发≠命中，但点的是真实节点中心，命中概率极高）。
     */
    private fun tapGestureAt(svc: AccessibilityService, cx: Float, cy: Float): Boolean {
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return svc.dispatchGesture(gd, null, null)
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        done.await(2, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun findAndClick(context: Context, svc: QuroAccessibilityService, byText: String? = null, byDesc: String? = null): String {
        // 按文本/描述查找（不要求节点本身可点击，因为可点监听常在父容器）；找不到则延迟重试一次（应对页面跳变）
        var root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ 无法恢复刚才的目标 App，已停止点击；不会误点 Zorv"
        if (root.packageName?.toString() == svc.packageName)
            return "❌ 当前仍是 Zorv，已拒绝对自身界面执行外部点击"
        val beforePage = compactUiFingerprint(root)
        val beforeVisual = ScreenshotTool().captureWithAccessibility(svc)?.let { bitmap ->
            try { visualFingerprint(bitmap) } finally { bitmap.recycle() }
        }
        var node = findNode(root, byText, byDesc, 0)
        if (node == null) {
            Thread.sleep(250)
            root = ExternalUiTargetSession.rootForAutomation(svc) ?: return "❌ 目标 App 窗口已丢失"
            node = findNode(root, byText, byDesc, 0)
        }
        if (node == null) return "❌ 未找到匹配节点: ${byText ?: byDesc}"
        // 向上回溯到可点击祖先，再点其真实中心（解决「点了文本子节点却没反应」）
        val target = findClickableAncestor(node) ?: node
        val r = Rect().also { target.getBoundsInScreen(it) }
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val label = (target.text?.toString() ?: target.contentDescription?.toString() ?: byText ?: byDesc ?: "节点")
        val result = VerifiedUiActionExecutor.execute(
            cacheKey = "tap_structured_node",
            retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
            dispatch = { route ->
                when (route) {
                    VerifiedUiActionExecutor.Route.SHIZUKU -> QuroShizukuBridge.exec(
                        context,
                        "input tap ${cx.toInt()} ${cy.toInt()} && echo ZORV_UI_ACTION_OK",
                    ).contains("ZORV_UI_ACTION_OK")
                    VerifiedUiActionExecutor.Route.ACCESSIBILITY ->
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapGestureAt(svc, cx, cy)
                }
            },
            verify = { waitForStableSurfaceChange(svc, beforePage, beforeVisual) },
        )
        return if (result.verified) "✅ 已点击「$label」，连续两帧页面变化已确认"
        else if (result.uncertainDispatch) "⚠️ 点击最多已尝试一个备用通道，但页面未稳定变化；必须重新观察"
        else "❌ 点击「$label」失败"
    }

    private fun findNode(root: AccessibilityNodeInfo, byText: String?, byDesc: String?, depth: Int): AccessibilityNodeInfo? {
        if (depth > 18) return null
        val t = root.text?.toString()
        val d = root.contentDescription?.toString()
        // 精确 + 模糊匹配文本/描述（不要求 isClickable，点击时再向上回溯可点祖先）
        if (byText != null) {
            if (t == byText || t?.contains(byText) == true) return root
        }
        if (byDesc != null) {
            if (d == byDesc || d?.contains(byDesc) == true) return root
        }
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, byText, byDesc, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** 长按屏幕元素（x,y 坐标 或 文本/描述查找），用于触发长按菜单（选择/弹出菜单/拖拽预备/应用卸载等）。 */
class LongPressScreenTool : QuroTool {
    override val name = "long_press_screen"
    override val description = "长按屏幕上的元素，触发长按菜单/选择/拖拽预备。支持三种定位：(1) x,y 坐标长按；(2) 按文本内容查找并长按；(3) 按内容描述(description)查找并长按。duration_ms 可选，默认 600ms。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "x":{"type":"number","description":"X 坐标（像素）"},
            "y":{"type":"number","description":"Y 坐标（像素）"},
            "text":{"type":"string","description":"要长按的按钮/元素的文本内容"},
            "description":{"type":"string","description":"要长按的内容描述（contentDescription）"},
            "duration_ms":{"type":"number","description":"长按持续时间毫秒（默认 600，范围 200-3000）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        val duration = args.optLong("duration_ms", 600L).coerceIn(200L, 3000L)
        return try {
            val (cx, cy, label) = when {
                args.has("x") && args.has("y") -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    Triple(x, y, "坐标(${x.toInt()},${y.toInt()})")
                }
                args.has("text") -> {
                    val node = svc.actionableRoot()?.let { findNodeLp(it, args.getString("text"), null, 0) }
                        ?: return "❌ 未找到文本匹配节点: ${args.getString("text")}"
                    val t = findClickableAncestorLp(node) ?: node
                    val r = Rect().also { t.getBoundsInScreen(it) }
                    Triple((r.left + r.right) / 2f, (r.top + r.bottom) / 2f,
                        (t.text?.toString() ?: t.contentDescription?.toString() ?: args.getString("text")))
                }
                args.has("description") -> {
                    val node = svc.actionableRoot()?.let { findNodeLp(it, null, args.getString("description"), 0) }
                        ?: return "❌ 未找到描述匹配节点: ${args.getString("description")}"
                    val t = findClickableAncestorLp(node) ?: node
                    val r = Rect().also { t.getBoundsInScreen(it) }
                    Triple((r.left + r.right) / 2f, (r.top + r.bottom) / 2f,
                        (t.contentDescription?.toString() ?: t.text?.toString() ?: args.getString("description")))
                }
                else -> return "❌ 缺少参数：需要 x+y / text / description 任一"
            }
            // 坐标越界保护
            val (screenWidth, screenHeight) = physicalDisplaySize(svc)
            if (cx < 0 || cy < 0 || cx >= screenWidth || cy >= screenHeight)
                return "❌ 坐标越界(物理屏幕 ${screenWidth}×${screenHeight}): (${cx.toInt()},${cy.toInt()})"
            val dispatched = dispatchLongPress(svc, cx, cy, duration)
            if (dispatched) "✅ 已长按「$label」(${cx.toInt()},${cy.toInt()}，约 ${duration}ms)"
            else "❌ 长按手势被系统拒绝派发（建议重试）"
        } catch (e: Exception) {
            "❌ 长按失败: ${e.message}"
        }
    }

    private fun dispatchLongPress(svc: AccessibilityService, cx: Float, cy: Float, dur: Long): Boolean {
        // 在同一点保持 dur 毫秒即构成长按手势
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return svc.dispatchGesture(gd, null, null)
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        done.await(4, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun findClickableAncestorLp(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var guard = 0
        while (cur != null && !cur.isClickable && guard++ < 24) cur = cur.parent
        return cur
    }

    private fun findNodeLp(root: AccessibilityNodeInfo, byText: String?, byDesc: String?, depth: Int): AccessibilityNodeInfo? {
        if (depth > 18) return null
        val t = root.text?.toString()
        val d = root.contentDescription?.toString()
        if (byText != null) { if (t == byText || t?.contains(byText) == true) return root }
        if (byDesc != null) { if (d == byDesc || d?.contains(byDesc) == true) return root }
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val child = root.getChild(i) ?: continue
            val found = findNodeLp(child, byText, byDesc, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** 在屏幕上滑动（上滑 / 下滑 / 左滑 / 右滑 / 自定义起止坐标）。 */
class SwipeScreenTool : QuroTool {
    override val name = "swipe_screen"
    override val description = "在屏幕上执行滑动手势。支持预设方向或自定义起止坐标。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "direction":{"type":"string","enum":["up","down","left","right"],"description":"滑动方向（默认 up 即向上划/内容向下滚动）"},
            "start_x":{"type":"number","description":"起点 X 像素"},
            "start_y":{"type":"number","description":"起点 Y 像素"},
            "end_x":{"type":"number","description":"终点 X 像素"},
            "end_y":{"type":"number","description":"终点 Y 像素"},
            "duration_ms":{"type":"number","description":"手势持续时间毫秒（默认 300）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            val (screenWidth, screenHeight) = physicalDisplaySize(context)
            val w = screenWidth.toFloat()
            val h = screenHeight.toFloat()
            val duration = args.optLong("duration_ms", 300L)

            val (sx, sy, ex, ey) = if (args.has("start_x") && args.has("start_y")) {
                Quadruple(
                    args.getDouble("start_x").toFloat(),
                    args.getDouble("start_y").toFloat(),
                    args.getDouble("end_x").toFloat(),
                    args.getDouble("end_y").toFloat(),
                )
            } else {
                when (args.optString("direction", "up")) {
                    "down" -> Quadruple(w / 2f, h * 0.3f, w / 2f, h * 0.7f)   // 下滑
                    "left" -> Quadruple(w * 0.7f, h / 2f, w * 0.3f, h / 2f) // 左滑
                    "right" -> Quadruple(w * 0.3f, h / 2f, w * 0.7f, h / 2f)// 右滑
                    else -> Quadruple(w / 2f, h * 0.7f, w / 2f, h * 0.3f)  // 上滑(默认)
                }
            }
            if (listOf(sx, ex).any { it < 0 || it >= w } || listOf(sy, ey).any { it < 0 || it >= h }) {
                return "❌ 滑动坐标越界(物理屏幕 ${screenWidth}×${screenHeight})"
            }
            val root = ExternalUiTargetSession.rootForAutomation(svc)
                ?: return "❌ 无法恢复目标 App，拒绝滑动"
            val beforePage = compactUiFingerprint(root)
            val beforeVisual = ScreenshotTool().captureWithAccessibility(svc)?.let { bitmap ->
                try { visualFingerprint(bitmap) } finally { bitmap.recycle() }
            }
            val result = VerifiedUiActionExecutor.execute(
                cacheKey = "swipe_screen",
                retrySafety = VerifiedUiActionExecutor.RetrySafety.SAFE_TO_REPEAT,
                dispatch = { route ->
                    when (route) {
                        VerifiedUiActionExecutor.Route.SHIZUKU -> QuroShizukuBridge.exec(
                            context,
                            "input swipe ${sx.toInt()} ${sy.toInt()} ${ex.toInt()} ${ey.toInt()} $duration && echo ZORV_UI_ACTION_OK",
                        ).contains("ZORV_UI_ACTION_OK")
                        VerifiedUiActionExecutor.Route.ACCESSIBILITY ->
                            dispatchSwipeGesture(svc, sx, sy, ex, ey, duration)
                    }
                },
                verify = { waitForStableSurfaceChange(svc, beforePage, beforeVisual) },
            )
            if (result.verified) "✅ 已滑动并通过连续两帧页面变化确认结果"
            else if (result.uncertainDispatch) "⚠️ 滑动最多已尝试一个备用通道，但页面未稳定变化；必须重新观察"
            else "❌ 滑动动作未能派发"
        } catch (e: Exception) {
            "❌ 滑动失败: ${e.message}"
        }
    }

    private data class Quadruple(val f1: Float, val f2: Float, val f3: Float, val f4: Float)

    private fun dispatchSwipeGesture(svc: AccessibilityService, sx: Float, sy: Float, ex: Float, ey: Float, dur: Long): Boolean {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return svc.dispatchGesture(gd, null, null)
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        done.await(2, TimeUnit.SECONDS)
        return ok.get()
    }
}

/** 在可编辑框内输入文本（先查找再输入）。 */
class InputTextTool : QuroTool {
    override val name = "input_text"
    override val description = "在屏幕上定向找到输入框并填入文本。搜索任务必须先用 find_ui_element(query=\"搜索\") 找入口；可通过资源ID、坐标、hint/text/description 定位，多个输入框时必须明确定位。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "text":{"type":"string","description":"要输入的文本内容（必填）"},
            "hint":{"type":"string","description":"输入框的 hint 文本（可选定位用）"},
            "target_text":{"type":"string","description":"输入框当前的文本（可选定位用）"},
            "target_desc":{"type":"string","description":"输入框的 contentDescription（可选定位用）"},
            "target_resource_id":{"type":"string","description":"输入框完整资源ID或末段ID（优先定位）"},
            "target_x":{"type":"integer","description":"输入框内的屏幕X坐标，须与 target_y 同时提供"},
            "target_y":{"type":"integer","description":"输入框内的屏幕Y坐标，须与 target_x 同时提供"}
        },
        "required":["text"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = runCatching { JSONObject(arguments) }.getOrElse { return "❌ 参数不是有效 JSON" }
        val text = args.optString("text", "")
        if (text.isEmpty()) return "❌ 缺少 text 参数"
        val hasX = args.has("target_x")
        val hasY = args.has("target_y")
        if (hasX != hasY) return "❌ target_x 与 target_y 必须同时提供"
        return try {
            // If Zorv launched an external app, restore and validate that exact task before
            // touching any editor.  Falling back to actionableRoot() here used to let a stale
            // Zorv chat composer receive the external contact/search text.
            var root = ExternalUiTargetSession.rootForAutomation(svc)
                ?: return "❌ 无法恢复目标 App，拒绝向当前输入框写入文字"
            var editables = collectEditables(root)
            var snapshots = editables.map { toResolverNode(it) }
            var selection = SearchTargetResolver.selectEditable(
                nodes = snapshots,
                targetResourceId = args.optNonBlank("target_resource_id"),
                targetX = if (hasX) args.optInt("target_x") else null,
                targetY = if (hasY) args.optInt("target_y") else null,
                hint = args.optNonBlank("hint"),
                targetText = args.optNonBlank("target_text"),
                targetDescription = args.optNonBlank("target_desc"),
            )
            val searchIntent = SearchTargetResolver.isSearchIntent(
                listOf(
                    args.optString("hint"),
                    args.optString("target_desc"),
                    args.optString("target_resource_id"),
                ).joinToString(" "),
            )
            if (selection.index == null && searchIntent) {
                val opened = openSearchEntry(svc, root)
                if (opened) {
                    repeat(6) {
                        Thread.sleep(250)
                        root = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
                        editables = collectEditables(root)
                        if (editables.isNotEmpty()) return@repeat
                    }
                    snapshots = editables.map { toResolverNode(it) }
                    selection = SearchTargetResolver.selectEditable(
                        nodes = snapshots,
                        targetResourceId = args.optNonBlank("target_resource_id"),
                        targetX = null,
                        targetY = null,
                        hint = args.optNonBlank("hint"),
                        targetText = args.optNonBlank("target_text"),
                        targetDescription = args.optNonBlank("target_desc"),
                    )
                    // Search pages often replace the entry with one unambiguously focused
                    // editor whose hint/description differs from the landing-page label.
                    if (selection.index == null && editables.size == 1) {
                        selection = SearchTargetResolver.EditableSelection(
                            SearchTargetResolver.SelectionStatus.MATCH,
                            index = 0,
                        )
                    }
                }
            }
            if (selection.status == SearchTargetResolver.SelectionStatus.AMBIGUOUS) {
                val candidates = selection.candidateIndexes.take(8).mapIndexed { index, nodeIndex ->
                    val node = snapshots[nodeIndex]
                    val label = listOf(node.hint, node.text, node.description, node.resourceId.substringAfterLast(':'))
                        .firstOrNull { it.isNotBlank() } ?: node.className.substringAfterLast('.')
                    "${index + 1}. $label [${node.left},${node.top}][${node.right},${node.bottom}] " +
                        "target_resource_id=${node.resourceId.ifBlank { "无" }} target_x=${node.centerX} target_y=${node.centerY}"
                }
                return "❌ 当前有多个输入框，拒绝猜测。请根据候选传 target_resource_id 或 target_x+target_y：\n${candidates.joinToString("\n")}"
            }
            val selectedIndex = selection.index ?: run {
                val externalPackage = root.packageName?.toString()
                val hasFocusedIme = svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                if (externalPackage != null && externalPackage != context.packageName && hasFocusedIme) {
                    return PasteFocusedTextTool().run(
                        context,
                        JSONObject().put("text", text).toString(),
                    )
                }
                return noEditableFallback(root)
            }
            val target = editables[selectedIndex]
            runCatching { target.refresh() }
            val before = target.text?.toString().orEmpty()
            if (inputMatches(text, before)) {
                "✅ 输入框已经精确等于目标文字；未重复写入。禁止再次调用 input_text，请继续提交/搜索。"
            } else {
                // Gotcha-style: ACTION_SET_TEXT atomically replaces the whole editor value.
                // Never paste first: paste may append to an existing suggestion/value and
                // trigger an input-method delete/reinsert loop.
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val arg = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arg)
                if (set && verifyInput(target, text)) {
                    "✅ 已原子覆盖并回读确认 ${text.length} 个字符；禁止再次调用 input_text，请继续提交/搜索。"
                } else {
                    // Clipboard fallback is allowed only after an independently verified clear.
                    val clearArgs = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }
                    val cleared = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs) &&
                        verifyInput(target, "")
                    if (!cleared) {
                        "❌ 无法可靠清空原文字，已停止输入以防追加和删除循环；禁止重复调用 input_text。"
                    } else {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
                        Thread.sleep(100)
                        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        if (pasted && verifyInput(target, text)) {
                            "✅ 已清空后粘贴并回读确认 ${text.length} 个字符；禁止再次调用 input_text，请继续提交/搜索。"
                        } else {
                            "❌ 清空后输入仍未通过回读验证，已停止；禁止重复调用 input_text或报告任务完成。"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "❌ 输入文本失败: ${e.message}"
        }
    }

    private fun collectEditables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1200 || result.size >= 80) return
            if (node.isEditable && node.isVisibleToUser) result += node
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    private fun verifyInput(target: AccessibilityNodeInfo, expected: String): Boolean {
        repeat(4) {
            Thread.sleep(150)
            runCatching { target.refresh() }
            val observed = runCatching { target.text?.toString().orEmpty() }.getOrDefault("")
            if (inputMatches(expected, observed)) return true
        }
        return false
    }

    companion object {
        internal fun inputMatches(expected: String, observed: String): Boolean =
            normalizeInput(expected) == normalizeInput(observed)

        internal fun noEditableResult(candidate: SearchTargetResolver.Candidate?): String {
            if (candidate == null) {
                return "❌ 未找到可编辑输入框。请重新 read_screen / find_ui_element 定位；如需视觉判断，应单独调用 visual_analysis，当前 input_text 未执行，禁止报告任务完成。"
            }
            val node = candidate.node
            val locator = "target_x=${node.centerX}, target_y=${node.centerY}"
            return when (candidate.kind) {
                SearchTargetResolver.Kind.SEARCH_ENTRY ->
                    "❌ 当前没有可编辑输入框；发现 SEARCH_ENTRY [$locator]。下一步只点击该入口一次并重新 read_screen；当前 input_text 未执行，禁止报告任务完成。"
                SearchTargetResolver.Kind.EDITABLE_FIELD ->
                    "❌ 输入框定位不明确；发现 EDITABLE_FIELD [$locator]。请用该坐标重新调用 input_text；当前没有通过输入回读验证，禁止报告任务完成。"
            }
        }

        private fun normalizeInput(value: String): String =
            value.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun noEditableFallback(root: AccessibilityNodeInfo): String {
        val nodes = mutableListOf<SearchTargetResolver.Node>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1200) return
            if (node.isVisibleToUser) nodes += toResolverNode(node)
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        // input_text 必须只表示实际输入动作。视觉截图属于感知动作，不能从这里返回
        // {status:"captured"} 并被通用工具状态机误判为输入成功。
        return noEditableResult(SearchTargetResolver.rank(nodes).firstOrNull())
    }

    /** Open a ranked search entry once, then let the normal editable/readback path continue. */
    private fun openSearchEntry(svc: QuroAccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val actual = mutableListOf<AccessibilityNodeInfo>()
        val snapshots = mutableListOf<SearchTargetResolver.Node>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1200) return
            if (node.isVisibleToUser) {
                actual += node
                snapshots += toResolverNode(node)
            }
            for (i in 0 until node.childCount.coerceAtMost(60)) {
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        val candidate = SearchTargetResolver.rank(snapshots)
            .firstOrNull { it.kind == SearchTargetResolver.Kind.SEARCH_ENTRY }
            ?: return false
        val index = snapshots.indexOf(candidate.node)
        val node = actual.getOrNull(index) ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        val path = Path().apply { moveTo(candidate.node.centerX.toFloat(), candidate.node.centerY.toFloat()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    private fun toResolverNode(node: AccessibilityNodeInfo): SearchTargetResolver.Node {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return SearchTargetResolver.Node(
            text = node.text?.toString().orEmpty(),
            hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else "",
            description = node.contentDescription?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
        )
    }

    private fun JSONObject.optNonBlank(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() }
}

/** One-call generic app search transaction: launch → locate → edit → submit → verify. */
class SearchInAppTool : QuroTool {
    override val name = "search_in_app"
    override val description = "用户要求“打开某 App 搜索联系人/商品/内容”时必须直接调用本工具。内部按顺序打开 App、识别搜索栏、确认可编辑框并输入；不要先调用 search_and_launch_app，也不要直接调用 input_text。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "app_name":{"type":"string","description":"目标应用显示名"},
            "query":{"type":"string","description":"要搜索的文字"}
        },
        "required":["app_name","query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { return "❌ 参数不是有效 JSON" }
        val appName = args.optString("app_name").trim()
        val query = args.optString("query").trim()
        if (appName.isEmpty() || query.isEmpty()) return "❌ app_name 和 query 均不能为空"
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"

        val launched = SearchAndLaunchAppTool().run(
            context,
            JSONObject().put("app_name", appName).toString(),
        )
        if (!launched.startsWith("已")) return "❌ [打开应用] $launched"
        Thread.sleep(1200)

        val activated = activateSearchFromCurrentOrAncestor(context, svc)
        if (!activated.startsWith("✅")) return "❌ [打开搜索栏] $activated"

        val inputResult = InputTextTool().run(
            context,
            JSONObject().put("text", query).put("hint", "搜索").toString(),
        )
        if (inputResult.contains("FOCUSED_PASTE_PENDING_VERIFICATION")) {
            return "⚠️ [SEARCH_QUERY_PENDING_VISUAL_VERIFICATION] 已在$appName 激活搜索并向聚焦框输入“$query”；" +
                "目标应用未暴露可回读节点，必须下一轮截图核对，不得直接报告完成"
        }
        if (!inputResult.startsWith("✅")) return "❌ [填入文字] $inputResult"

        var root = ExternalUiTargetSession.rootForAutomation(svc)
            ?: return "❌ [按搜索] 目标应用窗口已丢失"
        val editables = collectVisibleEditables(root)
        if (editables.isEmpty()) return "❌ [按搜索] 已输入但无法重新取得搜索编辑框"

        val rankedEditors = SearchTargetResolver.rank(editables.map(::snapshotNode))
            .filter { it.kind == SearchTargetResolver.Kind.EDITABLE_FIELD }
        val target = when {
            rankedEditors.isNotEmpty() -> {
                val wanted = rankedEditors.first().node
                editables.firstOrNull { snapshotNode(it) == wanted }
            }
            editables.size == 1 -> editables.single()
            else -> null
        } ?: return "❌ [检测输入框] 存在多个编辑框，无法可靠确定搜索框"

        root = ExternalUiTargetSession.rootForAutomation(svc) ?: return "❌ [按搜索] 无法读取提交界面"
        val submit = findSubmitNode(root)
        val before = fingerprint(root)
        val submitted = if (submit != null) {
            submit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else false
        if (!submitted) return "❌ [按搜索] 未找到搜索按钮且键盘搜索动作不可用"

        repeat(8) {
            Thread.sleep(250)
            val after = ExternalUiTargetSession.rootForAutomation(svc) ?: return@repeat
            if (fingerprint(after) != before) {
                return "✅ [SEARCH_TRANSACTION_COMPLETE] 已在$appName 搜索“$query”，结果页面变化已确认"
            }
        }
        return "❌ [验证结果] 已按搜索但页面没有变化，未确认任务完成"
    }

    /**
     * App search is a top-level task, while Android commonly restores the exact previous page
     * (for example, a chat or product detail).  If that page has no global-search entry, walk up
     * a small number of levels.  Every Back is guarded by the remembered package before and after
     * the action so a missing search icon can never turn into blind navigation outside the target.
     */
    private fun activateSearchFromCurrentOrAncestor(
        context: Context,
        svc: QuroAccessibilityService,
    ): String {
        var result = ActivateAppSearchTool().run(context, "{}")
        if (result.startsWith("✅")) return result

        val targetPackage = ExternalUiTargetSession.rememberedPackage(context)
            ?: return result
        val failures = mutableListOf("当前页: $result")
        for (step in 0 until SearchPageBacktrackPolicy.MAX_BACK_STEPS) {
            val beforePackage = svc.actionableRoot()?.packageName?.toString()
            if (!SearchPageBacktrackPolicy.mayGoBack(step, targetPackage, beforePackage)) {
                return "❌ 搜索页回溯前台校验失败，已停止；${failures.joinToString(" | ")}"
            }
            if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                return "❌ 第${step + 1}次返回动作未能派发，已停止；${failures.joinToString(" | ")}"
            }

            var afterPackage: String? = null
            repeat(10) {
                Thread.sleep(200)
                afterPackage = svc.actionableRoot()?.packageName?.toString()
                if (afterPackage != beforePackage || afterPackage == targetPackage) return@repeat
            }
            if (!SearchPageBacktrackPolicy.mayRetryActivation(targetPackage, afterPackage)) {
                return "❌ 返回后已离开目标 App（${afterPackage ?: "无前台窗口"}），为防止误操作已停止"
            }

            result = ActivateAppSearchTool().run(context, "{}")
            if (result.startsWith("✅")) {
                return "✅ [SEARCH_ACTIVATED_AFTER_BACK] 返回 ${step + 1} 层后已确认搜索框；$result"
            }
            failures += "返回${step + 1}层: $result"
        }
        return "❌ 已在目标 App 内安全回溯 ${SearchPageBacktrackPolicy.MAX_BACK_STEPS} 层，仍未找到可验证的全局搜索入口；" +
            failures.joinToString(" | ")
    }

    private fun collectIndexedNodes(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, SearchTargetResolver.Node>> {
        val out = mutableListOf<Pair<AccessibilityNodeInfo, SearchTargetResolver.Node>>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1200) return
            if (node.isVisibleToUser) out += node to snapshotNode(node)
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out
    }

    private fun collectVisibleEditables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        collectIndexedNodes(root).filter { it.first.isEditable }.map { it.first }

    private fun snapshotNode(node: AccessibilityNodeInfo): SearchTargetResolver.Node {
        val b = Rect().also { node.getBoundsInScreen(it) }
        return SearchTargetResolver.Node(
            text = node.text?.toString().orEmpty(),
            hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else "",
            description = node.contentDescription?.toString().orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            clickable = node.isClickable, editable = node.isEditable, enabled = node.isEnabled,
        )
    }

    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean {
        repeat(5) {
            Thread.sleep(150)
            runCatching { node.refresh() }
            if (node.text?.toString().orEmpty() == expected) return true
        }
        return false
    }

    private fun findSubmitNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var cur: AccessibilityNodeInfo? = node
            repeat(8) {
                val n = cur ?: return null
                if (n.isClickable && n.isEnabled && n.isVisibleToUser) return n
                cur = n.parent
            }
            return null
        }
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16 || visited++ >= 1200) return
            val label = listOf(node.text, node.contentDescription).joinToString(" ") { it?.toString().orEmpty() }
                .lowercase().replace(Regex("[\\s_\\-.:/]+"), "")
            if (!node.isEditable && label in setOf("搜索", "查找", "search")) {
                clickable(node)?.let { hit ->
                    val b = Rect().also { hit.getBoundsInScreen(it) }
                    candidates += ((if (b.top < 500) 10_000 else 0) + b.centerX() - b.width()) to hit
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(60)) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun dispatchTap(svc: QuroAccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    private fun fingerprint(root: AccessibilityNodeInfo): String = collectIndexedNodes(root)
        .take(120)
        .joinToString("\n") { (_, n) -> "${n.text}|${n.description}|${n.resourceId}|${n.left},${n.top},${n.right},${n.bottom}" }
}

internal object SearchPageBacktrackPolicy {
    const val MAX_BACK_STEPS = 3

    fun mayGoBack(step: Int, targetPackage: String, foregroundPackage: String?): Boolean =
        step in 0 until MAX_BACK_STEPS && targetPackage.isNotBlank() && foregroundPackage == targetPackage

    fun mayRetryActivation(targetPackage: String, foregroundPackage: String?): Boolean =
        targetPackage.isNotBlank() && foregroundPackage == targetPackage
}

/** 滚动列表（向前/向后）。 */
class ScrollScreenTool : QuroTool {
    override val name = "scroll_screen"
    override val description = "滚动当前屏幕上的可滚动容器（列表/页面）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "direction":{"type":"string","enum":["forward","backward","up","down"],"description":"滚动方向（默认 forward 向前/向下翻页）"},
            "count":{"type":"integer","description":"滚动次数（默认 3）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            val root = svc.actionableRoot() ?: return "⚠️ 无法获取窗口根节点"
            val dir = args.optString("direction", "forward")
            val count = args.optInt("count", 3).coerceIn(1, 20)
            val action = when (dir) {
                "backward", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            // 找到最近的 scrollable 容器
            var scrolled = 0
            val target = findScrollable(root) ?: root
            repeat(count) {
                if (target.performAction(action)) scrolled++
                Thread.sleep(200)
            }
            "✅ 执行滚动 $dir ×$count 次，实际成功 $scrolled 次"
        } catch (e: Exception) {
            "❌ 滚动失败: ${e.message}"
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            val found = findScrollable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }
}

// ──────────────────────────── 全局动作 ────────────────────────────

/** 执行全局无障碍动作（返回键 / 最近任务 / 展开通知栏等）。 */
class GlobalActionTool : QuroTool {
    override val name = "global_action"
    override val description = "执行全局系统级动作：back（返回）、home（主页）、recents（最近任务）、notifications（展开通知栏）、lock_screen（锁屏）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","enum":["back","home","recents","notifications","quick_settings","power_dialog","lock_screen","take_screenshot"],"description":"要执行的全局动作"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        val actionStr = args.optString("action", "")
        val ga = when (actionStr) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> return "❌ 未知动作: $actionStr（支持: back/home/recents/notifications/quick_settings/power_dialog/lock_screen/take_screenshot）"
        }
        return if (svc.performGlobalAction(ga))
            "✅ 全局动作 $actionStr 执行成功"
        else "❌ 全局动作 $actionStr 执行失败（可能需要更高权限或系统限制）"
    }
}
