package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.VaultConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BookshelfIndexStoreTest {

    @After
    fun tearDown() {
        BookshelfIndexStore.invalidateAll()
    }

    private fun testVault(): VaultConfig {
        val root = Files.createTempDirectory("bookshelf-vault").toFile()
        val inbox = java.io.File(root, "inbox").apply { mkdirs() }
        return VaultConfig(
            id = "vault-test",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
    }

    @Test
    fun `load missing file returns empty index`() {
        val vault = testVault()
        val index = BookshelfIndexStore.load(vault)
        assertTrue(index.entries.isEmpty())
        assertTrue(index.archivedPaths.isEmpty())
    }

    @Test
    fun `add remove entry round trip`() {
        val vault = testVault()
        BookshelfIndexStore.addEntry(vault, "Notes/foo.md", BookshelfKind.NOTE)
        val loaded = BookshelfIndexStore.load(vault)
        assertEquals(1, loaded.entries.size)
        assertEquals("Notes/foo.md", loaded.entries[0].path)
        assertEquals(BookshelfKind.NOTE, loaded.entries[0].kind)

        BookshelfIndexStore.removeEntry(vault, "Notes/foo.md")
        assertTrue(BookshelfIndexStore.load(vault).entries.isEmpty())
    }

    @Test
    fun `setPinned creates entry when missing`() {
        val vault = testVault()
        BookshelfIndexStore.setPinned(vault, "inbox/capture.md", pinned = true)
        val entry = BookshelfIndexStore.load(vault).entries.single()
        assertTrue(entry.pinned)
        assertTrue(entry.pinnedAt > 0L)
    }

    @Test
    fun `archivePath removes entry and records archived path`() {
        val vault = testVault()
        BookshelfIndexStore.addEntry(vault, "Projects/plan.md", BookshelfKind.NOTE)
        BookshelfIndexStore.archivePath(vault, "Projects/plan.md")
        val index = BookshelfIndexStore.load(vault)
        assertTrue(index.entries.isEmpty())
        assertTrue("Projects/plan.md" in index.archivedPaths)
    }

    @Test
    fun `loadWithPinMigration imports legacy AppSettings pins`() {
        val vault = testVault()
        val pinKey = "v:${vault.id}:inbox/pinned.md"
        val settings = AppSettings(
            version = 1,
            homePinnedCaptureKeys = listOf(pinKey)
        )
        val migrated = BookshelfIndexStore.loadWithPinMigration(vault, settings)
        val entry = migrated.entries.single()
        assertEquals("inbox/pinned.md", entry.path)
        assertTrue(entry.pinned)
        assertTrue(BookshelfIndexStore.load(vault).entries.single().pinned)
    }

    @Test
    fun `renamePath updates entry and archived paths`() {
        val vault = testVault()
        BookshelfIndexStore.addEntry(vault, "old.md", BookshelfKind.NOTE)
        BookshelfIndexStore.archivePath(vault, "archived.md")
        BookshelfIndexStore.renamePath(vault, "old.md", "new.md")
        val index = BookshelfIndexStore.load(vault)
        assertEquals("new.md", index.entries.single().path)
        assertTrue("archived.md" in index.archivedPaths)
    }

    @Test
    fun `addEntry unarchives path`() {
        val vault = testVault()
        BookshelfIndexStore.archivePath(vault, "back.md")
        BookshelfIndexStore.addEntry(vault, "back.md", BookshelfKind.NOTE)
        val index = BookshelfIndexStore.load(vault)
        assertFalse("back.md" in index.archivedPaths)
        assertEquals("back.md", index.entries.single().path)
    }
}
