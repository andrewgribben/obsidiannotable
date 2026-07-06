package com.ethran.notable.io.flipside

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FlipSideManagerCaptureTest {

    @Test
    fun `buildCaptureNoteStub includes flip-side and no pdf`() {
        val stub = FlipSideManager.buildCaptureNoteStub(
            Date(1700000000000L),
            "2026-03-18-20-09-39.flip.excalidraw"
        )
        assertTrue(stub.contains("flip-side: \"[[2026-03-18-20-09-39.flip.excalidraw]]\""))
        assertTrue(stub.contains("created:"))
        assertFalse(stub.contains("pdf:"))
    }

    @Test
    fun `ensureCaptureFrontmatter strips pdf and adds flip-side`() {
        val existing = """
            ---
            created: "[[2026-03-18]]"
            pdf: "[[2026-03-18-20-09-39.pdf]]"
            ---

            Some body
        """.trimIndent()
        val updated = FlipSideManager.ensureCaptureFrontmatter(
            existing,
            "2026-03-18-20-09-39.flip.excalidraw"
        )
        assertFalse(updated.contains("pdf:"))
        assertTrue(updated.contains("flip-side: \"[[2026-03-18-20-09-39.flip.excalidraw]]\""))
        assertTrue(updated.contains("Some body"))
    }

    @Test
    fun `ensureCaptureFrontmatter leaves existing flip-side`() {
        val existing = """
            ---
            flip-side: "[[note.flip.excalidraw]]"
            ---
        """.trimIndent()
        val updated = FlipSideManager.ensureCaptureFrontmatter(existing, "other.flip.excalidraw")
        assertTrue(updated.contains("flip-side: \"[[note.flip.excalidraw]]\""))
    }
}
