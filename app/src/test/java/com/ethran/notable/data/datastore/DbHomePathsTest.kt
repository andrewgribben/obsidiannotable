package com.ethran.notable.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class DbHomePathsTest {
    private val primary = VaultConfig(id = "p", inboxPath = "Notes/Notes", attachmentPath = "Notes/Notes/Att")
    private val study = VaultConfig(id = "s", inboxPath = "Notes/Study", attachmentPath = "Notes/Study/Att")

    @Test
    fun usesPrimaryVaultEvenWhenAnotherVaultIsActive() {
        val settings = AppSettings(version = 1, vaults = listOf(primary, study), activeVaultId = "s")
            .normalizedVaults()
        // Legacy fields mirror the active vault; the database must not follow them.
        assertEquals("Notes/Study", settings.obsidianInboxPath)
        assertEquals("Notes/Notes" to "Notes/Notes/Att", settings.dbHomePaths)
    }

    @Test
    fun fallsBackToBootstrapPathsBeforeVaultsLoad() {
        val settings = AppSettings(
            version = 1,
            obsidianInboxPath = "Notes/Notes",
            obsidianAttachmentPath = "Notes/Notes/Att"
        )
        assertEquals("Notes/Notes" to "Notes/Notes/Att", settings.dbHomePaths)
    }
}
