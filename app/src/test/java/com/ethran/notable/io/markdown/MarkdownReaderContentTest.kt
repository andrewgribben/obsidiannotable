package com.ethran.notable.io.markdown

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReaderContentTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }
    fun `prepareForTextView drops excalidraw drawing tail`() {
        val unified = ExcalidrawSerializer.serializeUnified("# Song\n\nVerse one", emptyList())
        val visible = MarkdownReaderContent.prepareForTextView(unified)
        assertTrue(visible.contains("# Song"))
        assertTrue(visible.contains("Verse one"))
        assertFalse(visible.contains("```json"))
        assertFalse(visible.contains("==⚠"))
        assertFalse(visible.contains("%%"))
    }

    @Test
    fun `prepareForTextView removes paired markdown comment blocks`() {
        val source = """
            # Title

            Visible line.
            %% hidden excalidraw junk %%
            Still visible.
        """.trimIndent()
        val visible = MarkdownReaderContent.prepareForTextView(source)
        assertTrue(visible.contains("Visible line."))
        assertTrue(visible.contains("Still visible."))
        assertFalse(visible.contains("hidden excalidraw"))
        assertFalse(visible.contains("%%"))
    }

    @Test
    fun `prepareForTextView hides obsidian wrapped drawing block`() {
        val source = """
            ---
            excalidraw-plugin: parsed
            ---
            # Lyrics

            %%
            ==⚠ Switch to EXCALIDRAW VIEW
            ## Drawing
            ```compressed-json
            abc123
            ```
            %%
        """.trimIndent()
        val visible = MarkdownReaderContent.prepareForTextView(source)
        assertTrue(visible.contains("# Lyrics"))
        assertFalse(visible.contains("EXCALIDRAW"))
        assertFalse(visible.contains("abc123"))
    }

    @Test
    fun `prepareForTextView keeps body text at original source offsets`() {
        val full = ExcalidrawSerializer.serializeUnified("Annotate me", emptyList())
        val start = full.indexOf("Annotate me")
        val prepared = MarkdownReaderContent.prepareForTextView(full)
        assertEquals("Annotate me", prepared.substring(start, start + "Annotate me".length))
    }

    @Test
    fun `renderForReader does not show drawing section`() {
        val unified = ExcalidrawSerializer.serializeUnified("Hello reader", emptyList())
        val rendered = MarkdownRenderer.renderForReader(unified)
        assertTrue(rendered.text.text.contains("Hello reader"))
        assertFalse(rendered.text.text.contains("Drawing"))
        assertFalse(rendered.text.text.contains("EXCALIDRAW"))
    }
}
