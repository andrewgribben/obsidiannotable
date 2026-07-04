package com.ethran.notable.io.markdown

import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `strikethrough hides markers and applies line-through style`() {
        val rendered = MarkdownRenderer.render("~~gone~~ and kept\n")
        val text = rendered.text.text
        assertTrue(text.contains("gone"))
        assertFalse(text.contains("~~"))

        val start = text.indexOf("gone")
        val struck = rendered.text.spanStyles.any {
            it.item.textDecoration == TextDecoration.LineThrough &&
                it.start <= start && it.end >= start + 4
        }
        assertTrue("expected a LineThrough span over 'gone'", struck)
    }

    @Test
    fun `highlight hides markers and applies background style`() {
        val rendered = MarkdownRenderer.render("This is ==important== text\n")
        val text = rendered.text.text
        assertTrue(text.contains("important"))
        assertFalse(text.contains("=="))

        val start = text.indexOf("important")
        val highlighted = rendered.text.spanStyles.any {
            it.item.background.alpha > 0f && it.start <= start && it.end >= start + 9
        }
        assertTrue("expected a background span over 'important'", highlighted)
    }

    @Test
    fun `bullet list items are rendered`() {
        val rendered = MarkdownRenderer.render("- first\n- second\n")
        val text = rendered.text.text
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
    }

    @Test
    fun `tables render rows with cell separators and bold header`() {
        val rendered = MarkdownRenderer.render(
            "| Name | Amount |\n|------|--------|\n| Coffee | 2 |\n| Tea | 5 |\n"
        )
        val text = rendered.text.text
        assertTrue(text.contains("Name"))
        assertTrue(text.contains("Coffee"))
        assertTrue(text.contains("Tea"))
        assertTrue("expected cell separator", text.contains("│"))
        assertFalse("raw pipe syntax should not remain", text.contains("|"))
        assertFalse("delimiter row should not remain", text.contains("---"))

        // Header row is on its own line above the body rows
        assertTrue(text.indexOf("Name") < text.indexOf("Coffee"))
    }

    @Test
    fun `bare urls become tappable links`() {
        val rendered = MarkdownRenderer.render("Go to https://example.com now\n")
        val links = rendered.links.filter { !it.isWikilink }
        assertEquals(1, links.size)
        assertEquals("https://example.com", links[0].target)
    }

    @Test
    fun `links inside bold text are still tappable`() {
        val rendered = MarkdownRenderer.render("**see [docs](https://x.com) here**\n")
        val links = rendered.links.filter { !it.isWikilink }
        assertEquals(1, links.size)
        assertEquals("https://x.com", links[0].target)
        assertTrue(rendered.text.text.contains("docs"))
    }

    @Test
    fun `wikilinks inside styled text are still tappable`() {
        val rendered = MarkdownRenderer.render("*see [[Other Note]] here*\n")
        val links = rendered.links.filter { it.isWikilink }
        assertEquals(1, links.size)
        assertEquals("Other Note", links[0].target)
        assertTrue(rendered.text.text.contains("Other Note"))
    }

    @Test
    fun `font scale multiplies text sizes`() {
        val small = MarkdownRenderer.render("# Title\n\nBody\n", fontScale = 1f)
        val large = MarkdownRenderer.render("# Title\n\nBody\n", fontScale = 1.5f)
        assertEquals(1.5f, large.theme.scale, 0.001f)
        assertEquals(small.theme.bodySize.value * 1.5f, large.theme.bodySize.value, 0.01f)
        assertEquals(small.theme.lineHeight.value * 1.5f, large.theme.lineHeight.value, 0.01f)
    }
}
