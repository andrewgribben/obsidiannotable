package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.datastore.AppSettings

/** Stable keys for home-grid captures (pinning, filter bookkeeping). */
object HomeCaptureKeys {
    fun vault(vaultId: String, relativePath: String) = "v:$vaultId:$relativePath"

    fun parseVaultKey(key: String): Pair<String, String>? {
        if (!key.startsWith("v:")) return null
        val rest = key.removePrefix("v:")
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        return rest.substring(0, colon) to rest.substring(colon + 1)
    }

    /** Moves pin and cover entries when a capture file is renamed. */
    fun migrateCaptureKey(
        settings: AppSettings,
        oldKey: String,
        newKey: String
    ): AppSettings {
        val pins = settings.homePinnedCaptureKeys.map { if (it == oldKey) newKey else it }
        val covers = settings.homeCaptureCoverImages.toMutableMap()
        covers.remove(oldKey)?.let { covers[newKey] = it }
        return settings.copy(
            homePinnedCaptureKeys = pins,
            homeCaptureCoverImages = covers
        )
    }

    /** Clears pin and cover metadata for a deleted vault path (and optional descendants). */
    fun clearCaptureKeys(
        settings: AppSettings,
        vaultId: String,
        relativePath: String,
        includeDescendants: Boolean
    ): AppSettings {
        fun matches(key: String): Boolean {
            val parsed = parseVaultKey(key) ?: return false
            if (parsed.first != vaultId) return false
            val path = parsed.second
            return path == relativePath ||
                (includeDescendants && path.startsWith("$relativePath/"))
        }
        return settings.copy(
            homePinnedCaptureKeys = settings.homePinnedCaptureKeys.filterNot { matches(it) },
            homeCaptureCoverImages = settings.homeCaptureCoverImages.filterKeys { !matches(it) }
        )
    }
}

/**
 * Applies vault filter, pin-first ordering, and sort mode to home capture items.
 * Pinned items keep [pinnedKeys] order; unpinned items follow [sortMode].
 */
fun orderHomeCaptures(
    items: List<HomeCaptureItem>,
    sortMode: String,
    pinnedKeys: List<String>,
    vaultFilterIds: Set<String>
): List<HomeCaptureItem> {
    val filtered = items.filter { item ->
        vaultFilterIds.isEmpty() || item.vaultId in vaultFilterIds
    }
    val validKeys = filtered.map { it.captureKey }.toSet()
    val pinOrder = pinnedKeys.filter { it in validKeys }
    val pinnedSet = pinOrder.toSet()
    val pinned = pinOrder.mapNotNull { key -> filtered.find { it.captureKey == key } }
    val unpinned = filtered.filter { it.captureKey !in pinnedSet }
    return pinned + sortHomeCaptures(unpinned, sortMode)
}

/**
 * Applies vault filter, pin-first ordering (by [HomeCaptureItem.pinnedAt]), and sort mode.
 */
fun orderHomeBookshelfItems(
    items: List<HomeCaptureItem>,
    sortMode: String,
    vaultFilterIds: Set<String>
): List<HomeCaptureItem> {
    val filtered = items.filter { item ->
        vaultFilterIds.isEmpty() || item.vaultId in vaultFilterIds
    }
    val pinned = filtered.filter { it.isPinned }.sortedBy { it.pinnedAt }
    val unpinned = filtered.filter { !it.isPinned }
    return pinned + sortHomeBookshelfUnpinned(unpinned, sortMode)
}

private fun sortHomeBookshelfUnpinned(
    items: List<HomeCaptureItem>,
    sortMode: String
): List<HomeCaptureItem> = sortHomeCaptures(items, sortMode)

private fun sortHomeCaptures(items: List<HomeCaptureItem>, sortMode: String): List<HomeCaptureItem> =
    when (sortMode) {
        "name" -> items.sortedBy { it.sortKeyName.lowercase() }
        "nameDesc" -> items.sortedByDescending { it.sortKeyName.lowercase() }
        "oldest" -> items.sortedBy { it.sortKeyModified }
        else -> items.sortedByDescending { it.sortKeyModified }
    }
