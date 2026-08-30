package com.ai.assistance.quro.core.aidlaci

import android.content.Context

/**
 * Classifies the current user request before tools and ACI capability prompts are exposed.
 * This is deliberately deterministic: a default browser ACI must never hijack an ordinary
 * native-App request merely because the model found browser wording semantically attractive.
 */
object AciTaskRouter {
    enum class Kind { WEB, NATIVE_APP, ACI_APP, GENERAL }

    data class Decision(
        val kind: Kind,
        val allowAciCall: Boolean,
        val targetPackage: String? = null,
        val reason: String,
    )

    private val urlPattern = Regex("(?i)(https?://|www\\.|(?:[a-z0-9-]+\\.)+(?:com|cn|net|org|io|ai)(?:[/\\s]|$))")
    private val webWords = setOf(
        "网页", "网站", "网址", "链接", "浏览器", "打开网页", "访问网页",
        "webpage", "website", "browser", "url", "http://", "https://",
    )
    private val nativeWords = setOf(
        "app", "应用", "当前屏幕", "当前页面", "读屏", "读取屏幕", "点击", "点一下",
        "输入框", "输入", "发送", "滑动", "打开淘宝", "打开微信", "打开豆包", "打开千问",
        "launch app", "open app", "tap", "click", "type into", "current screen",
    )
    private val explicitAciWords = setOf("aci", "能力接口", "受控端")

    fun resolve(context: Context, taskText: String): Decision {
        val manager = runCatching { QuroAidlAciManager.getInstance() }.getOrNull()
        val apps = manager?.getDiscoveredAppNames().orEmpty()
        val browserPackages = manager?.getCapabilityIndex().orEmpty()
            .filterValues { caps -> caps.any { it.id == "browser_open" } }
            .keys
        return classify(
            taskText = taskText,
            autoSelect = AciAppPreferences.isAutoSelect(context),
            defaultPackage = AciAppPreferences.getDefaultPackage(context),
            defaultAppName = AciAppPreferences.getDefaultAppName(context),
            discoveredApps = apps,
            browserPackages = browserPackages,
        )
    }

    fun classify(
        taskText: String,
        autoSelect: Boolean,
        defaultPackage: String?,
        defaultAppName: String?,
        discoveredApps: Map<String, String>,
        browserPackages: Set<String>,
    ): Decision {
        val text = taskText.trim().lowercase()
        val mentionedAci = discoveredApps.entries.firstOrNull { (pkg, name) ->
            pkg.lowercase() in text || name.trim().lowercase().takeIf { it.isNotEmpty() }?.let(text::contains) == true
        }
        if (mentionedAci != null) {
            return Decision(Kind.ACI_APP, true, mentionedAci.key, "task names a discovered ACI app")
        }
        if (!defaultAppName.isNullOrBlank() && text.contains(defaultAppName.lowercase())) {
            return Decision(Kind.ACI_APP, true, defaultPackage, "task names the default ACI app")
        }
        val explicitAci = explicitAciWords.any(text::contains)
        if (explicitAci) {
            return Decision(Kind.ACI_APP, true, defaultPackage, "task explicitly requests ACI")
        }
        val webTask = urlPattern.containsMatchIn(text) || webWords.any(text::contains)
        if (webTask) {
            val browserTarget = when {
                defaultPackage in browserPackages -> defaultPackage
                autoSelect -> browserPackages.firstOrNull()
                else -> null
            }
            return Decision(
                Kind.WEB,
                allowAciCall = autoSelect && browserTarget != null,
                targetPackage = browserTarget,
                reason = if (browserTarget != null && autoSelect) "web task routed to browser ACI" else "web task without auto-selected browser ACI",
            )
        }
        if (nativeWords.any(text::contains)) {
            return Decision(Kind.NATIVE_APP, false, reason = "native App or screen-operation task")
        }
        return Decision(Kind.GENERAL, false, reason = "no explicit web or ACI intent")
    }

    fun filterTools(specs: List<com.ai.assistance.quro.core.QuroToolSpec>, decision: Decision): List<com.ai.assistance.quro.core.QuroToolSpec> {
        if (decision.allowAciCall) return specs
        return specs.filterNot { it.name == "aci_call" }
    }
}
