package com.ethran.notable.editor.utils

import com.ethran.notable.editor.utils.InkGestureClassifier.InkGesture
import com.ethran.notable.editor.utils.InkGestureClassifier.Point
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class InkGestureClassifierTest {

    private fun circlePoints(
        centerX: Float = 100f,
        centerY: Float = 100f,
        radius: Float = 50f,
        steps: Int = 36,
        closeFully: Boolean = true
    ): List<Point> {
        val last = if (closeFully) steps else steps - 4
        return (0..last).map { i ->
            val angle = 2.0 * Math.PI * i / steps
            Point(
                centerX + radius * cos(angle).toFloat(),
                centerY + radius * sin(angle).toFloat()
            )
        }
    }

    @Test
    fun `closed loop classifies as circle`() {
        val result = InkGestureClassifier.classify(circlePoints())
        assertEquals(InkGesture.CIRCLE, result.gesture)
    }

    @Test
    fun `nearly closed loop still classifies as circle`() {
        val result = InkGestureClassifier.classify(circlePoints(closeFully = false))
        assertEquals(InkGesture.CIRCLE, result.gesture)
    }

    @Test
    fun `straight horizontal stroke classifies as line`() {
        val points = (0..20).map { Point(it * 10f, 100f + it * 0.4f) }
        val result = InkGestureClassifier.classify(points)
        assertEquals(InkGesture.HORIZONTAL_LINE, result.gesture)
    }

    @Test
    fun `dense zigzag classifies as scrawl`() {
        val points = mutableListOf<Point>()
        var y = 100f
        repeat(6) { pass ->
            val xs = if (pass % 2 == 0) 0..80 step 8 else 80 downTo 0 step 8
            for (x in xs) points.add(Point(x.toFloat(), y))
            y += 3f
        }
        val result = InkGestureClassifier.classify(points)
        assertEquals(InkGesture.SCRAWL, result.gesture)
    }

    @Test
    fun `tiny mark classifies as other`() {
        val points = listOf(Point(10f, 10f), Point(12f, 11f), Point(13f, 12f))
        val result = InkGestureClassifier.classify(points)
        assertEquals(InkGesture.OTHER, result.gesture)
    }

    @Test
    fun `steep diagonal stroke classifies as other`() {
        val points = (0..20).map { Point(it * 5f, it * 10f) }
        val result = InkGestureClassifier.classify(points)
        assertEquals(InkGesture.OTHER, result.gesture)
    }

    @Test
    fun `classification reports bounding box`() {
        val points = (0..20).map { Point(it * 10f, 100f) }
        val result = InkGestureClassifier.classify(points)
        assertEquals(0f, result.left, 0f)
        assertEquals(200f, result.right, 0f)
        assertEquals(100f, result.top, 0f)
        assertEquals(100f, result.bottom, 0f)
    }

    @Test
    fun `x reversal counting ignores jitter`() {
        val clean = (0..10).map { Point(it * 10f, 0f) }
        assertEquals(0, InkGestureClassifier.countXReversals(clean))

        val zigzag = listOf(
            Point(0f, 0f), Point(50f, 0f), Point(0f, 5f), Point(50f, 10f), Point(0f, 15f)
        )
        assertEquals(3, InkGestureClassifier.countXReversals(zigzag))
    }
}
