package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.resolveExternalStoragePath
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("VaultIndex")

/** Suffix for flip-side drawing files (Excalidraw-compatible markdown). */
const val FLIP_SIDE_SUFFIX = ".flip.excalidraw.md"

/** A markdown note inside a vault. */
data class VaultNote(
    val file: File,
    /** Path relative to the vault root, using '/' separators, including ".md". */
    val relativePath: String,
    /** File name without the ".md" extension. */
    val name: String,
    /** True when the note has a flip-side drawing file next to it. */
    val hasInk: Boolean,
    val lastModified: Long,
    val isFolder: Boolean = false
)

/** The vault root is the parent of the inbox folder. */
fun vaultRootDir(vault: VaultConfig): File? {
    if (vault.inboxPath.isBlank()) return null
    return resolveExternalStoragePath(vault.inboxPath).parentFile
}

/**
 * Index of the markdown notes of a single vault. Scans the filesystem directly (the app
 * holds all-files access) and provides Obsidian-style wikilink resolution by file name.
 */
class VaultIndex(val vaultRoot: File) {

    @Volatile
    private var notes: List<VaultNote> = emptyList()

    /** All indexed notes (flip-side files excluded), most recently modified first. */
    fun allNotes(): List<VaultNote> = notes

    fun refresh(): List<VaultNote> {
        val found = mutableListOf<VaultNote>()
        scanDir(vaultRoot, found)
        found.sortByDescending { it.lastModified }
        notes = found
        log.i("Indexed ${found.size} notes under ${vaultRoot.absolutePath}")
        return found
    }

    private fun scanDir(dir: File, out: MutableList<VaultNote>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue // .obsidian, .singularity, .trash, hidden
            if (child.isDirectory) {
                scanDir(child, out)
            } else if (name.endsWith(".md", ignoreCase = true) &&
                !name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true)
            ) {
                out.add(toNote(child))
            }
        }
    }

    private fun toNote(file: File): VaultNote {
        val relative = file.absolutePath
            .removePrefix(vaultRoot.absolutePath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        return VaultNote(
            file = file,
            relativePath = relative,
            name = file.name.removeSuffix(".md"),
            hasInk = flipSideFileFor(file).exists(),
            lastModified = file.lastModified()
        )
    }

    /** Direct children (folders + notes) of [relativeDir] for the tree browser. */
    fun listDir(relativeDir: String): List<VaultNote> {
        val dir = if (relativeDir.isBlank()) vaultRoot
        else File(vaultRoot, relativeDir.replace('/', File.separatorChar))
        val children = dir.listFiles() ?: return emptyList()
        val folders = mutableListOf<VaultNote>()
        val files = mutableListOf<VaultNote>()
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                folders.add(
                    VaultNote(
                        file = child,
                        relativePath = (if (relativeDir.isBlank()) name else "$relativeDir/$name"),
                        name = name,
                        hasInk = false,
                        lastModified = child.lastModified(),
                        isFolder = true
                    )
                )
            } else if (name.endsWith(".md", ignoreCase = true) &&
                !name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true)
            ) {
                files.add(toNote(child))
            }
        }
        folders.sortBy { it.name.lowercase() }
        files.sortBy { it.name.lowercase() }
        return folders + files
    }

    /**
     * Resolves a wikilink target to a note file, Obsidian-style:
     * strips `#heading` / `^block` suffixes, then matches by exact relative path,
     * then by unique file name anywhere in the vault (shortest path wins on ties).
     */
    fun resolveWikilink(target: String): VaultNote? {
        val cleaned = target.substringBefore('#').substringBefore('^').trim()
        if (cleaned.isEmpty()) return null
        val withExt = if (cleaned.endsWith(".md", true)) cleaned else "$cleaned.md"

        if (notes.isEmpty()) refresh()

        // Exact relative-path match
        notes.firstOrNull { it.relativePath.equals(withExt, ignoreCase = true) }
            ?.let { return it }

        // Name match anywhere in the vault
        val nameOnly = withExt.substringAfterLast('/')
        val matches = notes.filter { it.relativePath.substringAfterLast('/').equals(nameOnly, ignoreCase = true) }
        return matches.minByOrNull { it.relativePath.count { c -> c == '/' } }
    }

    /** Fuzzy filename search for the quick switcher: subsequence match, ranked. */
    fun search(query: String, limit: Int = 30): List<VaultNote> {
        if (notes.isEmpty()) refresh()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return notes.take(limit)
        return notes.mapNotNull { note ->
            val score = fuzzyScore(q, note.name.lowercase())
                ?: fuzzyScore(q, note.relativePath.lowercase())?.minus(1000)
            if (score == null) null else note to score
        }.sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    companion object {
        /**
         * Subsequence fuzzy score: null when [query] is not a subsequence of [candidate].
         * Higher is better; rewards contiguous runs and matches near the start.
         */
        fun fuzzyScore(query: String, candidate: String): Int? {
            var qi = 0
            var score = 0
            var streak = 0
            for ((ci, c) in candidate.withIndex()) {
                if (qi < query.length && c == query[qi]) {
                    qi++
                    streak++
                    score += 10 + streak * 5 - ci / 4
                } else {
                    streak = 0
                }
            }
            if (qi < query.length) return null
            if (candidate.contains(query)) score += 200
            if (candidate.startsWith(query)) score += 300
            return score
        }
    }
}

/** The flip-side file that pairs with [noteFile] (same folder, same base name). */
fun flipSideFileFor(noteFile: File): File {
    val base = noteFile.name.removeSuffix(".md")
    return File(noteFile.parentFile, "$base$FLIP_SIDE_SUFFIX")
}

/** The text note that pairs with a flip-side [flipFile]. */
fun noteFileForFlipSide(flipFile: File): File {
    val base = flipFile.name.removeSuffix(FLIP_SIDE_SUFFIX)
    return File(flipFile.parentFile, "$base.md")
}

/** Global registry of per-vault indexes, keyed by vault id. */
object VaultIndexRegistry {
    private val indexes = mutableMapOf<String, VaultIndex>()

    @Synchronized
    fun forVault(vault: VaultConfig): VaultIndex? {
        val root = vaultRootDir(vault) ?: return null
        val existing = indexes[vault.id]
        if (existing != null && existing.vaultRoot == root) return existing
        val index = VaultIndex(root)
        indexes[vault.id] = index
        return index
    }
}
