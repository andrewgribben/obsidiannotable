package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.VaultConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultNotePathsTest {

    @Test
    fun vaultRelativeToSyncPath_stripsInboxPrefix() {
        val container = Files.createTempDirectory("vault-paths").toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        val vault = VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        assertEquals("foo.md", vaultRelativeToSyncPath(vault, "Notes/foo.md"))
        assertEquals("Meta/bar.md", vaultRelativeToSyncPath(vault, "Notes/Meta/bar.md"))
    }

    @Test
    fun syncPathToVaultRelative_addsInboxPrefix() {
        val container = Files.createTempDirectory("vault-paths2").toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        val vault = VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        assertEquals("Notes/foo.md", syncPathToVaultRelative(vault, "foo.md"))
        assertEquals("Notes/Meta/bar.md", syncPathToVaultRelative(vault, "Meta/bar.md"))
    }

    @Test
    fun resolveVaultNoteFile_findsFileUnderSyncRootLayout() {
        val container = Files.createTempDirectory("vault-resolve").toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        val note = File(inbox, "remote-note.md").apply { writeText("# hello") }
        val vault = VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val resolved = resolveVaultNoteFile(vault, "Notes/remote-note.md")
        assertEquals(note.absolutePath, resolved?.absolutePath)
        assertTrue(resolved?.exists() == true)
    }
}
