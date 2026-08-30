package com.ai.assistance.quro.core.tools

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Pixel-only detector for a magnifier in a conventional top app bar. */
internal object LocalSearchIconDetector {
    data class Candidate(val x: Int, val y: Int, val score: Double, val runnerUp: Double)

    fun detect(bitmap: Bitmap): Candidate? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 240 || height < 400) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size) { index ->
            val p = pixels[index]
            (((p shr 16) and 0xff) * 30 + ((p shr 8) and 0xff) * 59 + (p and 0xff) * 11) / 100
        }
        return detect(gray, width, height)
    }

    internal fun detect(gray: IntArray, width: Int, height: Int): Candidate? {
        if (gray.size != width * height || width < 100 || height < 100) return null
        val x0 = (width * 0.62).toInt()
        val x1 = (width * 0.98).toInt()
        val y0 = (height * 0.05).toInt()
        val y1 = (height * 0.135).toInt()
        val roiWidth = x1 - x0
        val roiHeight = y1 - y0
        val samples = ArrayList<Int>((roiWidth * roiHeight) / 16)
        var sy = y0
        while (sy < y1) {
            var sx = x0
            while (sx < x1) {
                samples += gray[sy * width + sx]
                sx += 4
            }
            sy += 4
        }
        if (samples.isEmpty()) return null
        samples.sort()
        val background = samples[samples.size / 2]

        val dark = BooleanArray(roiWidth * roiHeight)
        val light = BooleanArray(roiWidth * roiHeight)
        for (y in 0 until roiHeight) for (x in 0 until roiWidth) {
            val value = gray[(y + y0) * width + x + x0]
            dark[y * roiWidth + x] = background - value >= 65
            light[y * roiWidth + x] = value - background >= 65
        }
        val scored = (scoreComponents(dark, roiWidth, roiHeight, x0, y0) +
            scoreComponents(light, roiWidth, roiHeight, x0, y0))
            .sortedByDescending { it.score }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.score ?: 0.0
        // A single low-confidence blob is not permission to click. The deliberately small margin
        // is paired with one-shot click + post-click verification in ActivateAppSearchTool.
        if (best.score < 0.60 || best.score - second < 0.015) return null
        return Candidate(best.x, best.y, best.score, second)
    }

    private data class Scored(val x: Int, val y: Int, val score: Double)

    private fun scoreComponents(
        mask: BooleanArray,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int,
    ): List<Scored> {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val out = mutableListOf<Scored>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var count = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                count++
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (mask[ni] && !seen[ni]) {
                        seen[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (count < 12 || bw < 8 || bh < 8 || bw > width / 2 || bh > height * 3 / 4) continue
            val fill = count.toDouble() / (bw * bh)
            val aspect = min(bw, bh).toDouble() / max(bw, bh)
            var bottomRight = 0
            var topLeft = 0
            var centerPixels = 0
            var centerArea = 0
            for (py in minY..maxY) for (px in minX..maxX) {
                val nx = (px - minX).toDouble() / bw
                val ny = (py - minY).toDouble() / bh
                if (nx in 0.25..0.58 && ny in 0.25..0.58) {
                    centerArea++
                    if (mask[py * width + px]) centerPixels++
                }
            }
            for (i in 0 until tail) {
                val px = queue[i] % width
                val py = queue[i] / width
                val nx = (px - minX).toDouble() / bw
                val ny = (py - minY).toDouble() / bh
                if (nx >= 0.66 && ny >= 0.66) bottomRight++
                if (nx <= 0.34 && ny <= 0.34) topLeft++
            }
            // A magnifier has a connected diagonal handle extending beyond the ring in the
            // bottom-right corner. A plus or circled-plus is approximately corner-symmetric.
            val directionalHandle = ((bottomRight - topLeft).toDouble() / count / 0.12).coerceIn(0.0, 1.0)
            val hollowCenter = if (centerArea == 0) 0.0 else
                (1.0 - centerPixels.toDouble() / centerArea / 0.35).coerceIn(0.0, 1.0)
            val fillScore = (fill / 0.30).coerceIn(0.0, 1.0)
            val score = 0.30 * aspect + 0.30 * directionalHandle + 0.25 * hollowCenter + 0.15 * fillScore
            out += Scored((minX + maxX) / 2 + offsetX, (minY + maxY) / 2 + offsetY, score)
        }
        return out
    }
}
