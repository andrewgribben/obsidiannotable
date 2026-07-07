package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.resolveVaultAttachmentDir
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class BookshelfKind {
    NOTE,
    FOLDER,
}

@Serializable
data class BookshelfEntry(
    val path: String,
    val kind: BookshelfKind,
    val pinned: Boolean = false,
    val pinnedAt: Long = 0,
    val addedAt: Long = 0,
)

@Serializable
data class BookshelfIndex(
    val version: Int = 1,
    val entries: List<BookshelfEntry> = emptyList(),
    val archivedPaths: Set<String> = emptySet(),
)

@Serializable
private data class BookshelfIndexJson(
    val version: Int = 1,
    val entries: List<BookshelfEntry>? = null,
    val archivedPaths: Set<String>? = null,
)

/**
 * Per-vault home bookshelf metadata at
 * `{attachmentDir}/.singularity/bookshelf.json`.
 */
object BookshelfIndexStore {

    const val INDEX_FILE_NAME = "bookshelf.json"
    private const val SINGULARITY_DIR = ".singularity"
    private val log = ShipBook.getLogger("BookshelfIndexStore")
    private val cache = mutableMapOf<String, BookshelfIndex>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    fun indexFile(vault: VaultConfig): File? {
        val attachmentDir = resolveVaultAttachmentDir(vault.inboxPath, vault.attachmentPath)
            ?: return null
        return File(File(attachmentDir, SINGULARITY_DIR), INDEX_FILE_NAME)
    }

    fun load(vault: VaultConfig): BookshelfIndex {
        cache[vault.id]?.let { return it }
        val file = indexFile(vault) ?: return BookshelfIndex()
        if (!file.exists()) {
            val empty = BookshelfIndex()
            cache[vault.id] = empty
            return empty
        }
        val dto = json.decodeFromString<BookshelfIndexJson>(file.readText(Charsets.UTF_8))
        val index = BookshelfIndex(
            version = dto.version,
            entries = dto.entries.orEmpty(),
            archivedPaths = dto.archivedPaths.orEmpty()
        )
        cache[vault.id] = index
        return index
    }

    fun save(vault: VaultConfig, index: BookshelfIndex) {
        val file = indexFile(vault) ?: return
        file.parentFile?.mkdirs()
        val dto = BookshelfIndexJson(
            version = index.version,
            entries = index.entries,
            archivedPaths = index.archivedPaths
        )
        val payload = json.encodeToString(dto) + "\n"
        val tmp = File(file.parentFile, "$INDEX_FILE_NAME.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IllegalStateException("failed to replace ${file.absolutePath}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("failed to commit ${file.absolutePath}")
        }
        cache[vault.id] = index
        log.i("Saved bookshelf index for ${vault.displayName} (${index.entries.size} entries)")
    }

    fun invalidate(vaultId: String) {
        cache.remove(vaultId)
    }

    fun invalidateAll() {
        cache.clear()
    }

    /** Applies legacy AppSettings pin keys into the vault index (one-time migration). */
    fun loadWithPinMigration(vault: VaultConfig, settings: AppSettings): BookshelfIndex {
        val index = load(vault)
        val prefix = "v:${vault.id}:"
        val legacyPins = settings.homePinnedCaptureKeys.filter { it.startsWith(prefix) }
        if (legacyPins.isEmpty()) return index

        val entries = index.entries.toMutableList()
        var changed = false
        val now = System.currentTimeMillis()
        for (pinKey in legacyPins) {
            val path = pinKey.removePrefix(prefix)
            val idx = entries.indexOfFirst { it.path == path }
            if (idx >= 0) {
                val entry = entries[idx]
                if (!entry.pinned) {
                    entries[idx] = entry.copy(pinned = true, pinnedAt = now)
                    changed = true
                }
            } else {
                entries.add(
                    BookshelfEntry(
                        path = path,
                        kind = BookshelfKind.NOTE,
                        pinned = true,
                        pinnedAt = now,
                        addedAt = now
                    )
                )
                changed = true
            }
        }
        if (!changed) return index
        val migrated = index.copy(entries = entries)
        save(vault, migrated)
        return migrated
    }

    fun addEntry(vault: VaultConfig, path: String, kind: BookshelfKind): BookshelfIndex {
        val index = load(vault)
        if (index.entries.any { it.path == path }) return index
        val now = System.currentTimeMillis()
        val updated = index.copy(
            entries = index.entries + BookshelfEntry(
                path = path,
                kind = kind,
                addedAt = now
            ),
            archivedPaths = index.archivedPaths - path
        )
        save(vault, updated)
        return updated
    }

    fun removeEntry(vault: VaultConfig, path: String): BookshelfIndex {
        val index = load(vault)
        val updated = index.copy(entries = index.entries.filter { it.path != path })
        if (updated.entries.size == index.entries.size) return index
        save(vault, updated)
        return updated
    }

    fun setPinned(vault: VaultConfig, path: String, pinned: Boolean): BookshelfIndex {
        val index = load(vault)
        val now = System.currentTimeMillis()
        val entries = index.entries.toMutableList()
        val idx = entries.indexOfFirst { it.path == path }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(
                pinned = pinned,
                pinnedAt = if (pinned) now else 0L
            )
        } else {
            entries.add(
                BookshelfEntry(
                    path = path,
                    kind = BookshelfKind.NOTE,
                    pinned = pinned,
                    pinnedAt = if (pinned) now else 0L,
                    addedAt = now
                )
            )
        }
        val updated = index.copy(entries = entries)
        save(vault, updated)
        return updated
    }

    fun renamePath(vault: VaultConfig, oldPath: String, newPath: String): BookshelfIndex {
        val index = load(vault)
        var changed = false
        val entries = index.entries.map { entry ->
            if (entry.path == oldPath) {
                changed = true
                entry.copy(path = newPath)
            } else {
                entry
            }
        }
        val archived = index.archivedPaths.map { path ->
            if (path == oldPath) {
                changed = true
                newPath
            } else {
                path
            }
        }.toSet()
        if (!changed) return index
        val updated = index.copy(entries = entries, archivedPaths = archived)
        save(vault, updated)
        return updated
    }

    fun archivePath(vault: VaultConfig, path: String): BookshelfIndex {
        val index = load(vault)
        val updated = index.copy(
            entries = index.entries.filter { it.path != path },
            archivedPaths = index.archivedPaths + path
        )
        save(vault, updated)
        return updated
    }

    /** Removes bookshelf entries and archive markers for [path] and its descendants. */
    fun removePathAndDescendants(vault: VaultConfig, path: String): BookshelfIndex {
        val index = load(vault)
        fun matches(candidate: String): Boolean =
            candidate == path || candidate.startsWith("$path/")
        val updated = index.copy(
            entries = index.entries.filter { !matches(it.path) },
            archivedPaths = index.archivedPaths.filter { !matches(it) }.toSet()
        )
        if (updated == index) return index
        save(vault, updated)
        return updated
    }

    fun pinnedAtForPath(index: BookshelfIndex, path: String): Long =
        index.entries.find { it.path == path && it.pinned }?.pinnedAt ?: 0L
}
