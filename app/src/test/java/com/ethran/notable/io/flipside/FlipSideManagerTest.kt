package com.ethran.notable.io.flipside

import org.junit.Assert.assertEquals
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
}
