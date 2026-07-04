package com.ethran.notable.editor.utils

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Geometric classifier for ink annotation gestures drawn over rendered text.
 *
 * Pure geometry — no Android types — so thresholds can be unit-tested and tuned.
 * The reader's annotation layer maps [InkGesture.HORIZONTAL_LINE] to strike-through or
 * underline(bold) based on where the line sits relative to the text line band, which
 * the classifier itself doesn't know about.
 */
object InkGestureClassifier {

    data class Point(val x: Float, val y: Float)

    enum class InkGesture {
        /** Closed loop enclosing text → highlight. */
        CIRCLE,

        /** Roughly horizontal single line → strike-through or underline by position. */
        HORIZONTAL_LINE,

        /** Dense zigzag scribble over text → delete. */
        SCRAWL,

        /** Anything else: ignored by the annotation layer. */
        OTHER
    }

    data class Classification(
        val gesture: InkGesture,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    // --- Tunable thresholds (values in page/display pixels where absolute) ---

    /** Max distance between stroke start and end, as a fraction of bbox diagonal, to count as closed. */
    const val CLOSURE_MAX_FRACTION = 0.5f

    /** Min enclosed area as a fraction of bbox area for a closed loop (filters back-and-forth lines). */
    const val LOOP_MIN_AREA_FRACTION = 0.25f

    /**
     * Net (shoelace) area fraction above which a stroke counts as a loop even when its
     * ends don't meet or it has scrawl-like reversals — catches big sloppy circles,
     * open C-shapes, and overdrawn (1.5-turn) loops, whose net area stays large, while
     * a deletion scribble's back-and-forth passes mostly cancel out.
     */
    const val LOOP_STRONG_AREA_FRACTION = 0.65f

    /** Min bbox diagonal for any gesture — smaller marks are accidental dots. */
    const val MIN_DIAGONAL = 12f

    /** A horizontal line's bbox must be at least this many times wider than tall. */
    const val LINE_MIN_ASPECT = 2.0f

    /** Max direction reversals along x for a clean line (more = scribble). */
    const val LINE_MAX_REVERSALS = 2

    /** Min x-direction reversals for a scrawl. */
    const val SCRAWL_MIN_REVERSALS = 3

    /** Min ink-length : bbox-diagonal ratio for a scrawl (density of coverage). */
    const val SCRAWL_MIN_DENSITY = 2.2f

    fun classify(points: List<Point>): Classification {
        if (points.size < 3) return classification(InkGesture.OTHER, points)

        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        val width = right - left
        val height = bottom - top
        val diagonal = hypot(width, height)

        if (diagonal < MIN_DIAGONAL) return classification(InkGesture.OTHER, points)

        val reversals = countXReversals(points)
        val inkLength = pathLength(points)
        val density = if (diagonal > 0f) inkLength / diagonal else 0f

        val closure = hypot(points.first().x - points.last().x, points.first().y - points.last().y)
        val area = abs(signedArea(points))
        val bboxArea = width * height
        val areaFraction = if (bboxArea > 0f) area / bboxArea else 0f

        // Dense zigzag over an area → scrawl/delete. The area guard keeps overdrawn
        // circles (which also rack up x reversals) out of this branch: a scribble's
        // net area mostly cancels, a loop's doesn't.
        if (reversals >= SCRAWL_MIN_REVERSALS && density >= SCRAWL_MIN_DENSITY &&
            areaFraction < LOOP_STRONG_AREA_FRACTION
        ) {
            return Classification(InkGesture.SCRAWL, left, top, right, bottom)
        }

        // Loop → circle/highlight: either reasonably closed with enough enclosed area,
        // or clearly loop-shaped by net area alone (big sloppy or unclosed circles).
        if ((closure <= diagonal * CLOSURE_MAX_FRACTION && areaFraction >= LOOP_MIN_AREA_FRACTION) ||
            areaFraction >= LOOP_STRONG_AREA_FRACTION
        ) {
            return Classification(InkGesture.CIRCLE, left, top, right, bottom)
        }

        // Horizontal line: wide, flat, few reversals
        if (height <= 0.01f || (width / height >= LINE_MIN_ASPECT && reversals <= LINE_MAX_REVERSALS)) {
            return Classification(InkGesture.HORIZONTAL_LINE, left, top, right, bottom)
        }

        return Classification(InkGesture.OTHER, left, top, right, bottom)
    }

    private fun classification(gesture: InkGesture, points: List<Point>): Classification {
        val left = points.minOfOrNull { it.x } ?: 0f
        val right = points.maxOfOrNull { it.x } ?: 0f
        val top = points.minOfOrNull { it.y } ?: 0f
        val bottom = points.maxOfOrNull { it.y } ?: 0f
        return Classification(gesture, left, top, right, bottom)
    }

    /** Number of times the x direction of travel flips (ignoring tiny jitter). */
    fun countXReversals(points: List<Point>, jitter: Float = 3f): Int {
        var reversals = 0
        var direction = 0 // -1, 0, +1
        var anchorX = points.first().x
        for (point in points.drop(1)) {
            val dx = point.x - anchorX
            if (abs(dx) < jitter) continue
            val newDirection = if (dx > 0) 1 else -1
            if (direction != 0 && newDirection != direction) reversals++
            direction = newDirection
            anchorX = point.x
        }
        return reversals
    }

    fun pathLength(points: List<Point>): Float {
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return length
    }

    /** Shoelace signed area of the polygon formed by the stroke. */
    fun signedArea(points: List<Point>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }
}
