package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LocalSearchIconDetectorTest {
    @Test
    fun detector_prefersMagnifierAndDoesNotMistakeAdjacentPlus() {
        val image = canvas()
        drawMagnifier(image, 500, 105)
        drawPlus(image, 560, 105)

        val result = LocalSearchIconDetector.detect(image, WIDTH, HEIGHT)

        assertTrue("result=$result", result != null)
        assertTrue(result!!.x in 485..525)
    }

    @Test
    fun detector_rejectsTwoIndistinguishableMagnifiers() {
        val image = canvas()
        drawMagnifier(image, 460, 105)
        drawMagnifier(image, 540, 105)

        assertNull(LocalSearchIconDetector.detect(image, WIDTH, HEIGHT))
    }

    private fun canvas() = IntArray(WIDTH * HEIGHT) { 245 }

    private fun dark(image: IntArray, x: Int, y: Int, radius: Int = 1) {
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until WIDTH && py in 0 until HEIGHT) image[py * WIDTH + px] = 20
        }
    }

    private fun drawMagnifier(image: IntArray, cx: Int, cy: Int) {
        for (degree in 0 until 360 step 3) {
            val radians = Math.toRadians(degree.toDouble())
            dark(image, cx + (cos(radians) * 15).toInt(), cy + (sin(radians) * 15).toInt())
        }
        for (step in 10..29) dark(image, cx + step, cy + step)
    }

    private fun drawPlus(image: IntArray, cx: Int, cy: Int) {
        for (step in -18..18) {
            dark(image, cx + step, cy)
            dark(image, cx, cy + step)
        }
    }

    companion object {
        private const val WIDTH = 640
        private const val HEIGHT = 1000
    }
}
