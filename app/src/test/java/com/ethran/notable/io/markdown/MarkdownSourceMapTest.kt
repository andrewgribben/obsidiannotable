package com.ethran.notable.io.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSourceMapTest {

    // Display: [0,5) maps 1:1 to source [10,15); display [5,9) is a construct whose
    // whole source range is [15,30) (e.g. a wikilink where display shows only the name).
    private val map = MarkdownSourceMap(
        listOf(
            MarkdownSourceMap.Segment(0, 5, 10, 15, linear = true),
            MarkdownSourceMap.Segment(5, 9, 15, 30, linear = false)
        )
    )

    @Test
    fun `linear segment maps offsets 1 to 1`() {
        assertEquals(10, map.displayToSource(0))
        assertEquals(12, map.displayToSource(2))
        assertEquals(14, map.displayToSource(4))
    }

    @Test
    fun `non-linear segment maps any offset to the source start`() {
        assertEquals(15, map.displayToSource(5))
        assertEquals(15, map.displayToSource(8))
    }

    @Test
    fun `offset outside all segments is unmapped`() {
        assertNull(map.displayToSource(9))
        assertNull(map.displayToSource(100))
    }

    @Test
    fun `range within linear segment maps proportionally`() {
        assertEquals(11 until 14, map.displayRangeToSource(1, 4))
    }

    @Test
    fun `range touching non-linear segment expands to its whole source range`() {
        // Never split markup like wikilink brackets
        assertEquals(13 until 30, map.displayRangeToSource(3, 7))
        assertEquals(15 until 30, map.displayRangeToSource(6, 7))
    }

    @Test
    fun `range with no mapped source returns null`() {
        assertNull(map.displayRangeToSource(20, 25))
    }

    @Test
    fun `empty map reports empty`() {
        assertTrue(MarkdownSourceMap(emptyList()).isEmpty())
    }
}
