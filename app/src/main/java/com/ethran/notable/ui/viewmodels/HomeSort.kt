package com.ethran.notable.ui.viewmodels

import com.ethran.notable.ui.views.VaultSort

/** Sort modes and labels for the home capture grid. */
object HomeSort {
    val modes = listOf(
        VaultSort.NAME_ASC,
        VaultSort.NAME_DESC,
        VaultSort.NEWEST,
        VaultSort.OLDEST
    )

    fun label(mode: String): String = when (mode) {
        VaultSort.NAME_DESC -> "Name Z–A"
        VaultSort.NEWEST -> "Date modified (newest)"
        VaultSort.OLDEST -> "Date modified (oldest)"
        else -> "Name A–Z"
    }
}
