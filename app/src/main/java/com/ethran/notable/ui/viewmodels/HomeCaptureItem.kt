package com.ethran.notable.ui.viewmodels

import com.ethran.notable.io.vault.VaultNote

/** A vault note or folder tile on the home bookshelf grid. */
data class HomeCaptureItem(
    val vaultId: String,
    val vaultName: String,
    val note: VaultNote,
    val previewPageId: String?,
    val coverImagePath: String? = null,
    val isFolder: Boolean = false,
    val isBookshelfOnly: Boolean = false,
    val isPinned: Boolean = false,
    val pinnedAt: Long = 0L,
) {
    val sortKeyModified: Long = note.lastModified
    val sortKeyName: String = note.name
    val captureKey: String = HomeCaptureKeys.vault(vaultId, note.relativePath)
}
