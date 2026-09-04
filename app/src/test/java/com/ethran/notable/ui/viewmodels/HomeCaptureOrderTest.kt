package com.ethran.notable.ui.viewmodels

import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.ui.views.VaultSort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HomeCaptureOrderTest {

    private fun vaultCapture(
        vaultId: String,
        path: String,
        name: String,
        lastModified: Long
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
            previewPageId = null
        )
    }

    @Test
    fun `capture keys are stable`() {
        assertEquals("v:v1:inbox/a.md", HomeCaptureKeys.vault("v1", "inbox/a.md"))
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
            vaultFilterIds = emptySet()
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
            vaultFilterIds = setOf("v1")
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
            vaultFilterIds = emptySet()
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
            vaultFilterIds = emptySet()
        )
        assertEquals(listOf(b, a), byName)
    }

    @Test
    fun `sort key uses note file mtime`() {
        val item = vaultCapture("v1", "inbox/capture.md", "capture", 100L)
        assertEquals(100L, item.sortKeyModified)
    }
}
