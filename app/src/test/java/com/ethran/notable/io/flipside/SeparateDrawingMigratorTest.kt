package com.ethran.notable.io.flipside

import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import com.ethran.notable.io.vault.resolveAssociatedDrawingFile
import com.ethran.notable.io.vault.vaultRootDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeparateDrawingMigratorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    @Test
    fun `migrates unified note to linked raw drawing and keeps backup`() {
        val root = temporaryFolder.newFolder("Vault")
        val inbox = root.resolve("Inbox").apply { mkdirs() }
        val note = inbox.resolve("Capture.md")
        val stroke = Stroke(
            pageId = "page",
            size = 4f,
            pen = Pen.BALLPEN,
            points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
            left = 1f,
            top = 2f,
            right = 3f,
            bottom = 4f
        )
        note.writeText(ExcalidrawSerializer.serializeUnified("Recognized text", listOf(stroke)))
        val vault = VaultConfig(
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = "Attachments"
        )

        val result = SeparateDrawingMigrator.migrateVault(vault)

        assertEquals(1, result.migrated)
        assertTrue(result.failures.isEmpty())
        assertFalse(note.readText().contains("compressed-json"))
        assertTrue(note.readText().contains("Recognized text"))
        val drawing = resolveAssociatedDrawingFile(
            note,
            vaultRootDir(vault)!!,
            linkRoot = inbox
        )
        assertNotNull(drawing)
        assertTrue(drawing!!.name.endsWith(".excalidraw.md"))
        assertTrue(drawing.readText().contains("excalidraw-plugin: parsed"))
        assertNotNull(ExcalidrawSerializer.extractDrawingJson(drawing.readText()))
        assertTrue(
            inbox.resolve(
                "Attachments/.singularity/migration-backups/Inbox/Capture.md.unified-backup"
            ).isFile
        )
    }

    @Test
    fun `migration refuses attachment folder outside sync root`() {
        val root = temporaryFolder.newFolder("UnsafeVault")
        val inbox = root.resolve("Inbox").apply { mkdirs() }
        inbox.resolve("Capture.md")
            .writeText(ExcalidrawSerializer.serializeUnified("Text", emptyList()))
        val vault = VaultConfig(
            name = "Unsafe",
            inboxPath = inbox.absolutePath,
            attachmentPath = root.resolve("Attachments").absolutePath
        )

        val result = SeparateDrawingMigrator.migrateVault(vault)

        assertEquals(0, result.migrated)
        assertEquals(1, result.failures.size)
        assertTrue(inbox.resolve("Capture.md").readText().contains("compressed-json"))
    }

    @Test
    fun `migration leaves unrelated Obsidian drawings untouched`() {
        val root = temporaryFolder.newFolder("ObsidianVault")
        val inbox = root.resolve("Inbox").apply { mkdirs() }
        val drawingNote = inbox.resolve("Plugin Drawing.excalidraw.md")
        val original = ExcalidrawSerializer.serializeUnified("Plugin note", emptyList())
        drawingNote.writeText(original)
        val vault = VaultConfig(
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = "Attachments"
        )

        val result = SeparateDrawingMigrator.migrateVault(vault)

        assertEquals(0, result.migrated)
        assertTrue(result.failures.isEmpty())
        assertEquals(original, drawingNote.readText())
    }

    @Test
    fun `upgrades linked raw drawing to modern markdown format`() {
        val root = temporaryFolder.newFolder("LegacyRaw")
        val inbox = root.resolve("Inbox").apply { mkdirs() }
        val attachments = inbox.resolve("Attachments").apply { mkdirs() }
        val raw = attachments.resolve("Capture.excalidraw").apply {
            writeText("""{"type":"excalidraw","version":2,"elements":[]}""")
        }
        val note = inbox.resolve("Capture.md").apply {
            writeText(
                "---\nsingularity-drawing: \"[[Attachments/${raw.name}]]\"\n---\n\nText\n"
            )
        }
        val vault = VaultConfig(
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = "Attachments"
        )

        val result = SeparateDrawingMigrator.migrateVault(vault)

        assertEquals(1, result.migrated)
        val active = ExcalidrawSerializer.drawingLinkPath(note.readText())!!
        assertTrue(active.endsWith(".excalidraw.md"))
        assertTrue(inbox.resolve(active).readText().contains("```compressed-json"))
        assertTrue(raw.isFile)
    }
}
