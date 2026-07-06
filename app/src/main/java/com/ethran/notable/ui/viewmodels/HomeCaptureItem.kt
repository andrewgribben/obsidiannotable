package com.ethran.notable.ui.viewmodels

import com.ethran.notable.io.vault.VaultNote

/** A vault inbox capture on the home screen grid. */
data class HomeCaptureItem(
    val vaultId: String,
    val vaultName: String,
    val note: VaultNote,
    val previewPageId: String?
) {
    val sortKeyModified: Long = note.lastModified
    val sortKeyName: String = note.name
    val captureKey: String = HomeCaptureKeys.vault(vaultId, note.relativePath)
}
