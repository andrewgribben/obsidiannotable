package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DrawingShapeSnapTest {

    private fun circleStroke(
        centerX: Float = 100f,
        centerY: Float = 100f,
        radiusX: Float = 50f,
        radiusY: Float = 50f,
        steps: Int = 36
    ): List<StrokePoint> {
        return (0..steps).map { i ->
            val angle = 2.0 * Math.PI * i / steps
            StrokePoint(
                centerX + radiusX * cos(angle).toFloat(),
                centerY + radiusY * sin(angle).toFloat()
            )
        }
    }

    private fun rectangleStroke(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        stepsPerEdge: Int = 12
    ): List<StrokePoint> {
        val corners = listOf(
            left to top,
            right to top,
            right to bottom,
            left to bottom,
            left to top,
        )
        return corners.zipWithNext { (x1, y1), (x2, y2) ->
            (0..stepsPerEdge).map { i ->
                val t = i.toFloat() / stepsPerEdge
                StrokePoint(
                    x1 + (x2 - x1) * t,
                    y1 + (y2 - y1) * t
                )
            }
        }.flatten()
    }

    @Test
    fun `circle ink snaps to ellipse`() {
        val result = DrawingShapeSnap.snap(circleStroke())
        assertEquals(SnappedShapeKind.ELLIPSE, result.kind)
        assertTrue(result.points.size >= 60)
        val xs = result.points.map { it.x }
        val ys = result.points.map { it.y }
        assertTrue(xs.max() - xs.min() > 80f)
        assertTrue(ys.max() - ys.min() > 80f)
    }

    @Test
    fun `box ink snaps to rectangle`() {
        val result = DrawingShapeSnap.snap(rectangleStroke(50f, 60f, 200f, 160f))
        assertEquals(SnappedShapeKind.RECTANGLE, result.kind)
        val xs = result.points.map { it.x }
        val ys = result.points.map { it.y }
        assertEquals(50f, xs.min(), 2f)
        assertEquals(200f, xs.max(), 2f)
        assertEquals(60f, ys.min(), 2f)
        assertEquals(160f, ys.max(), 2f)
    }

    @Test
    fun `diagonal line snaps to line`() {
        val points = (0..20).map { i ->
            StrokePoint(i * 10f, i * 8f + 5f)
        }
        val result = DrawingShapeSnap.snap(points)
        assertEquals(SnappedShapeKind.LINE, result.kind)
        val first = result.points.first()
        val last = result.points.last()
        assertEquals(0f, first.x, 1f)
        assertEquals(5f, first.y, 2f)
        assertEquals(200f, last.x, 2f)
        assertEquals(165f, last.y, 4f)
    }

    @Test
    fun `vertical line snaps to line`() {
        val points = (0..20).map { i -> StrokePoint(100f, i * 10f) }
        val result = DrawingShapeSnap.snap(points)
        assertEquals(SnappedShapeKind.LINE, result.kind)
        assertEquals(100f, result.points.first().x, 1f)
        assertEquals(0f, result.points.first().y, 1f)
        assertEquals(200f, result.points.last().y, 2f)
    }

    @Test
    fun `open wavy stroke falls back to line`() {
        val points = (0..40).map { i ->
            val x = i * 8f
            val y = 100f + 20f * sin(i / 3.0).toFloat()
            StrokePoint(x, y)
        }
        val result = DrawingShapeSnap.snap(points)
        assertEquals(SnappedShapeKind.LINE, result.kind)
    }

    @Test
    fun `tiny stroke falls back to line`() {
        val points = listOf(StrokePoint(0f, 0f), StrokePoint(3f, 2f))
        val result = DrawingShapeSnap.snap(points)
        assertEquals(SnappedShapeKind.LINE, result.kind)
        assertEquals(2, result.points.size)
    }

    @Test
    fun `oval ink snaps to ellipse not rectangle`() {
        val result = DrawingShapeSnap.snap(circleStroke(radiusX = 80f, radiusY = 30f))
        assertEquals(SnappedShapeKind.ELLIPSE, result.kind)
        val width = result.points.maxOf { it.x } - result.points.minOf { it.x }
        val height = result.points.maxOf { it.y } - result.points.minOf { it.y }
        assertTrue(width > height * 1.5f)
    }
}
