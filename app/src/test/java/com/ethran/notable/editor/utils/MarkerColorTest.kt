package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerColorTest {

    @Test
    fun `markerColorArgb preserves rgb and applies marker opacity`() {
        val opaqueRed = 0xFFFF8040.toInt()
        val markerRed = markerColorArgb(opaqueRed)

        assertEquals(MARKER_OPACITY, markerRed ushr 24 and 0xFF)
        assertEquals(255, markerRed shr 16 and 0xFF)
        assertEquals(128, markerRed shr 8 and 0xFF)
        assertEquals(64, markerRed and 0xFF)
    }

    @Test
    fun `markerPreviewColorArgb is fully opaque with same rgb`() {
        val translucentYellow = markerColorArgb(0xFFFFFF00.toInt())
        val previewYellow = markerPreviewColorArgb(translucentYellow)

        assertEquals(255, previewYellow ushr 24 and 0xFF)
        assertEquals(255, previewYellow shr 16 and 0xFF)
        assertEquals(255, previewYellow shr 8 and 0xFF)
        assertEquals(0, previewYellow and 0xFF)
    }
}
