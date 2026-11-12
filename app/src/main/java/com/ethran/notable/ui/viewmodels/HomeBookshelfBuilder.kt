package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.vault.BookshelfIndex
import com.ethran.notable.io.vault.BookshelfIndexStore
import com.ethran.notable.io.vault.BookshelfKind
import com.ethran.notable.io.vault.VaultIndex
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.io.vault.listInboxNotesWithInk
import com.ethran.notable.io.vault.resolveVaultNoteFile
import com.ethran.notable.io.vault.vaultRootDir
import java.io.File

object HomeBookshelfBuilder {

    data class BuildInput(
        val vault: VaultConfig,
        val index: BookshelfIndex,
        val bookshelfDir: String,
        val coverImages: Map<String, String>,
        val previewPageIds: Map<String, String>,
    )

    fun buildRootItems(inputs: List<BuildInput>): List<HomeCaptureItem> {
        // When any vault has a bookshelf folder open, the home screen shows ONLY that
        // folder's contents: a real file hierarchy, not the home-root inbox captures
        // and pinned notes/folders from the dashboard.
        val openFolderInputs = inputs.filter { it.bookshelfDir.trim().trim('/').isNotEmpty() }
        if (openFolderInputs.isNotEmpty()) {
            val byKey = linkedMapOf<String, HomeCaptureItem>()
            for (input in openFolderInputs) {
                val bookshelfDir = input.bookshelfDir.trim().trim('/')
                val archived: (String) -> Boolean = { path -> path in input.index.archivedPaths }
                buildFolderItems(input, bookshelfDir, archived).forEach { item ->
                    byKey[item.captureKey] = item
                }
            }
            return byKey.values.toList()
        }

        val byKey = linkedMapOf<String, HomeCaptureItem>()
        for (input in inputs) {
            val vault = input.vault
            val archived: (String) -> Boolean = { path -> path in input.index.archivedPaths }
            for (note in listInboxNotesWithInk(vault)) {
                if (archived(note.relativePath)) continue
                val item = noteToItem(
                    vault = vault,
                    note = note,
                    coverImages = input.coverImages,
                    previewPageIds = input.previewPageIds,
                    pinnedAt = BookshelfIndexStore.pinnedAtForPath(input.index, note.relativePath),
                    isBookshelfOnly = false
                )
                byKey[item.captureKey] = item
            }
            for (entry in input.index.entries) {
                if (archived(entry.path)) continue
                val key = HomeCaptureKeys.vault(vault.id, entry.path)
                if (key in byKey) {
                    val existing = byKey.getValue(key)
                    byKey[key] = existing.copy(
                        isPinned = entry.pinned,
                        pinnedAt = entry.pinnedAt,
                        isBookshelfOnly = false
                    )
                    continue
                }
                val note = entryToNote(vault, entry) ?: continue
                byKey[key] = noteToItem(
                    vault = vault,
                    note = note,
                    coverImages = input.coverImages,
                    previewPageIds = input.previewPageIds,
                    pinnedAt = entry.pinnedAt,
                    isBookshelfOnly = true,
                    isFolder = entry.kind == BookshelfKind.FOLDER,
                    isPinned = entry.pinned
                )
            }
        }
        return byKey.values.toList()
    }

    /**
     * Contents of a single folder: its direct subfolders and files (notes), the same
     * children [com.ethran.notable.io.vault.VaultIndex.listDir] shows in the vault
     * browser, so a pinned folder behaves as a real, navigable file hierarchy.
     */
    fun buildFolderItems(
        input: BuildInput,
        relativeDir: String,
        archivedFilter: (String) -> Boolean
    ): List<HomeCaptureItem> {
        val vault = input.vault
        val vaultIndex = VaultIndexRegistry.forVault(vault) ?: return emptyList()
        return vaultIndex.listDir(relativeDir)
            .filter { !archivedFilter(it.relativePath) }
            .map { note ->
                noteToItem(
                    vault = vault,
                    note = note,
                    coverImages = input.coverImages,
                    previewPageIds = input.previewPageIds,
                    pinnedAt = BookshelfIndexStore.pinnedAtForPath(input.index, note.relativePath),
                    isBookshelfOnly = false,
                    isFolder = note.isFolder,
                    isPinned = input.index.entries.any {
                        it.path == note.relativePath && it.pinned
                    }
                )
            }
    }

    fun isArchived(index: BookshelfIndex, path: String): Boolean = path in index.archivedPaths

    /**
     * Computes the next home-bookshelf folder path when going "up" one level.
     * Returns null when the hierarchy should close entirely (back to the home root):
     * that's when [currentDir] is already the originally-opened pinned folder
     * ([rootDir]), or there is no current directory.
     */
    fun stepUpFolder(currentDir: String, rootDir: String): String? {
        if (currentDir.isEmpty() || currentDir == rootDir) return null
        return currentDir.substringBeforeLast('/', "")
    }

    private fun entryToNote(vault: VaultConfig, entry: com.ethran.notable.io.vault.BookshelfEntry): VaultNote? {
        val root = vaultRootDir(vault) ?: return null
        return if (entry.kind == BookshelfKind.FOLDER) {
            val dir = File(root, entry.path.replace('/', File.separatorChar))
            if (!dir.isDirectory) return null
            VaultNote(
                file = dir,
                relativePath = entry.path,
                name = entry.path.substringAfterLast('/'),
                hasInk = false,
                lastModified = dir.lastModified(),
                isFolder = true
            )
        } else {
            val file = resolveVaultNoteFile(vault, entry.path) ?: return null
            if (!file.isFile) return null
            val indexed = VaultIndexRegistry.forVault(vault)?.allNotes()
                ?.find { it.relativePath == entry.path }
            VaultNote(
                file = file,
                relativePath = entry.path,
                name = file.name.removeSuffix(".md"),
                hasInk = indexed?.hasInk == true,
                lastModified = file.lastModified()
            )
        }
    }

    private fun noteToItem(
        vault: VaultConfig,
        note: VaultNote,
        coverImages: Map<String, String>,
        previewPageIds: Map<String, String>,
        pinnedAt: Long,
        isBookshelfOnly: Boolean,
        isFolder: Boolean = note.isFolder,
        isPinned: Boolean = pinnedAt > 0L
    ): HomeCaptureItem {
        val captureKey = HomeCaptureKeys.vault(vault.id, note.relativePath)
        return HomeCaptureItem(
            vaultId = vault.id,
            vaultName = vault.displayName,
            note = note,
            previewPageId = previewPageIds[captureKey],
            coverImagePath = coverImages[captureKey],
            isFolder = isFolder,
            isBookshelfOnly = isBookshelfOnly,
            isPinned = isPinned,
            pinnedAt = pinnedAt
        )
    }
}
