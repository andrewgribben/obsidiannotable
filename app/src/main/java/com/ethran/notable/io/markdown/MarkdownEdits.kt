package com.ethran.notable.io.markdown

/**
 * Surgical edits applied to raw markdown source by the ink annotation layer.
 * Standards-compliant markup only: `==highlight==`, `**bold**`, `~~strikethrough~~`,
 * and plain deletion with whitespace cleanup.
 */
sealed class MarkdownEdit {
    abstract val sourceRange: IntRange

    data class Highlight(override val sourceRange: IntRange) : MarkdownEdit()
    data class Bold(override val sourceRange: IntRange) : MarkdownEdit()
    data class Strikethrough(override val sourceRange: IntRange) : MarkdownEdit()
    data class Delete(override val sourceRange: IntRange) : MarkdownEdit()
}

object MarkdownEdits {

    /**
     * Applies [edits] to [source]. Edits are applied back-to-front so earlier ranges
     * stay valid; overlapping edits are dropped (first one wins).
     */
    fun apply(source: String, edits: List<MarkdownEdit>): String {
        val ordered = edits.sortedByDescending { it.sourceRange.first }
        var result = source
        var lastStart = Int.MAX_VALUE
        for (edit in ordered) {
            val range = clampRange(edit.sourceRange, result.length) ?: continue
            if (range.last >= lastStart) continue // overlaps an already-applied edit
            result = when (edit) {
                is MarkdownEdit.Highlight -> wrap(result, range, "==")
                is MarkdownEdit.Bold -> wrap(result, range, "**")
                is MarkdownEdit.Strikethrough -> wrap(result, range, "~~")
                is MarkdownEdit.Delete -> delete(result, range)
            }
            lastStart = range.first
        }
        return result
    }

    private fun clampRange(range: IntRange, length: Int): IntRange? {
        val start = range.first.coerceIn(0, length)
        val end = (range.last + 1).coerceIn(0, length)
        if (end <= start) return null
        return start until end
    }

    /** Wraps the range with [marker], shrinking it first so whitespace stays outside. */
    private fun wrap(source: String, range: IntRange, marker: String): String {
        var start = range.first
        var end = range.last + 1
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        if (end <= start) return source
        // Keep trailing punctuation outside the markup
        while (end > start && source[end - 1] in ".,;:!?") end--
        if (end <= start) return source
        return source.substring(0, start) + marker + source.substring(start, end) +
            marker + source.substring(end)
    }

    /** Deletes the range, collapsing any doubled whitespace left behind. */
    private fun delete(source: String, range: IntRange): String {
        var start = range.first
        var end = range.last + 1
        // Swallow whitespace after the deleted text (or before, at end of line/text)
        if (end < source.length && source[end] == ' ') {
            end++
        } else if (start > 0 && source[start - 1] == ' ') {
            start--
        }
        return source.substring(0, start) + source.substring(end)
    }

    /** Expands [start, end) to word boundaries in [text] (for gesture → word snapping). */
    fun expandToWordBounds(text: String, start: Int, end: Int): IntRange {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(0, text.length)
        if (e < s) e = s
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < text.length && !text[e].isWhitespace()) e++
        return s until e
    }
}
