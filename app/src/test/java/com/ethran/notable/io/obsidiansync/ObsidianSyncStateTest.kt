package com.ethran.notable.io.obsidiansync

import com.ethran.notable.io.VaultFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ObsidianSyncStateTest {

    @Test
    fun load_missingFile_returnsEmptyState() {
        val dir = createTempDirectory().toFile()
        val state = ObsidianSyncStateStore.load(dir)
        assertEquals("", state.vaultUid)
        assertEquals(0, state.version)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun load_validFile() {
        val dir = createTempDirectory().toFile()
        val path = File(dir, ObsidianSyncStateStore.STATE_FILE_NAME)
        path.writeText(
            """
            {
              "vault_uid": "vault123",
              "version": 42,
              "files": {
                "notes/foo.md": {
                  "hash": "abc123",
                  "sync_hash": "def456",
                  "mtime": 1709553600000,
                  "ctime": 1709553600000,
                  "size": 1234
                }
              }
            }
            """.trimIndent()
        )

        val state = ObsidianSyncStateStore.load(dir)
        assertEquals("vault123", state.vaultUid)
        assertEquals(42, state.version)
        val file = state.files["notes/foo.md"]
        assertNotNull(file)
        assertEquals("abc123", file!!.hash)
        assertEquals("def456", file.syncHash)
        assertEquals(1234, file.size)
    }

    @Test
    fun load_nullFilesMap_initializesEmpty() {
        val dir = createTempDirectory().toFile()
        File(dir, ObsidianSyncStateStore.STATE_FILE_NAME).writeText(
            """{"vault_uid":"v1","version":1,"files":null}"""
        )

        val state = ObsidianSyncStateStore.load(dir)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun load_invalidJson() {
        val dir = createTempDirectory().toFile()
        File(dir, ObsidianSyncStateStore.STATE_FILE_NAME).writeText("{bad json")
        assertThrows(Exception::class.java) {
            ObsidianSyncStateStore.load(dir)
        }
    }

    @Test
    fun save_roundTrip() {
        val dir = createTempDirectory().toFile()
        val original = ObsidianSyncState(
            vaultUid = "round-trip-vault",
            version = 9999,
            files = mutableMapOf(
                "a.md" to ObsidianSyncFileState(
                    hash = "h1",
                    syncHash = "s1",
                    mtime = 100,
                    ctime = 200,
                    size = 10
                ),
                "sub/b.md" to ObsidianSyncFileState(
                    hash = "h2",
                    syncHash = "s2",
                    mtime = 300,
                    ctime = 400,
                    size = 20
                )
            )
        )

        ObsidianSyncStateStore.save(dir, original)
        val loaded = ObsidianSyncStateStore.load(dir)
        assertEquals(original.vaultUid, loaded.vaultUid)
        assertEquals(original.version, loaded.version)
        assertEquals(original.files, loaded.files)
        assertFalse(File(dir, "${ObsidianSyncStateStore.STATE_FILE_NAME}.tmp").exists())
    }

    @Test
    fun save_overwritesExisting() {
        val dir = createTempDirectory().toFile()
        ObsidianSyncStateStore.save(
            dir,
            ObsidianSyncState(
                vaultUid = "v1",
                version = 1,
                files = mutableMapOf("old.md" to ObsidianSyncFileState(hash = "old", syncHash = "old"))
            )
        )
        ObsidianSyncStateStore.save(
            dir,
            ObsidianSyncState(
                vaultUid = "v1",
                version = 2,
                files = mutableMapOf("new.md" to ObsidianSyncFileState(hash = "new", syncHash = "new"))
            )
        )

        val loaded = ObsidianSyncStateStore.load(dir)
        assertEquals(2, loaded.version)
        assertNull(loaded.files["old.md"])
        assertNotNull(loaded.files["new.md"])
    }

    @Test
    fun reconcileHashesFromDisk_updatesBlankSyncHash() {
        val dir = createTempDirectory().toFile()
        val note = File(dir, "notes/a.md").apply {
            parentFile.mkdirs()
            writeText("hello")
        }
        val hash = VaultFileStore.hashOf(note.readBytes())
        val state = ObsidianSyncState(
            files = mutableMapOf(
                "notes/a.md" to ObsidianSyncFileState(
                    hash = "",
                    syncHash = "",
                    mtime = note.lastModified(),
                    ctime = note.lastModified(),
                    size = note.length()
                )
            )
        )

        ObsidianSyncStateStore.reconcileHashesFromDisk(dir, state)

        assertEquals(hash, state.files["notes/a.md"]!!.syncHash)
        assertEquals(hash, state.files["notes/a.md"]!!.hash)
    }

    @Test
    fun needsBootstrap_trueWhenEmpty() {
        assertTrue(ObsidianSyncStateStore.needsBootstrap(ObsidianSyncState()))
        assertTrue(
            ObsidianSyncStateStore.needsBootstrap(
                ObsidianSyncState(version = 5, files = mutableMapOf())
            )
        )
        assertFalse(
            ObsidianSyncStateStore.needsBootstrap(
                ObsidianSyncState(
                    version = 0,
                    files = mutableMapOf("a.md" to ObsidianSyncFileState(syncHash = "x"))
                )
            )
        )
    }

    @Test
    fun hasStaleWrongRootState_detectsInboxPrefixMismatch() {
        val container = createTempDirectory().toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        File(inbox, "capture.md").writeText("hello")
        val vault = com.ethran.notable.data.datastore.VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val state = ObsidianSyncState(
            version = 100,
            files = mutableMapOf(
                "Notes/capture.md" to ObsidianSyncFileState(syncHash = "x")
            )
        )

        assertTrue(ObsidianSyncStateStore.hasStaleWrongRootState(vault, inbox, state))
    }

    @Test
    fun hasStaleWrongRootState_falseWhenPathsMatch() {
        val inbox = createTempDirectory().toFile()
        File(inbox, "capture.md").writeText("hello")
        val vault = com.ethran.notable.data.datastore.VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        val state = ObsidianSyncState(
            version = 100,
            files = mutableMapOf(
                "capture.md" to ObsidianSyncFileState(syncHash = "x")
            )
        )

        assertFalse(ObsidianSyncStateStore.hasStaleWrongRootState(vault, inbox, state))
    }

    @Test
    fun prepareSyncRootState_resetsStaleState() {
        val container = createTempDirectory().toFile()
        val inbox = File(container, "Notes").apply { mkdirs() }
        File(inbox, "capture.md").writeText("hello")
        val vault = com.ethran.notable.data.datastore.VaultConfig(
            id = "notes",
            name = "Notes",
            inboxPath = inbox.absolutePath,
            attachmentPath = ""
        )
        ObsidianSyncStateStore.save(
            inbox,
            ObsidianSyncState(
                vaultUid = "remote",
                version = 500,
                files = mutableMapOf(
                    "Notes/capture.md" to ObsidianSyncFileState(syncHash = "old")
                )
            )
        )

        val prepared = ObsidianSyncStateStore.prepareSyncRootState(vault, inbox)

        assertEquals(0, prepared.version)
        assertTrue(prepared.files.isEmpty())
        val reloaded = ObsidianSyncStateStore.load(inbox)
        assertEquals(0, reloaded.version)
        assertTrue(reloaded.files.isEmpty())
    }
}
