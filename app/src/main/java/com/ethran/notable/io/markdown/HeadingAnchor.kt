package com.ethran.notable.io.markdown

import com.ethran.notable.io.vault.decodeVaultLinkTarget

/**
 * Extracts an Obsidian-style heading anchor from a raw note-reader link target, e.g.
 * the `1.3 Key skills` in `Module%20Guide/1%20Learning%20outcomes.md#1.3%20Key%20skills`
 * or the `Heading` in a wikilink's `Note#Heading` (its `|alias` is already stripped by
 * [MarkdownRenderer] before the target reaches here).
 *
 * Returns null when the target has no `#`, the anchor is blank, or the anchor is an
 * Obsidian block reference (`#^blockid`) — block refs aren't headings, so the reader
 * opens the note at the top rather than guessing a scroll position.
 */
fun extractHeadingAnchor(rawTarget: String): String? {
    val hashIndex = rawTarget.indexOf('#')
    if (hashIndex < 0) return null
    val anchorRaw = rawTarget.substring(hashIndex + 1)
    if (anchorRaw.isEmpty() || anchorRaw.startsWith('^')) return null
    val decoded = decodeVaultLinkTarget(anchorRaw).trim()
    return decoded.ifBlank { null }
}

/**
 * Normalizes heading/anchor text for Obsidian-style matching: case-insensitive,
 * surrounding whitespace trimmed, internal runs of whitespace collapsed (the heading's
 * markdown markup is already stripped by the time it reaches [MarkdownHeading.text]).
 */
private fun normalizeForAnchorMatch(text: String): String =
    text.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * Finds the display offset of the heading matching [anchor], Obsidian-style
 * (case-insensitive, whitespace-insensitive exact match on the heading text).
 * Returns null when no heading matches — the reader then stays at the top of the note.
 */
fun findHeadingDisplayStart(headings: List<MarkdownHeading>, anchor: String): Int? {
    if (anchor.isBlank()) return null
    val target = normalizeForAnchorMatch(anchor)
    return headings.firstOrNull { normalizeForAnchorMatch(it.text) == target }?.displayStart
}
