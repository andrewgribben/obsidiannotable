package com.ethran.notable.io.flipside

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FlipSideManagerCaptureTest {

    @Test
    fun `buildCaptureUnifiedStub has excalidraw frontmatter`() {
        val stub = FlipSideManager.buildCaptureUnifiedStub(Date(1700000000000L))
        assertTrue(stub.contains("excalidraw-plugin: parsed"))
        assertTrue(stub.contains("created:"))
        assertFalse(stub.contains("flip-side:"))
        assertFalse(stub.contains("pdf:"))
        assertTrue(stub.contains("compressed-json"))
    }

    @Test
    fun `stripDrawingFromUnified keeps markdown text`() {
        val unified = ExcalidrawSerializer.serializeUnified("Lyrics here", emptyList())
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(unified)
        assertTrue(stripped.contains("Lyrics here"))
        assertFalse(stripped.contains("compressed-json"))
    }
}
