package com.ethran.notable.data.datastore

import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import java.util.UUID


// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    fun update(settings: AppSettings) {
        _current.value = settings
    }
}

/**
 * A registered Obsidian vault. The vault root is the parent of [inboxPath].
 * [attachmentPath] uses the same semantics as the legacy setting: blank = next to
 * the note, otherwise absolute-ish storage path or relative to inbox.
 */
@Serializable
data class VaultConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val inboxPath: String = "",
    val attachmentPath: String = "",
) {
    /** Display name falling back to the vault root folder name. */
    val displayName: String
        get() = name.ifBlank { defaultVaultName(inboxPath) }

    companion object {
        /** Vault name derived from the inbox path: the parent folder of the inbox. */
        fun defaultVaultName(inboxPath: String): String {
            val segments = inboxPath.trim().trim('/').split('/').filter { it.isNotBlank() }
            return when {
                segments.size >= 2 -> segments[segments.size - 2]
                segments.size == 1 -> segments[0]
                else -> "Vault"
            }
        }
    }
}


@Serializable
data class AppSettings(
    // General
    val version: Int,
    val monitorBgFiles: Boolean = false,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val neoTools: Boolean = false,
    val scribbleToEraseEnabled: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val continuousZoom: Boolean = false,
    val continuousStrokeSlider: Boolean = false,
    val monochromeMode: Boolean = false,
    val paginatePdf: Boolean = true,
    val visualizePdfPagination: Boolean = false,

    // Gestures
    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
    val holdAction: GestureAction? = defaultHoldAction,
    val enableQuickNav: Boolean = true,


    // Inbox Capture — legacy single-vault fields. These always mirror the *active*
    // vault so existing consumers keep working; the registry below is the source of truth.
    val obsidianInboxPath: String = "Documents/primary/inbox",
    val obsidianAttachmentPath: String = "Documents/primary/attachments",

    // Vault registry. vaults[0] is the "primary" vault whose attachment dir hosts the
    // app database (never moved by switching); activeVaultId selects the vault used for
    // capture, browsing and exports.
    val vaults: List<VaultConfig> = emptyList(),
    val activeVaultId: String = "",

    // Recently opened vault notes (relative paths), keyed by vault id. Drives the
    // quick switcher's initial list.
    val recentNotesByVault: Map<String, List<String>> = emptyMap(),

    // Note reader font scale (1.0 = default 17sp body).
    val readerFontScale: Float = 1f,

    // Vault browser presentation: file sort order (see VaultSort) and list vs grid.
    val vaultSortMode: String = "name",
    val vaultBrowserGrid: Boolean = false,

    // Home/library page-grid sort order (see HomeSort; "newest" = modified desc).
    val homeSortMode: String = "newest",

    // Home grid vault filter: empty = all vaults; non-empty = only listed vault ids.
    val homeVaultFilterIds: Set<String> = emptySet(),

    // Ordered pin keys for home captures (see HomeCaptureKeys).
    val homePinnedCaptureKeys: List<String> = emptyList(),

    /** One-time migration of legacy quick pages → flip-side captures (see FlipSideManager). */
    val legacyQuickPagesMigrated: Boolean = false,

    // Last browsed folder per vault in the in-app vault browser (relative path, "" = root).
    val vaultBrowserDirByVault: Map<String, String> = emptyMap(),

    // Which vault row is expanded in Settings → Vaults (empty = all collapsed).
    val settingsExpandedVaultId: String = "",

    // Debug
    val showWelcome: Boolean = true,
    // [system information -- does not have a setting]
    val debugMode: Boolean = false,
    val simpleRendering: Boolean = false,
    val openGLRendering: Boolean = true,
    val muPdfRendering: Boolean = true,
    val destructiveMigrations: Boolean = false,

    ) {

    /** The currently active vault, falling back to the primary vault. */
    val activeVault: VaultConfig?
        get() = vaults.find { it.id == activeVaultId } ?: vaults.firstOrNull()

    /** The primary vault: hosts the app database. Never changes with active-vault switching. */
    val primaryVault: VaultConfig?
        get() = vaults.firstOrNull()

    /**
     * Normalizes the vault registry:
     * 1. Migrates the legacy single-vault fields into `vaults[0]` when the registry is empty.
     * 2. Ensures `activeVaultId` points at a registered vault.
     * 3. Mirrors the active vault's paths into the legacy fields so existing consumers
     *    (capture, exports, tag scanning) follow the active vault.
     */
    fun normalizedVaults(): AppSettings {
        var result = this
        if (result.vaults.isEmpty() && result.obsidianInboxPath.isNotBlank()) {
            val migrated = VaultConfig(
                name = VaultConfig.defaultVaultName(result.obsidianInboxPath),
                inboxPath = result.obsidianInboxPath,
                attachmentPath = result.obsidianAttachmentPath
            )
            result = result.copy(vaults = listOf(migrated), activeVaultId = migrated.id)
        }
        if (result.vaults.isNotEmpty() && result.vaults.none { it.id == result.activeVaultId }) {
            result = result.copy(activeVaultId = result.vaults.first().id)
        }
        val active = result.activeVault
        if (active != null &&
            (active.inboxPath != result.obsidianInboxPath || active.attachmentPath != result.obsidianAttachmentPath)
        ) {
            result = result.copy(
                obsidianInboxPath = active.inboxPath,
                obsidianAttachmentPath = active.attachmentPath
            )
        }
        return result
    }

    companion object {
        val defaultDoubleTapAction get() = GestureAction.Undo
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction get() = GestureAction.ToggleZen
        val defaultHoldAction get() = GestureAction.Select
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }
}