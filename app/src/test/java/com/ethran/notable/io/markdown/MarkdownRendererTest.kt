package com.ethran.notable.io.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `renders headings and body text`() {
        val rendered = MarkdownRenderer.render("# Title\n\nHello world.\n")
        val text = rendered.text.text
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("Hello world."))
    }

    @Test
    fun `wikilinks become tappable links showing the note name`() {
        val rendered = MarkdownRenderer.render("See [[Other Note]] for details.\n")
        assertTrue(rendered.text.text.contains("Other Note"))

        val wikilinks = rendered.links.filter { it.isWikilink }
        assertEquals(1, wikilinks.size)
        assertEquals("Other Note", wikilinks[0].target)
    }

    @Test
    fun `aliased wikilink displays the alias but targets the note`() {
        val rendered = MarkdownRenderer.render("See [[Other Note|the docs]].\n")
        assertTrue(rendered.text.text.contains("the docs"))
        val wikilinks = rendered.links.filter { it.isWikilink }
        assertEquals(1, wikilinks.size)
        assertEquals("Other Note", wikilinks[0].target)
    }

    @Test
    fun `markdown links are extracted with their targets`() {
        val rendered = MarkdownRenderer.render("A [site](https://example.com) link.\n")
        val mdLinks = rendered.links.filter { !it.isWikilink }
        assertEquals(1, mdLinks.size)
        assertEquals("https://example.com", mdLinks[0].target)
        assertTrue(rendered.text.text.contains("site"))
    }

    @Test
    fun `frontmatter is parsed and excluded from the rendered body`() {
        val source = "---\ntitle: Test Note\ntags: [a, b]\n---\n\nBody text here.\n"
        val rendered = MarkdownRenderer.render(source)
        assertEquals(listOf("Test Note"), rendered.frontmatter["title"])
        assertTrue(rendered.text.text.contains("Body text here."))
        // The body starts after the frontmatter block in the source
        assertTrue(rendered.bodySourceStart > 0)
        assertTrue(source.substring(rendered.bodySourceStart).contains("Body text here."))
    }

    @Test
    fun `source map round trips a body word back to its source range`() {
        val source = "# Title\n\nHello brave world.\n"
        val rendered = MarkdownRenderer.render(source)
        val display = rendered.text.text
        val displayStart = display.indexOf("brave")
        assertTrue(displayStart >= 0)

        val sourceRange = rendered.sourceMap.displayRangeToSource(displayStart, displayStart + 5)
        assertNotNull(sourceRange)
        assertEquals("brave", source.substring(sourceRange!!.first, sourceRange.last + 1))
    }

    @Test
    fun `wikilink maps to its whole source span so edits never split it`() {
        val source = "Before [[Target]] after.\n"
        val rendered = MarkdownRenderer.render(source)
        val display = rendered.text.text
        val displayStart = display.indexOf("Target")
        assertTrue(displayStart >= 0)

        val sourceRange = rendered.sourceMap.displayRangeToSource(displayStart, displayStart + 3)
        assertNotNull(sourceRange)
        val covered = source.substring(sourceRange!!.first, sourceRange.last + 1)
        assertTrue(covered.contains("[[Target]]"))
    }

    @Test
    fun `strikethrough and highlight render their inner text`() {
        val rendered = MarkdownRenderer.render("~~gone~~ and ==kept==\n")
        val text = rendered.text.text
        assertTrue(text.contains("gone"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun `bullet list items are rendered`() {
        val rendered = MarkdownRenderer.render("- first\n- second\n")
        val text = rendered.text.text
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
    }
}
