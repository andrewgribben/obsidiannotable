package com.ethran.notable.io.flipside

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FlipSideManagerCaptureTest {

    @Test
    fun `buildCaptureUnifiedStub has excalidraw frontmatter and no flip-side`() {
        val stub = FlipSideManager.buildCaptureUnifiedStub(Date(1700000000000L))
        assertTrue(stub.contains("excalidraw-plugin: parsed"))
        assertTrue(stub.contains("created:"))
        assertFalse(stub.contains("flip-side:"))
        assertFalse(stub.contains("pdf:"))
        assertTrue(stub.contains("compressed-json"))
    }

    @Test
    fun `ensureCaptureFrontmatter strips pdf and produces unified excalidraw`() {
        val existing = """
            ---
            created: "[[2026-03-18]]"
            pdf: "[[2026-03-18-20-09-39.pdf]]"
            ---

            Some body
        """.trimIndent()
        val updated = FlipSideManager.ensureCaptureFrontmatter(
            existing,
            "ignored.flip.excalidraw"
        )
        assertFalse(updated.contains("pdf:"))
        assertFalse(updated.contains("flip-side:"))
        assertTrue(updated.contains("excalidraw-plugin: parsed"))
        assertTrue(updated.contains("Some body"))
        assertTrue(updated.contains("compressed-json"))
    }

    @Test
    fun `stripDrawingFromUnified keeps markdown text`() {
        val unified = ExcalidrawSerializer.serializeUnified("Lyrics here", emptyList())
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(unified)
        assertTrue(stripped.contains("Lyrics here"))
        assertFalse(stripped.contains("compressed-json"))
    }
}
