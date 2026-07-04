package com.ethran.notable.io.markdown

/**
 * Bidirectional mapping between offsets in the rendered display text (the
 * [androidx.compose.ui.text.AnnotatedString] shown in the reader) and offsets in the
 * raw markdown source. Built by [MarkdownRenderer]; consumed by ink annotation to turn
 * a display-text hit range into a surgical edit range in the source file.
 *
 * Mapping is piecewise: plain text runs map 1:1, while styled constructs whose display
 * text is shorter than their source (e.g. `[[wikilinks]]`, `**bold**`) map any display
 * offset inside them onto the whole source range of the construct.
 */
class MarkdownSourceMap(segments: List<Segment>) {

    /**
     * @param linear when true, display offsets inside the segment map proportionally
     *   (1:1) to source offsets; when false the whole source range is returned for any
     *   overlap (used for constructs with markup the display doesn't show).
     */
    data class Segment(
        val displayStart: Int,
        val displayEnd: Int,
        val sourceStart: Int,
        val sourceEnd: Int,
        val linear: Boolean = true
    )

    private val segments = segments.sortedBy { it.displayStart }

    fun isEmpty() = segments.isEmpty()

    /** Maps a single display offset to a source offset, or null if unmapped (decoration text). */
    fun displayToSource(displayOffset: Int): Int? {
        val seg = segmentAt(displayOffset) ?: return null
        return if (seg.linear) {
            (seg.sourceStart + (displayOffset - seg.displayStart))
                .coerceAtMost(seg.sourceEnd)
        } else {
            seg.sourceStart
        }
    }

    /**
     * Maps a display range to the covering source range. Expands over non-linear
     * segments so edits never split markup like `[[...]]`. Returns null when the
     * range touches no mapped source (e.g. purely decorative text).
     */
    fun displayRangeToSource(displayStart: Int, displayEnd: Int): IntRange? {
        var sourceStart = Int.MAX_VALUE
        var sourceEnd = Int.MIN_VALUE
        for (seg in segments) {
            if (seg.displayEnd <= displayStart || seg.displayStart >= displayEnd) continue
            if (seg.linear) {
                val overlapStart = maxOf(displayStart, seg.displayStart)
                val overlapEnd = minOf(displayEnd, seg.displayEnd)
                sourceStart = minOf(sourceStart, seg.sourceStart + (overlapStart - seg.displayStart))
                sourceEnd = maxOf(sourceEnd, seg.sourceStart + (overlapEnd - seg.displayStart))
            } else {
                sourceStart = minOf(sourceStart, seg.sourceStart)
                sourceEnd = maxOf(sourceEnd, seg.sourceEnd)
            }
        }
        if (sourceStart > sourceEnd) return null
        return sourceStart until sourceEnd
    }

    private fun segmentAt(displayOffset: Int): Segment? =
        segments.firstOrNull { displayOffset >= it.displayStart && displayOffset < it.displayEnd }
}
