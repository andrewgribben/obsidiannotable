package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultInboxCaptureTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    @Test
    fun `listInboxNotesWithInk returns only inbox unified excalidraw captures`() {
        val root = Files.createTempDirectory("vault-root").toFile()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val noteWithInk = File(inbox, "2026-03-18-20-09-39.md")
        noteWithInk.writeText(ExcalidrawSerializer.serializeUnified("# note", emptyList()))

        val noteNoInk = File(inbox, "plain.md")
        noteNoInk.writeText("# plain")

        val outside = File(root, "other.md")
        outside.writeText(ExcalidrawSerializer.serializeUnified("# other", emptyList()))

        val vault = VaultConfig(
            id = "test-vault",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )

        val notes = listInboxNotesWithInk(vault)
        assertEquals(1, notes.size)
        assertEquals("2026-03-18-20-09-39.md", notes[0].relativePath.substringAfterLast('/'))
        assertFalse(notes[0].hasInk)
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
    fun `obsidianSyncVaultRoot is inbox folder and vaultRootDir is parent`() {
        val container = Files.createTempDirectory("vault-container").toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        val vault = VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        assertEquals(container, vaultRootDir(vault))
        assertEquals(inbox, obsidianSyncVaultRoot(vault))
    }

    @Test
    fun `sync relative path omits inbox folder prefix`() {
        val container = Files.createTempDirectory("vault-sync-path").toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        val capture = File(inbox, "2026-07-06.md").apply { writeText("# note") }
        val vault = VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val syncRoot = obsidianSyncVaultRoot(vault)!!
        val appRoot = vaultRootDir(vault)!!

        assertEquals("Notes/2026-07-06.md", capture.relativeTo(appRoot).path.replace('\\', '/'))
        assertEquals("2026-07-06.md", capture.relativeTo(syncRoot).path.replace('\\', '/'))
    }

    @Test
    fun `drawing link cannot escape its vault root`() {
        val root = Files.createTempDirectory("vault-safe-link").toFile()
        val inbox = File(root, "Notes").apply { mkdirs() }
        val outside = File(root.parentFile, "outside.excalidraw").apply {
            writeText("""{"type":"excalidraw","elements":[]}""")
        }
        val note = File(inbox, "note.md").apply {
            writeText("---\nsingularity-drawing: \"[[../../outside.excalidraw]]\"\n---\n")
        }
        try {
            assertNull(resolveAssociatedDrawingFile(note, root, linkRoot = inbox))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `listInboxNotesWithInkForVaults aggregates multiple vaults`() {
        fun vaultWithInk(id: String, dirName: String): VaultConfig {
            val root = Files.createTempDirectory("vault-$id").toFile()
            val inbox = File(root, dirName).apply { mkdirs() }
            val note = File(inbox, "capture.md")
            note.writeText(ExcalidrawSerializer.serializeUnified("# note", emptyList()))
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
            assertTrue(note.relativePath.endsWith("capture.md"))
        }
    }
}
