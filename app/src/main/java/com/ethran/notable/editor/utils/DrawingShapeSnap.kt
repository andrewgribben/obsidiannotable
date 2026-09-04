package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class SnappedShapeKind {
    LINE,
    RECTANGLE,
    ELLIPSE,
}

data class SnappedShape(
    val kind: SnappedShapeKind,
    val points: List<StrokePoint>,
)

/**
 * Classifies a finished stroke in drawing shape-snap mode and returns a perfect polyline.
 * Separate from [InkGestureClassifier] (reader annotations).
 */
object DrawingShapeSnap {

    private const val MIN_DIAGONAL = 12f
    private const val CLOSURE_MAX_FRACTION = 0.5f
    private const val LOOP_MIN_AREA_FRACTION = 0.3f
    private const val LOOP_STRONG_AREA_FRACTION = 0.6f
    private const val LOOP_MAX_ASPECT = 7f
    private const val LOOP_MAX_X_REVERSALS = 3
    private const val RECT_MIN_ASPECT = 0.25f
    private const val RECT_MAX_ASPECT = 4f
    private const val POINTS_PER_EDGE = 25
    private const val ELLIPSE_STEPS = 64

    fun snap(rawPoints: List<StrokePoint>): SnappedShape {
        if (rawPoints.size < 2) {
            return SnappedShape(SnappedShapeKind.LINE, rawPoints)
        }

        val geom = rawPoints.map { InkGestureClassifier.Point(it.x, it.y) }
        val left = geom.minOf { it.x }
        val right = geom.maxOf { it.x }
        val top = geom.minOf { it.y }
        val bottom = geom.maxOf { it.y }
        val width = right - left
        val height = bottom - top
        val diagonal = hypot(width, height)

        if (diagonal < MIN_DIAGONAL) {
            return SnappedShape(
                SnappedShapeKind.LINE,
                transformToLine(endpointsFromStroke(rawPoints))
            )
        }

        val closure = hypot(
            geom.first().x - geom.last().x,
            geom.first().y - geom.last().y
        )
        val bboxArea = width * height
        val areaFraction = if (bboxArea > 0f) {
            abs(InkGestureClassifier.signedArea(geom)) / bboxArea
        } else {
            0f
        }
        val aspect = if (height > 0.01f) width / height else Float.MAX_VALUE
        val reversals = InkGestureClassifier.countXReversals(geom)

        val isLoop = reversals <= LOOP_MAX_X_REVERSALS &&
            ((closure <= diagonal * CLOSURE_MAX_FRACTION &&
                    areaFraction >= LOOP_MIN_AREA_FRACTION) ||
                (areaFraction >= LOOP_STRONG_AREA_FRACTION && aspect <= LOOP_MAX_ASPECT))

        val aspectInv = if (width > 0.01f) height / width else Float.MAX_VALUE
        val inRectAspectRange = aspect in RECT_MIN_ASPECT..RECT_MAX_ASPECT &&
            aspectInv in RECT_MIN_ASPECT..RECT_MAX_ASPECT

        if (isLoop && inRectAspectRange && hasRectangleCorners(geom, diagonal, left, top, right, bottom)) {
            return SnappedShape(
                SnappedShapeKind.RECTANGLE,
                transformToRectangle(left, top, right, bottom, rawPoints.first())
            )
        }

        if (isLoop) {
            return SnappedShape(
                SnappedShapeKind.ELLIPSE,
                transformToEllipse(left, top, right, bottom, rawPoints.first())
            )
        }

        return SnappedShape(
            SnappedShapeKind.LINE,
            transformToLine(endpointsFromStroke(rawPoints))
        )
    }

    private fun hasRectangleCorners(
        points: List<InkGestureClassifier.Point>,
        diagonal: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean {
        val epsilon = diagonal * 0.08f
        val simplified = ramerDouglasPeucker(points, epsilon)
        val unique = if (simplified.size >= 2 &&
            hypot(
                simplified.first().x - simplified.last().x,
                simplified.first().y - simplified.last().y
            ) < epsilon
        ) {
            simplified.dropLast(1)
        } else {
            simplified
        }
        if (unique.size !in 4..5) return false
        val bboxCorners = listOf(
            InkGestureClassifier.Point(left, top),
            InkGestureClassifier.Point(right, top),
            InkGestureClassifier.Point(right, bottom),
            InkGestureClassifier.Point(left, bottom),
        )
        val cornerTolerance = diagonal * 0.15f
        return unique.all { vertex ->
            bboxCorners.any { corner ->
                hypot(vertex.x - corner.x, vertex.y - corner.y) < cornerTolerance
            }
        }
    }

    private fun ramerDouglasPeucker(
        points: List<InkGestureClassifier.Point>,
        epsilon: Float
    ): List<InkGestureClassifier.Point> {
        if (points.size < 3) return points
        var maxDistance = 0f
        var index = 0
        val end = points.lastIndex
        val start = points.first()
        val finish = points[end]
        for (i in 1 until end) {
            val distance = perpendicularDistance(points[i], start, finish)
            if (distance > maxDistance) {
                maxDistance = distance
                index = i
            }
        }
        return if (maxDistance > epsilon) {
            val left = ramerDouglasPeucker(points.subList(0, index + 1), epsilon)
            val right = ramerDouglasPeucker(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, finish)
        }
    }

    private fun perpendicularDistance(
        point: InkGestureClassifier.Point,
        lineStart: InkGestureClassifier.Point,
        lineEnd: InkGestureClassifier.Point
    ): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        if (dx == 0f && dy == 0f) {
            return hypot(point.x - lineStart.x, point.y - lineStart.y)
        }
        val t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0f, 1f)
        val projX = lineStart.x + clamped * dx
        val projY = lineStart.y + clamped * dy
        return hypot(point.x - projX, point.y - projY)
    }

    private fun endpointsFromStroke(points: List<StrokePoint>): Pair<StrokePoint, StrokePoint> {
        val startIdx = points.size / 10
        val endIdx = (9 * points.size) / 10
        val startPoint = points.first()
        val endPoint = points.last()
        return startPoint.copy(
            tiltX = points[startIdx].tiltX,
            tiltY = points[startIdx].tiltY,
            pressure = points[startIdx].pressure
        ) to endPoint.copy(
            tiltX = points[endIdx].tiltX,
            tiltY = points[endIdx].tiltY,
            pressure = points[endIdx].pressure
        )
    }

    private fun transformToLine(endpoints: Pair<StrokePoint, StrokePoint>): List<StrokePoint> =
        listOf(endpoints.first, endpoints.second)

    fun transformToRectangle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        template: StrokePoint
    ): List<StrokePoint> {
        val corners = listOf(
            StrokePoint(left, top),
            StrokePoint(right, top),
            StrokePoint(right, bottom),
            StrokePoint(left, bottom),
            StrokePoint(left, top),
        )
        return corners.zipWithNext { a, b ->
            interpolateEdge(a, b, template)
        }.flatten()
    }

    fun transformToEllipse(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        template: StrokePoint
    ): List<StrokePoint> {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = max((right - left) / 2f, 0.5f)
        val ry = max((bottom - top) / 2f, 0.5f)
        return List(ELLIPSE_STEPS) { i ->
            val angle = 2.0 * PI * i / ELLIPSE_STEPS
            StrokePoint(
                x = cx + rx * cos(angle).toFloat(),
                y = cy + ry * sin(angle).toFloat(),
                pressure = template.pressure,
                tiltX = template.tiltX,
                tiltY = template.tiltY
            )
        }
    }

    private fun interpolateEdge(
        start: StrokePoint,
        end: StrokePoint,
        template: StrokePoint
    ): List<StrokePoint> {
        return List(POINTS_PER_EDGE) { i ->
            val fraction = i.toFloat() / (POINTS_PER_EDGE - 1)
            StrokePoint(
                x = lerp(start.x, end.x, fraction),
                y = lerp(start.y, end.y, fraction),
                pressure = template.pressure,
                tiltX = template.tiltX,
                tiltY = template.tiltY
            )
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
}
