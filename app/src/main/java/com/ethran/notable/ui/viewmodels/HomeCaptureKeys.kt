package com.ethran.notable.ui.viewmodels

/** Stable keys for home-grid captures (pinning, filter bookkeeping). */
object HomeCaptureKeys {
    fun vault(vaultId: String, relativePath: String) = "v:$vaultId:$relativePath"

    fun legacy(pageId: String) = "l:$pageId"
}

/**
 * Applies vault filter, pin-first ordering, and sort mode to home capture items.
 * Pinned items keep [pinnedKeys] order; unpinned items follow [sortMode].
 */
fun orderHomeCaptures(
    items: List<HomeCaptureItem>,
    sortMode: String,
    pinnedKeys: List<String>,
    vaultFilterIds: Set<String>,
    showLegacy: Boolean
): List<HomeCaptureItem> {
    val filtered = items.filter { item ->
        when (item) {
            is HomeCaptureItem.LegacyQuickPage -> showLegacy
            is HomeCaptureItem.VaultCapture ->
                vaultFilterIds.isEmpty() || item.vaultId in vaultFilterIds
        }
    }
    val validKeys = filtered.map { it.captureKey }.toSet()
    val pinOrder = pinnedKeys.filter { it in validKeys }
    val pinnedSet = pinOrder.toSet()
    val pinned = pinOrder.mapNotNull { key -> filtered.find { it.captureKey == key } }
    val unpinned = filtered.filter { it.captureKey !in pinnedSet }
    return pinned + sortHomeCaptures(unpinned, sortMode)
}

private fun sortHomeCaptures(items: List<HomeCaptureItem>, sortMode: String): List<HomeCaptureItem> =
    when (sortMode) {
        "name" -> items.sortedBy { it.sortKeyName.lowercase() }
        "nameDesc" -> items.sortedByDescending { it.sortKeyName.lowercase() }
        "oldest" -> items.sortedBy { it.sortKeyModified }
        else -> items.sortedByDescending { it.sortKeyModified }
    }
