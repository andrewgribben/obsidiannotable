package com.ethran.notable.io.flipside

import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FlipSideManagerCaptureTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    @Test
    fun `resolveDefaultNativeBackground honors global template`() {
        val original = GlobalAppSettings.current
        try {
            GlobalAppSettings.update(original.copy(defaultNativeTemplate = "lined"))
            assertEquals("lined", FlipSideManager.resolveDefaultNativeBackground())
            GlobalAppSettings.update(original.copy(defaultNativeTemplate = ""))
            assertEquals("blank", FlipSideManager.resolveDefaultNativeBackground())
        } finally {
            GlobalAppSettings.update(original)
        }
    }

    @Test
    fun `sanitizeCaptureBaseName rejects invalid names`() {
        assertEquals("My Note", FlipSideManager.sanitizeCaptureBaseName("My Note"))
        assertEquals("My Note", FlipSideManager.sanitizeCaptureBaseName("My Note.md"))
        assertNull(FlipSideManager.sanitizeCaptureBaseName(""))
        assertNull(FlipSideManager.sanitizeCaptureBaseName("bad/name"))
        assertNull(FlipSideManager.sanitizeCaptureBaseName("bad:name"))
    }

    @Test
    fun `buildCaptureNoteStub is small plain markdown`() {
        val stub = FlipSideManager.buildCaptureNoteStub(Date(1700000000000L))
        assertTrue(stub.contains("created:"))
        assertFalse(stub.contains("flip-side:"))
        assertFalse(stub.contains("pdf:"))
        assertFalse(stub.contains("excalidraw-plugin:"))
        assertFalse(stub.contains("%%"))
    }

    @Test
    fun `stripDrawingFromUnified keeps markdown text`() {
        val unified = ExcalidrawSerializer.serializeUnified("Lyrics here", emptyList())
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(unified)
        assertTrue(stripped.contains("Lyrics here"))
        assertFalse(stripped.contains("```compressed-json"))
        assertFalse(stripped.contains("excalidraw-plugin:"))
    }

    @Test
    fun `stripDrawingFromUnified keeps non-excalidraw frontmatter`() {
        val unified = """
            ---
            created: "[[2026-03-14]]"
            excalidraw-plugin: parsed
            excalidraw-open-md: true
            tags:
              - project
              - excalidraw
            ---

            Study notes here

            %%
            # Excalidraw Data
            ## Drawing
            ```json
            {"type":"excalidraw","version":2,"elements":[{"type":"freedraw","x":0,"y":0,"points":[[0,0],[1,1]],"isDeleted":false}]}
            ```
            %%
        """.trimIndent()
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(unified)
        assertTrue(stripped.contains("Study notes here"))
        assertTrue(stripped.contains("created:"))
        assertTrue(stripped.contains("  - project"))
        assertFalse(stripped.contains("  - excalidraw"))
        assertFalse(stripped.contains("excalidraw-plugin:"))
        assertFalse(stripped.contains("excalidraw-open-md:"))
        assertFalse(stripped.contains("# Excalidraw Data"))
        assertFalse(stripped.contains("%%"))
    }

    @Test
    fun `stripDrawingFromUnified preserves ordinary Obsidian comments`() {
        val unified = ExcalidrawSerializer.serializeUnified(
            "Visible\n\n# Drawing ideas\n\n%% keep this comment %%\n\nMore",
            emptyList()
        )
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(unified)
        assertTrue(stripped.contains("# Drawing ideas"))
        assertTrue(stripped.contains("%% keep this comment %%"))
        assertTrue(stripped.contains("More"))
        assertFalse(stripped.contains("# Excalidraw Data"))
    }

    @Test
    fun `stripDrawingFromUnified preserves earlier Drawing json example`() {
        val body = """
            # Drawing

            ```json
            {"example": true}
            ```

            Keep this paragraph.
        """.trimIndent()
        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(
            ExcalidrawSerializer.serializeUnified(body, emptyList())
        )
        assertTrue(stripped.contains("""{"example": true}"""))
        assertTrue(stripped.contains("Keep this paragraph."))
        assertFalse(stripped.contains("# Excalidraw Data"))
    }
}
