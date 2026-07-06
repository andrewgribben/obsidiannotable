package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Page
import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.ui.views.VaultSort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Date

class HomeCaptureOrderTest {

    private fun vaultCapture(
        vaultId: String,
        path: String,
        name: String,
        lastModified: Long
    ): HomeCaptureItem.VaultCapture {
        val file = File(path)
        return HomeCaptureItem.VaultCapture(
            vaultId = vaultId,
            vaultName = vaultId,
            note = VaultNote(
                file = file,
                relativePath = path,
                name = name,
                hasInk = true,
                lastModified = lastModified
            ),
            previewPageId = null
        )
    }

    @Test
    fun `capture keys are stable`() {
        assertEquals("v:v1:inbox/a.md", HomeCaptureKeys.vault("v1", "inbox/a.md"))
        assertEquals("l:page-1", HomeCaptureKeys.legacy("page-1"))
    }

    @Test
    fun `pinned items keep pin order then unpinned sorted by newest`() {
        val a = vaultCapture("v1", "inbox/a.md", "a", 100L)
        val b = vaultCapture("v1", "inbox/b.md", "b", 300L)
        val c = vaultCapture("v1", "inbox/c.md", "c", 200L)
        val ordered = orderHomeCaptures(
            items = listOf(a, b, c),
            sortMode = VaultSort.NEWEST,
            pinnedKeys = listOf(a.captureKey, c.captureKey),
            vaultFilterIds = emptySet(),
            showLegacy = true
        )
        assertEquals(listOf(a, c, b), ordered)
    }

    @Test
    fun `vault filter hides captures from other vaults`() {
        val v1 = vaultCapture("v1", "inbox/a.md", "a", 100L)
        val v2 = vaultCapture("v2", "inbox/b.md", "b", 200L)
        val ordered = orderHomeCaptures(
            items = listOf(v1, v2),
            sortMode = VaultSort.NEWEST,
            pinnedKeys = emptyList(),
            vaultFilterIds = setOf("v1"),
            showLegacy = true
        )
        assertEquals(listOf(v1), ordered)
    }

    @Test
    fun `empty vault filter shows all vaults`() {
        val v1 = vaultCapture("v1", "inbox/a.md", "a", 100L)
        val v2 = vaultCapture("v2", "inbox/b.md", "b", 200L)
        val ordered = orderHomeCaptures(
            items = listOf(v1, v2),
            sortMode = VaultSort.NEWEST,
            pinnedKeys = emptyList(),
            vaultFilterIds = emptySet(),
            showLegacy = true
        )
        assertEquals(listOf(v2, v1), ordered)
    }

    @Test
    fun `pin order preserved when sort mode changes`() {
        val a = vaultCapture("v1", "inbox/a.md", "z", 100L)
        val b = vaultCapture("v1", "inbox/b.md", "a", 200L)
        val pinned = listOf(b.captureKey, a.captureKey)
        val byName = orderHomeCaptures(
            items = listOf(a, b),
            sortMode = VaultSort.NAME_ASC,
            pinnedKeys = pinned,
            vaultFilterIds = emptySet(),
            showLegacy = true
        )
        assertEquals(listOf(b, a), byName)
    }

    @Test
    fun `legacy items included when showLegacy is true`() {
        val legacy = HomeCaptureItem.LegacyQuickPage(
            Page(id = "legacy-1", updatedAt = Date(500L))
        )
        val vault = vaultCapture("v1", "inbox/a.md", "a", 100L)
        val ordered = orderHomeCaptures(
            items = listOf(vault, legacy),
            sortMode = VaultSort.NEWEST,
            pinnedKeys = emptyList(),
            vaultFilterIds = emptySet(),
            showLegacy = true
        )
        assertEquals(listOf(legacy, vault), ordered)
    }

    @Test
    fun `vault capture sort key uses newer of note and flip side mtime`() {
        val noteFile = java.io.File("/vault/inbox/capture.md")
        val flipFile = java.io.File("/vault/inbox/capture.flip.excalidraw.md")
        val item = HomeCaptureItem.VaultCapture(
            vaultId = "v1",
            vaultName = "Vault",
            note = VaultNote(
                file = noteFile,
                relativePath = "inbox/capture.md",
                name = "capture",
                hasInk = true,
                lastModified = 100L
            ),
            previewPageId = null
        )
        // Without a real flip file on disk, falls back to note mtime.
        assertEquals(100L, item.sortKeyModified)

        val tempDir = kotlin.io.path.createTempDirectory("flip-mtime").toFile()
        val note = java.io.File(tempDir, "capture.md").apply { writeText("# note") }
        val flip = java.io.File(tempDir, "capture.flip.excalidraw.md").apply { writeText("%%") }
        flip.setLastModified(500L)
        note.setLastModified(100L)
        val withInk = HomeCaptureItem.VaultCapture(
            vaultId = "v1",
            vaultName = "Vault",
            note = VaultNote(
                file = note,
                relativePath = "capture.md",
                name = "capture",
                hasInk = true,
                lastModified = note.lastModified()
            ),
            previewPageId = null
        )
        assertEquals(flip.lastModified(), withInk.sortKeyModified)
    }
}
