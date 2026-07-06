package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Page
import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.io.vault.flipSideFileFor

/** A row on the home screen capture grid. */
sealed class HomeCaptureItem {
    abstract val sortKeyModified: Long
    abstract val sortKeyName: String
    abstract val captureKey: String

    data class VaultCapture(
        val vaultId: String,
        val vaultName: String,
        val note: VaultNote,
        val previewPageId: String?
    ) : HomeCaptureItem() {
        override val sortKeyModified: Long = run {
            val inkModified = flipSideFileFor(note.file).takeIf { it.isFile }?.lastModified()
            if (inkModified != null) maxOf(note.lastModified, inkModified) else note.lastModified
        }
        override val sortKeyName: String = note.name
        override val captureKey: String = HomeCaptureKeys.vault(vaultId, note.relativePath)
    }

    data class LegacyQuickPage(val page: Page) : HomeCaptureItem() {
        override val sortKeyModified: Long = page.updatedAt.time
        override val sortKeyName: String = page.id
        override val captureKey: String = HomeCaptureKeys.legacy(page.id)
    }
}
