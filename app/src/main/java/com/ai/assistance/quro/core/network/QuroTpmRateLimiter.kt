package com.ai.assistance.quro.core.network

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * 进程内滚动 TPM 闸门。它不替代服务端限流，但能阻止同一次 ReAct 任务在一分钟内
 * 反复携带完整上下文，把本机已知请求累计推过上限。
 */
internal class QuroTpmRateLimiter(
    defaultLimit: Int = 200_000,
    private val safetyRatio: Double = 0.88,
    private val windowMs: Long = 60_000L,
) {
    private data class Charge(val atMs: Long, val tokens: Int)

    private val charges = ArrayDeque<Charge>()
    private var limitTokens = defaultLimit.coerceAtLeast(1)
    private var blockedUntilMs = 0L

    @Synchronized
    fun updateLimit(observedLimit: Int?) {
        if (observedLimit != null && observedLimit > 0) limitTokens = observedLimit
    }

    @Synchronized
    fun blockFor(nowMs: Long, delayMs: Long) {
        blockedUntilMs = maxOf(blockedUntilMs, nowMs + delayMs.coerceAtLeast(0L))
    }

    /** 返回 0 表示已预留；正数表示调用方等待后重试本方法。 */
    @Synchronized
    fun reserveOrDelay(nowMs: Long, tokens: Int): Long {
        prune(nowMs)
        if (nowMs < blockedUntilMs) return blockedUntilMs - nowMs

        val requested = tokens.coerceAtLeast(1)
        val safeLimit = (limitTokens * safetyRatio).toInt().coerceAtLeast(1)
        var used = charges.sumOf { it.tokens }
        if (used + requested <= safeLimit || charges.isEmpty() && requested >= safeLimit) {
            charges.addLast(Charge(nowMs, requested))
            return 0L
        }

        for (charge in charges) {
            used -= charge.tokens
            if (used + requested <= safeLimit) {
                return (charge.atMs + windowMs - nowMs).coerceAtLeast(1L) + 250L
            }
        }
        return windowMs + 250L
    }

    @Synchronized
    fun usedTokens(nowMs: Long): Int {
        prune(nowMs)
        return charges.sumOf { it.tokens }
    }

    private fun prune(nowMs: Long) {
        while (charges.isNotEmpty() && nowMs - charges.first().atMs >= windowMs) charges.removeFirst()
    }
}

internal object QuroTpmGate {
    private val limiters = ConcurrentHashMap<String, QuroTpmRateLimiter>()

    private fun key(url: String, model: String): String =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault(url.take(120)) + "|" + model.lowercase()

    suspend fun acquire(url: String, model: String, tokens: Int) {
        val limiter = limiters.computeIfAbsent(key(url, model)) { QuroTpmRateLimiter() }
        while (true) {
            val now = System.currentTimeMillis()
            val waitMs = limiter.reserveOrDelay(now, tokens)
            if (waitMs <= 0L) return
            android.util.Log.w(
                "QuroTokenBudget",
                "TPM_GATE wait_ms=$waitMs request_est=$tokens rolling_used=${limiter.usedTokens(now)} model=${model.take(80)}",
            )
            delay(waitMs.coerceAtMost(60_250L))
        }
    }

    fun observeResponse(url: String, model: String, limitHeader: String?) {
        limiters.computeIfAbsent(key(url, model)) { QuroTpmRateLimiter() }
            .updateLimit(limitHeader?.trim()?.toIntOrNull())
    }

    fun observe429(url: String, model: String, responseText: String, retryMs: Long) {
        val limiter = limiters.computeIfAbsent(key(url, model)) { QuroTpmRateLimiter() }
        val limit = Regex("(?i)limit\\s+([0-9][0-9,]*)")
            .find(responseText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
        limiter.updateLimit(limit)
        limiter.blockFor(System.currentTimeMillis(), retryMs)
    }
}
