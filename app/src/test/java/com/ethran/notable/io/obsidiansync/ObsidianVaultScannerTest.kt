package com.ethran.notable.io.obsidiansync

import com.ethran.notable.io.VaultFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ObsidianVaultScannerTest {

    @Test
    fun scan_includesRegularFiles() {
        val root = createTempDirectory().toFile()
        File(root, "notes/a.md").apply {
            parentFile.mkdirs()
            writeText("hello")
        }

        val files = ObsidianVaultScanner.scan(root)
        assertEquals(VaultFileStore.hashOf("hello"), files["notes/a.md"])
    }

    @Test
    fun scan_skipsStateFileAndHiddenPaths() {
        val root = createTempDirectory().toFile()
        File(root, "visible.md").writeText("ok")
        File(root, ObsidianSyncStateStore.STATE_FILE_NAME).writeText("{}")
        File(root, ".hidden.md").writeText("secret")
        File(root, ".trash").apply {
            mkdirs()
            File(this, "gone.md").writeText("x")
        }
        File(root, ".obsidian/workspace.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }

        val files = ObsidianVaultScanner.scan(root)
        assertEquals(2, files.size)
        assertTrue(files.containsKey("visible.md"))
        assertTrue(files.containsKey(".obsidian/workspace.json"))
        assertFalse(files.containsKey(ObsidianSyncStateStore.STATE_FILE_NAME))
        assertFalse(files.containsKey(".hidden.md"))
        assertFalse(files.containsKey(".trash/gone.md"))
    }

    @Test
    fun scan_usesForwardSlashes() {
        val root = createTempDirectory().toFile()
        File(root, "a/b/c.md").apply {
            parentFile.mkdirs()
            writeText("nested")
        }

        val files = ObsidianVaultScanner.scan(root)
        assertTrue(files.containsKey("a/b/c.md"))
    }
}
