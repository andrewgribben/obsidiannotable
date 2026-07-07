package com.ethran.notable.io.vault

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.markdown.MarkdownReaderContent

/**
 * Splits a vault note into editable and preserved regions for Source-mode editing.
 *
 * Phase 1 edits [editableBody] only; [suffix] (Excalidraw drawing tail) is reattached
 * verbatim on save.
 *
 * ## Extension points (not implemented yet)
 *
 * - **Frontmatter editing (Phase 2):** expose [prefix] as structured YAML key/values and
 *   merge edited frontmatter back into [prefix] before [mergeAfterEdit].
 * - **Live Preview (Phase 3):** keep editing [editableBody] as the canonical buffer;
 *   map display caret through [MarkdownSourceMap] while typing, then call [mergeAfterEdit].
 */
data class VaultNoteEditRegions(
    /** Frontmatter and leading content before the markdown body (unchanged on body-only save). */
    val prefix: String,
    val editableBody: String,
    /** Drawing tail / trailing `%%` block — preserved byte-for-byte on save. */
    val suffix: String,
    val isUnified: Boolean,
)

object VaultNoteEditModel {

    fun splitForEditing(fullContent: String): VaultNoteEditRegions {
        val fmEnd = ExcalidrawSerializer.frontmatterEndIndex(fullContent)
        val tailStart = MarkdownReaderContent.hiddenTailStartIndex(fullContent)
        val suffix = if (tailStart < fullContent.length) {
            fullContent.substring(tailStart)
        } else {
            ""
        }
        val bodyEnd = if (suffix.isNotEmpty()) tailStart else fullContent.length
        val bodyRaw = if (bodyEnd > fmEnd) fullContent.substring(fmEnd, bodyEnd) else ""
        val prefix = fullContent.substring(0, fmEnd)
        val isUnified = suffix.isNotEmpty() ||
            ExcalidrawSerializer.isExcalidrawNote(fullContent) ||
            ExcalidrawSerializer.hasDrawingSection(fullContent)
        return VaultNoteEditRegions(
            prefix = prefix,
            editableBody = bodyRaw.trim(),
            suffix = suffix,
            isUnified = isUnified
        )
    }

    /** Rebuilds the full note with [newBody] spliced between [regions.prefix] and [regions.suffix]. */
    fun mergeAfterEdit(regions: VaultNoteEditRegions, newBody: String): String {
        val body = newBody.trim()
        return buildString {
            append(regions.prefix)
            val prefixEndsWithNewline = regions.prefix.isEmpty() || regions.prefix.endsWith("\n")
            if (body.isNotEmpty()) {
                if (regions.prefix.isNotEmpty() && !prefixEndsWithNewline) append('\n')
                if (regions.prefix.isNotEmpty()) append('\n')
                append(body)
            }
            if (regions.suffix.isNotEmpty()) {
                if (body.isNotEmpty() || regions.prefix.isNotEmpty()) append('\n')
                append(regions.suffix)
            }
            if (isEmpty() || !endsWith("\n")) append('\n')
        }
    }
}
