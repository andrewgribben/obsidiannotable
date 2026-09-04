package com.ethran.notable.io.markdown

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer

/**
 * Prepares vault note source for the text reader: visible markdown only, no excalidraw
 * drawing blocks or Obsidian `%%` comment regions.
 *
 * Uses a prefix of the original [source] so display offsets still map to the same
 * indices in the full file (ink annotations apply to [source], not this slice).
 */
object MarkdownReaderContent {

    private val PAIRED_COMMENT = Regex("""%%[\s\S]*?%%""")
    private val DRAWING_MARKERS = listOf(
        "==⚠",
        "## Drawing",
        "# Drawing",
        "```compressed-json",
        "```json"
    )

    fun prepareForTextView(source: String): String {
        var visible = PAIRED_COMMENT.replace(source, "")
        val end = hiddenTailStartIndex(visible)
        visible = visible.substring(0, end.coerceIn(0, visible.length))
        return visible.trimEnd() + "\n"
    }

    /** First index of a non-comment excalidraw tail or lone trailing `%%`. */
    internal fun hiddenTailStartIndex(source: String): Int {
        val bodyStart = ExcalidrawSerializer.frontmatterEndIndex(source)
        val candidates = mutableListOf<Int>()
        drawingSectionStart(source, bodyStart)?.let { candidates.add(it) }
        loneTrailingCommentMarker(source, bodyStart)?.let { candidates.add(it) }
        return candidates.minOrNull() ?: source.length
    }

    private fun drawingSectionStart(content: String, searchFrom: Int): Int? =
        DRAWING_MARKERS
            .map { content.indexOf(it, searchFrom) }
            .filter { it >= 0 }
            .minOrNull()

    private fun loneTrailingCommentMarker(content: String, searchFrom: Int): Int? {
        val last = content.lastIndexOf("%%")
        if (last < searchFrom) return null
        val previous = content.lastIndexOf("%%", last - 1)
        if (previous >= searchFrom) return null
        return if (content.substring(last).trim() == "%%") last else null
    }
}
