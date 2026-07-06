package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.datastore.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCaptureKeysTest {

    @Test
    fun `migrateCaptureKey moves pin and cover entries`() {
        val oldKey = HomeCaptureKeys.vault("vault-1", "inbox/old.md")
        val newKey = HomeCaptureKeys.vault("vault-1", "inbox/new.md")
        val settings = AppSettings(
            version = 1,
            homePinnedCaptureKeys = listOf(oldKey, "other"),
            homeCaptureCoverImages = mapOf(oldKey to "/covers/photo.jpg")
        )

        val migrated = HomeCaptureKeys.migrateCaptureKey(settings, oldKey, newKey)

        assertEquals(listOf(newKey, "other"), migrated.homePinnedCaptureKeys)
        assertEquals(mapOf(newKey to "/covers/photo.jpg"), migrated.homeCaptureCoverImages)
        assertNull(migrated.homeCaptureCoverImages[oldKey])
    }
}
