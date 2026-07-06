package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.VaultConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultInboxCaptureTest {

    @Test
    fun `listInboxNotesWithInk returns only inbox notes with flip side`() {
        val root = Files.createTempDirectory("vault-root").toFile()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val noteWithInk = File(inbox, "2026-03-18-20-09-39.md")
        noteWithInk.writeText("# note")
        File(inbox, "2026-03-18-20-09-39.flip.excalidraw.md").writeText("%%")

        val noteNoInk = File(inbox, "plain.md")
        noteNoInk.writeText("# plain")

        val outside = File(root, "other.md")
        outside.writeText("# other")
        File(root, "other.flip.excalidraw.md").writeText("%%")

        val vault = VaultConfig(
            id = "test-vault",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )

        val notes = listInboxNotesWithInk(vault)
        assertEquals(1, notes.size)
        assertEquals("2026-03-18-20-09-39.md", notes[0].relativePath.substringAfterLast('/'))
        assertTrue(notes[0].hasInk)
    }

    @Test
    fun `inboxDirRelativeToVault resolves path under root`() {
        val root = Files.createTempDirectory("vault-root2").toFile()
        val inbox = File(root, "Study/inbox").apply { mkdirs() }
        val vault = VaultConfig(
            id = "v2",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        assertEquals("inbox", inboxDirRelativeToVault(vault))
    }

    @Test
    fun `listInboxNotesWithInkForVaults aggregates multiple vaults`() {
        fun vaultWithInk(id: String, dirName: String): VaultConfig {
            val root = Files.createTempDirectory("vault-$id").toFile()
            val inbox = File(root, dirName).apply { mkdirs() }
            val note = File(inbox, "capture.md")
            note.writeText("# note")
            File(inbox, "capture.flip.excalidraw.md").writeText("%%")
            return VaultConfig(
                id = id,
                name = id,
                inboxPath = inbox.absolutePath,
                attachmentPath = ""
            )
        }

        val v1 = vaultWithInk("vault-a", "inbox")
        val v2 = vaultWithInk("vault-b", "inbox")

        val pairs = listInboxNotesWithInkForVaults(listOf(v1, v2))
        assertEquals(2, pairs.size)
        assertEquals(setOf("vault-a", "vault-b"), pairs.map { it.first.id }.toSet())
        pairs.forEach { (_, note) ->
            assertTrue(note.hasInk)
            assertTrue(note.relativePath.endsWith("capture.md"))
        }
    }
}
