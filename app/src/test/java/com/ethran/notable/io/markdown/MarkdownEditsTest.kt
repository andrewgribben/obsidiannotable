package com.ethran.notable.io.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditsTest {

    @Test
    fun `highlight wraps word`() {
        val result = MarkdownEdits.apply(
            "hello world", listOf(MarkdownEdit.Highlight(6..10))
        )
        assertEquals("hello ==world==", result)
    }

    @Test
    fun `bold wraps word`() {
        val result = MarkdownEdits.apply(
            "hello world", listOf(MarkdownEdit.Bold(6..10))
        )
        assertEquals("hello **world**", result)
    }

    @Test
    fun `strikethrough wraps word`() {
        val result = MarkdownEdits.apply(
            "hello world", listOf(MarkdownEdit.Strikethrough(6..10))
        )
        assertEquals("hello ~~world~~", result)
    }

    @Test
    fun `wrap keeps surrounding whitespace outside the markers`() {
        val result = MarkdownEdits.apply(
            "hello brave world", listOf(MarkdownEdit.Highlight(5..11))
        )
        assertEquals("hello ==brave== world", result)
    }

    @Test
    fun `wrap keeps trailing punctuation outside the markers`() {
        val result = MarkdownEdits.apply(
            "hello world!", listOf(MarkdownEdit.Bold(6..11))
        )
        assertEquals("hello **world**!", result)
    }

    @Test
    fun `delete removes word and collapses whitespace`() {
        val result = MarkdownEdits.apply(
            "hello brave world", listOf(MarkdownEdit.Delete(6..10))
        )
        assertEquals("hello world", result)
    }

    @Test
    fun `delete at end of text swallows leading space`() {
        val result = MarkdownEdits.apply(
            "hello world", listOf(MarkdownEdit.Delete(6..10))
        )
        assertEquals("hello", result)
    }

    @Test
    fun `multiple non-overlapping edits apply back to front`() {
        val result = MarkdownEdits.apply(
            "alpha beta gamma",
            listOf(
                MarkdownEdit.Highlight(0..4),
                MarkdownEdit.Strikethrough(11..15)
            )
        )
        assertEquals("==alpha== beta ~~gamma~~", result)
    }

    @Test
    fun `overlapping edits drop the earlier-starting one`() {
        val result = MarkdownEdits.apply(
            "abcdefgh",
            listOf(
                MarkdownEdit.Highlight(0..4),
                MarkdownEdit.Bold(2..6)
            )
        )
        assertEquals("ab**cdefg**h", result)
    }

    @Test
    fun `out of bounds range is clamped`() {
        val result = MarkdownEdits.apply(
            "short", listOf(MarkdownEdit.Highlight(0..100))
        )
        assertEquals("==short==", result)
    }

    @Test
    fun `expandToWordBounds snaps to whitespace boundaries`() {
        assertEquals(6 until 11, MarkdownEdits.expandToWordBounds("hello world", 7, 8))
        assertEquals(0 until 5, MarkdownEdits.expandToWordBounds("hello world", 0, 2))
        assertEquals(6 until 11, MarkdownEdits.expandToWordBounds("hello world", 6, 11))
    }
}
