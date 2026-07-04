package com.ethran.notable.editor.utils

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Geometric classifier for ink annotation gestures drawn over rendered text.
 *
 * Pure geometry — no Android types — so thresholds can be unit-tested and tuned.
 *
 * Only two gesture families exist (the scribble-to-delete gesture was removed as too
 * destructive when misclassified): loops and horizontal lines. Anything that encloses
 * area is a circle/highlight; anything wide and flat is a line (strike or underline,
 * decided by the annotation layer from its vertical position). Only tiny marks and
 * tall narrow strokes are rejected, which keeps "gesture not recognized" rare.
 */
object InkGestureClassifier {

    data class Point(val x: Float, val y: Float)

    enum class InkGesture {
        /** Closed-ish loop enclosing text → highlight. */
        CIRCLE,

        /** Roughly horizontal line → strike-through or underline by position. */
        HORIZONTAL_LINE,

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

    /** Min net enclosed area as a fraction of bbox area for a closed-ish loop.
     *  Real circles land ≥ 0.5; a zigzag whose ends happen to meet nets ~0.2. */
    const val LOOP_MIN_AREA_FRACTION = 0.3f

    /**
     * Net (shoelace) area fraction above which a stroke counts as a loop even when its
     * ends are far apart — catches big sloppy circles, open C-shapes, and overdrawn
     * (1.5-turn) loops. A wavy line's net area mostly cancels, so it stays a line.
     */
    const val LOOP_STRONG_AREA_FRACTION = 0.6f

    /** Loops can be flat ovals, but not this flat — beyond it, it's a sagging line. */
    const val LOOP_MAX_ASPECT = 7f

    /**
     * Max x-direction reversals for a loop. Tracing any single loop flips horizontal
     * direction at most twice (about 3 when overdrawn); a back-and-forth scribble
     * flips once per pass, so this cleanly keeps scribbles out of the circle branch
     * (their alternate passes enclose area too, so net area alone can't tell them apart).
     */
    const val LOOP_MAX_X_REVERSALS = 3

    /** Min bbox diagonal for any gesture — smaller marks are accidental dots. */
    const val MIN_DIAGONAL = 12f

    /** A horizontal line's bbox must be at least this many times wider than tall. */
    const val LINE_MIN_ASPECT = 1.5f

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

        val closure = hypot(points.first().x - points.last().x, points.first().y - points.last().y)
        val bboxArea = width * height
        val areaFraction = if (bboxArea > 0f) abs(signedArea(points)) / bboxArea else 0f
        val aspect = if (height > 0.01f) width / height else Float.MAX_VALUE
        val reversals = countXReversals(points)

        // Loop → circle/highlight: loop-like direction profile plus either reasonably
        // closed with enough enclosed area, or clearly loop-shaped by net area alone
        // (sloppy/open/overdrawn circles) as long as it isn't so flat that it's
        // really a sagging underline.
        val isLoop = reversals <= LOOP_MAX_X_REVERSALS &&
                ((closure <= diagonal * CLOSURE_MAX_FRACTION &&
                        areaFraction >= LOOP_MIN_AREA_FRACTION) ||
                        (areaFraction >= LOOP_STRONG_AREA_FRACTION && aspect <= LOOP_MAX_ASPECT))
        if (isLoop) {
            return Classification(InkGesture.CIRCLE, left, top, right, bottom)
        }

        // Wide and flat → horizontal line. Waviness or scribbly reversals don't
        // disqualify it: the worst case is a strike-through, which is easily undone.
        if (aspect >= LINE_MIN_ASPECT) {
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
