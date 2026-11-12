package com.ethran.notable.io.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure parts of `#heading` link scrolling: pulling the anchor out of a
 * note-reader link target, and matching that anchor against the headings recorded by
 * [MarkdownRenderer] (Obsidian-style: case-insensitive, whitespace-insensitive).
 */
class HeadingAnchorTest {

    // -- extractHeadingAnchor ---------------------------------------------------------

    @Test
    fun `extracts a percent-encoded heading anchor from a markdown link target`() {
        val anchor = extractHeadingAnchor(
            "Module%20Guide/1%20Learning%20outcomes.md#1.3%20Key%20skills"
        )
        assertEquals("1.3 Key skills", anchor)
    }

    @Test
    fun `extracts a plain heading anchor from a wikilink target`() {
        // MarkdownRenderer strips "|alias" before the target reaches here.
        assertEquals("Heading", extractHeadingAnchor("Target Note#Heading"))
    }

    @Test
    fun `returns null when the target has no anchor`() {
        assertNull(extractHeadingAnchor("Module Guide/Introduction.md"))
    }

    @Test
    fun `returns null for a blank anchor`() {
        assertNull(extractHeadingAnchor("Note.md#"))
    }

    @Test
    fun `treats a block reference anchor as no-scroll`() {
        assertNull(extractHeadingAnchor("Module%20Guide/3%20Module%20assessment.md#^s_3_2"))
    }

    @Test
    fun `decodes percent-encoded punctuation inside the anchor`() {
        val anchor = extractHeadingAnchor(
            "3%20W211.md#3.2%20Workload%3A%20how%20long%20should%20everything%20take%3F"
        )
        assertEquals("3.2 Workload: how long should everything take?", anchor)
    }

    // -- findHeadingDisplayStart --------------------------------------------------------

    @Test
    fun `matches a heading case-insensitively`() {
        val headings = listOf(
            MarkdownHeading(level = 2, displayStart = 0, text = "1.3 Key skills"),
            MarkdownHeading(level = 2, displayStart = 40, text = "1.4 Practical and professional skills")
        )
        assertEquals(0, findHeadingDisplayStart(headings, "1.3 key SKILLS"))
    }

    @Test
    fun `matches a heading ignoring surrounding and collapsed whitespace`() {
        val headings = listOf(MarkdownHeading(level = 2, displayStart = 12, text = "Key skills"))
        assertEquals(12, findHeadingDisplayStart(headings, "  Key   skills  "))
    }

    @Test
    fun `returns null when no heading matches`() {
        val headings = listOf(MarkdownHeading(level = 2, displayStart = 0, text = "Key skills"))
        assertNull(findHeadingDisplayStart(headings, "Nonexistent heading"))
    }

    @Test
    fun `returns null for a blank anchor argument`() {
        val headings = listOf(MarkdownHeading(level = 2, displayStart = 0, text = "Key skills"))
        assertNull(findHeadingDisplayStart(headings, "  "))
    }

    @Test
    fun `returns the first matching heading when duplicates exist`() {
        val headings = listOf(
            MarkdownHeading(level = 2, displayStart = 5, text = "Summary"),
            MarkdownHeading(level = 2, displayStart = 50, text = "Summary")
        )
        assertEquals(5, findHeadingDisplayStart(headings, "Summary"))
    }

    // -- End-to-end against real toc.md-style heading text -----------------------------

    @Test
    fun `renderer-recorded heading matches its toc anchor text, e-ink reader round trip`() {
        // Mirrors a target note for one of the real toc.md links:
        // [Key skills](Module%20Guide/1%20Learning%20outcomes.md#1.3%20Key%20skills)
        val source = """
            # Learning outcomes

            Some intro text.

            ## 1.1 Knowledge and understanding

            Body one.

            ## 1.3 Key skills

            Body three.
        """.trimIndent()

        val rendered = MarkdownRenderer.render(source)
        val anchor = extractHeadingAnchor(
            "Module%20Guide/1%20Learning%20outcomes.md#1.3%20Key%20skills"
        )
        assertEquals("1.3 Key skills", anchor)

        val displayStart = findHeadingDisplayStart(rendered.headings, anchor!!)
        assertEquals(
            "1.3 Key skills",
            rendered.text.text.substring(displayStart!!, displayStart + "1.3 Key skills".length)
        )
    }

    @Test
    fun `a block-ref-style anchor from toc md stays unresolved, reader opens at top`() {
        // [Summative assessment](...#s_3_2) in toc.md is a block-ref-style anchor, not a
        // heading — must not match any heading in the target note.
        val source = "# Module assessment\n\n## 3.1 Formative assessment\n\nBody.\n"
        val rendered = MarkdownRenderer.render(source)
        assertNull(findHeadingDisplayStart(rendered.headings, "s_3_2"))
    }
}
