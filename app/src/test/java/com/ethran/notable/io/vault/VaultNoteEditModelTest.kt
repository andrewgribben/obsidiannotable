package com.ethran.notable.io.vault

import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultNoteEditModelTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    @Test
    fun `split plain markdown has empty prefix and suffix`() {
        val source = "# Title\n\nHello world\n"
        val regions = VaultNoteEditModel.splitForEditing(source)
        assertEquals("", regions.prefix)
        assertEquals("# Title\n\nHello world", regions.editableBody)
        assertEquals("", regions.suffix)
        assertFalse(regions.isUnified)
    }

    @Test
    fun `merge plain markdown round trip`() {
        val source = "# Title\n\nHello world\n"
        val regions = VaultNoteEditModel.splitForEditing(source)
        val merged = VaultNoteEditModel.mergeAfterEdit(regions, "# Title\n\nUpdated body")
        val again = VaultNoteEditModel.splitForEditing(merged)
        assertEquals("Updated body", again.editableBody.substringAfterLast("\n\n").trim())
        assertEquals("", again.suffix)
    }

    @Test
    fun `unified note preserves drawing suffix on body edit`() {
        val unified = ExcalidrawSerializer.serializeUnified("# Song\n\nVerse one", emptyList())
        val regions = VaultNoteEditModel.splitForEditing(unified)
        assertTrue(regions.isUnified)
        assertTrue(regions.suffix.isNotEmpty())
        assertTrue(regions.suffix.contains("```"))
        assertTrue(regions.editableBody.contains("# Song"))
        assertTrue(regions.editableBody.contains("Verse one"))

        val merged = VaultNoteEditModel.mergeAfterEdit(regions, "# Song\n\nVerse two")
        assertTrue(merged.contains("Verse two"))
        assertFalse(merged.contains("Verse one"))
        assertTrue(merged.contains(regions.suffix))

        val reSplit = VaultNoteEditModel.splitForEditing(merged)
        assertTrue(reSplit.suffix.isNotEmpty())
        assertEquals(regions.suffix.trimEnd(), reSplit.suffix.trimEnd())
        assertTrue(reSplit.editableBody.contains("Verse two"))
    }

    @Test
    fun `paired comment in body stays editable not suffix`() {
        val source = """
            # Title

            Visible line.
            %% hidden comment %%
            Still visible.
        """.trimIndent() + "\n"
        val regions = VaultNoteEditModel.splitForEditing(source)
        assertEquals("", regions.suffix)
        assertTrue(regions.editableBody.contains("%% hidden comment %%"))
        val merged = VaultNoteEditModel.mergeAfterEdit(regions, "Edited body")
        assertTrue(merged.contains("Edited body"))
        assertFalse(merged.contains("hidden comment"))
    }

    @Test
    fun `frontmatter stays in prefix`() {
        val source = """
            ---
            tags: [note]
            ---

            Body here
        """.trimIndent() + "\n"
        val regions = VaultNoteEditModel.splitForEditing(source)
        assertTrue(regions.prefix.startsWith("---"))
        assertEquals("Body here", regions.editableBody)
        val merged = VaultNoteEditModel.mergeAfterEdit(regions, "New body")
        assertTrue(merged.startsWith("---"))
        assertTrue(merged.contains("tags: [note]"))
        assertTrue(merged.contains("New body"))
    }
}
