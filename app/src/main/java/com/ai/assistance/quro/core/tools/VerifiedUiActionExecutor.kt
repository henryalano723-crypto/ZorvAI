package com.ai.assistance.quro.core.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One bounded action policy shared by visual phone-control transactions.
 *
 * A backend reporting "dispatched" is not success.  Every attempt must pass [verify].  Safe,
 * idempotent focus/navigation actions may try the alternate backend once after an unverified
 * dispatch; irreversible actions must stop and re-observe because retrying could duplicate them.
 */
internal object VerifiedUiActionExecutor {
    enum class Route { SHIZUKU, ACCESSIBILITY }
    enum class RetrySafety { SAFE_TO_REPEAT, DISPATCH_ONCE }
    enum class ObservationMode(val wire: String) {
        STRUCTURED_NODES("xml_nodes"),
        SCREENSHOT_VISUAL("screenshot_visual"),
    }

    data class Result(
        val verified: Boolean,
        val route: Route?,
        val attempted: List<Route>,
        val uncertainDispatch: Boolean,
    )

    private val successfulRoutes = ConcurrentHashMap<String, Route>()
    private val observationSequence = AtomicLong(System.currentTimeMillis())

    fun nextObservationVersion(): Long = observationSequence.incrementAndGet()

    fun observationMode(hasUsableNodes: Boolean): ObservationMode =
        if (hasUsableNodes) ObservationMode.STRUCTURED_NODES else ObservationMode.SCREENSHOT_VISUAL

    internal fun canDispatchInput(focusVerified: Boolean, alreadyAttempted: Boolean): Boolean =
        focusVerified && !alreadyAttempted

    fun execute(
        cacheKey: String,
        retrySafety: RetrySafety,
        dispatch: (Route) -> Boolean,
        verify: () -> Boolean,
    ): Result {
        val preferred = successfulRoutes[cacheKey] ?: Route.SHIZUKU
        val order = listOf(preferred, alternate(preferred))
        val attempted = mutableListOf<Route>()
        var uncertain = false
        for ((index, route) in order.withIndex()) {
            attempted += route
            if (!dispatch(route)) continue
            if (verify()) {
                successfulRoutes[cacheKey] = route
                return Result(true, route, attempted.toList(), uncertainDispatch = false)
            }
            uncertain = true
            if (retrySafety == RetrySafety.DISPATCH_ONCE || index == order.lastIndex) break
        }
        return Result(false, null, attempted.toList(), uncertainDispatch = uncertain)
    }

    internal fun acceptsObservation(
        expectedVersion: Long,
        suppliedVersion: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean = suppliedVersion == expectedVersion && expectedVersion > 0L &&
        x in 0 until width && y in 0 until height

    internal fun hasStableChange(samples: Iterable<Boolean>, requiredConsecutive: Int = 2): Boolean {
        var consecutive = 0
        for (changed in samples) {
            consecutive = if (changed) consecutive + 1 else 0
            if (consecutive >= requiredConsecutive.coerceAtLeast(1)) return true
        }
        return false
    }

    internal fun clearRouteCache() = successfulRoutes.clear()

    internal fun cachedRoute(cacheKey: String): Route? = successfulRoutes[cacheKey]

    private fun alternate(route: Route): Route = when (route) {
        Route.SHIZUKU -> Route.ACCESSIBILITY
        Route.ACCESSIBILITY -> Route.SHIZUKU
    }
}
