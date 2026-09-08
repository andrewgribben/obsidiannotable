package com.ethran.notable.io.flipside

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FlipSideManagerTest {

    @Test
    fun `frontmatterEndIndex spans the whole yaml block`() {
        val content = "---\nfoo: bar\n---\nbody"
        val end = FlipSideManager.frontmatterEndIndex(content)
        assertEquals("---\nfoo: bar\n---\n", content.substring(0, end))
        assertEquals("body", content.substring(end))
    }

    @Test
    fun `frontmatterEndIndex is zero without frontmatter`() {
        assertEquals(0, FlipSideManager.frontmatterEndIndex("no yaml here"))
        assertEquals(0, FlipSideManager.frontmatterEndIndex("--- not really\ntext"))
        // Unterminated block
        assertEquals(0, FlipSideManager.frontmatterEndIndex("---\nfoo: bar\nno end"))
    }

    @Test
    fun `addFrontmatterProperty inserts into existing block`() {
        val content = "---\ntitle: Test\n---\n\nbody"
        val updated = FlipSideManager.addFrontmatterProperty(content, "flip-side: \"[[X]]\"")
        assertEquals("---\ntitle: Test\nflip-side: \"[[X]]\"\n---\n\nbody", updated)
    }

    @Test
    fun `addFrontmatterProperty creates block when absent`() {
        val updated = FlipSideManager.addFrontmatterProperty("body", "flip-side: \"[[X]]\"")
        assertEquals("---\nflip-side: \"[[X]]\"\n---\n\nbody", updated)
    }

    @Test
    fun `flipSideNoteName strips extension and flip suffix`() {
        assertEquals("My Note", flipSideNoteName("folder/My Note.md"))
    }

    @Test
    fun `strokesFingerprint is empty for no strokes`() {
        assertEquals("empty", FlipSideManager.strokesFingerprint(emptyList()))
    }

    @Test
    fun `strokesFingerprint changes when stroke content changes`() {
        val stroke = Stroke(
            id = "s1",
            size = 3f,
            pen = Pen.BALLPEN,
            top = 0f,
            bottom = 10f,
            left = 0f,
            right = 10f,
            points = listOf(StrokePoint(1f, 2f)),
            pageId = "p1"
        )
        val baseline = FlipSideManager.strokesFingerprint(listOf(stroke))
        val changed = FlipSideManager.strokesFingerprint(
            listOf(stroke.copy(points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f))))
        )
        assertNotEquals(baseline, changed)

        val sameCountDifferentPoint = FlipSideManager.strokesFingerprint(
            listOf(stroke.copy(points = listOf(StrokePoint(8f, 9f))))
        )
        assertNotEquals(baseline, sameCountDifferentPoint)
    }
}
