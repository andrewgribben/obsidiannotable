package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawTestTemplate
import com.ethran.notable.io.vault.BookshelfEntry
import com.ethran.notable.io.vault.BookshelfIndex
import com.ethran.notable.io.vault.BookshelfKind
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.io.vault.listInboxNotesWithInk
import com.ethran.notable.ui.views.VaultSort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HomeBookshelfOrderTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    @After
    fun tearDown() {
        VaultIndexRegistry.invalidateAll()
    }

    private fun noteItem(
        vaultId: String,
        path: String,
        name: String,
        lastModified: Long,
        pinned: Boolean = false,
        pinnedAt: Long = 0L,
        isBookshelfOnly: Boolean = false
    ): HomeCaptureItem {
        val file = File(path)
        return HomeCaptureItem(
            vaultId = vaultId,
            vaultName = vaultId,
            note = VaultNote(
                file = file,
                relativePath = path,
                name = name,
                hasInk = true,
                lastModified = lastModified
            ),
            previewPageId = null,
            isPinned = pinned,
            pinnedAt = pinnedAt,
            isBookshelfOnly = isBookshelfOnly
        )
    }

    @Test
    fun `orderHomeBookshelfItems pins by pinnedAt then sorts unpinned`() {
        val a = noteItem("v1", "a.md", "a", 100L, pinned = true, pinnedAt = 20L)
        val b = noteItem("v1", "b.md", "b", 300L, pinned = true, pinnedAt = 10L)
        val c = noteItem("v1", "c.md", "c", 200L)
        val ordered = orderHomeBookshelfItems(
            items = listOf(a, b, c),
            sortMode = VaultSort.NEWEST,
            vaultFilterIds = emptySet()
        )
        assertEquals(listOf(b, a, c), ordered)
    }

    @Test
    fun `buildRootItems dedupes inbox and explicit entries`() {
        val root = Files.createTempDirectory("bookshelf-dedupe").toFile()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val capture = File(inbox, "2026-07-06.md")
        capture.writeText(ExcalidrawSerializer.serializeUnified("# note", emptyList()))

        val vault = VaultConfig(
            id = "v1",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val inboxNotes = listInboxNotesWithInk(vault)
        assertEquals(1, inboxNotes.size)

        val index = BookshelfIndex(
            entries = listOf(
                BookshelfEntry(
                    path = inboxNotes[0].relativePath,
                    kind = BookshelfKind.NOTE,
                    pinned = true,
                    pinnedAt = 99L
                )
            )
        )
        val input = HomeBookshelfBuilder.BuildInput(
            vault = vault,
            index = index,
            bookshelfDir = "",
            coverImages = emptyMap(),
            previewPageIds = emptyMap()
        )
        val items = HomeBookshelfBuilder.buildRootItems(listOf(input))
        assertEquals(1, items.size)
        assertTrue(items[0].isPinned)
        assertEquals(99L, items[0].pinnedAt)
    }

    @Test
    fun `buildRootItems hides archived paths`() {
        val root = Files.createTempDirectory("bookshelf-archived").toFile()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val capture = File(inbox, "2026-07-06.md")
        capture.writeText(ExcalidrawSerializer.serializeUnified("# note", emptyList()))
        val vault = VaultConfig(
            id = "v1",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val path = listInboxNotesWithInk(vault).single().relativePath
        val index = BookshelfIndex(archivedPaths = setOf(path))
        val input = HomeBookshelfBuilder.BuildInput(
            vault = vault,
            index = index,
            bookshelfDir = "",
            coverImages = emptyMap(),
            previewPageIds = emptyMap()
        )
        assertTrue(HomeBookshelfBuilder.buildRootItems(listOf(input)).isEmpty())
    }
}

class HomeBookshelfFolderTest {

    @After
    fun tearDown() {
        VaultIndexRegistry.invalidateAll()
    }

    @Test
    fun `folder drill lists direct md children only`() {
        val root = Files.createTempDirectory("bookshelf-folder").toFile()
        val inbox = File(root, "inbox").apply { mkdirs() }
        val projects = File(root, "Projects").apply { mkdirs() }
        File(projects, "one.md").writeText("# one")
        File(projects, "two.md").writeText("# two")
        File(projects, "nested").mkdirs()
        File(projects, "nested/three.md").writeText("# three")

        val vault = VaultConfig(
            id = "folder-vault",
            name = "Test",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        VaultIndexRegistry.forVault(vault)?.refresh()

        val input = HomeBookshelfBuilder.BuildInput(
            vault = vault,
            index = BookshelfIndex(),
            bookshelfDir = "Projects",
            coverImages = emptyMap(),
            previewPageIds = emptyMap()
        )
        val items = HomeBookshelfBuilder.buildFolderItems(input, "Projects") { false }
        val names = items.map { it.note.name }.sorted()
        assertEquals(listOf("one", "two"), names)
        assertFalse(items.any { it.isFolder })
    }
}
