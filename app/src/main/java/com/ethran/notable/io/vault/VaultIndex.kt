package com.ethran.notable.io.vault

import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.resolveExternalStoragePath
import com.ethran.notable.io.resolveVaultAttachmentDir
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("VaultIndex")

/** Suffix for flip-side drawing files (Excalidraw-compatible markdown). */
const val FLIP_SIDE_SUFFIX = ".flip.excalidraw.md"
const val DRAWING_EXTENSION = ".excalidraw.md"

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

/** Obsidian Sync vault root: the inbox folder (matches desktop Obsidian vault root). */
fun obsidianSyncVaultRoot(vault: VaultConfig): File? {
    if (vault.inboxPath.isBlank()) return null
    return resolveExternalStoragePath(vault.inboxPath)
}

/** Inbox folder path relative to the vault root (forward slashes), or null when unconfigured. */
fun inboxDirRelativeToVault(vault: VaultConfig): String? {
    val root = vaultRootDir(vault) ?: return null
    if (vault.inboxPath.isBlank()) return null
    val inboxDir = resolveExternalStoragePath(vault.inboxPath)
    return inboxDir.relativeTo(root).path.replace('\\', '/')
}

/** Vault-root-relative path → Obsidian Sync path (relative to inbox / sync root). */
fun vaultRelativeToSyncPath(vault: VaultConfig, vaultRelativePath: String): String {
    val prefix = inboxDirRelativeToVault(vault) ?: return vaultRelativePath
    if (vaultRelativePath == prefix) return ""
    if (vaultRelativePath.startsWith("$prefix/")) {
        return vaultRelativePath.removePrefix("$prefix/")
    }
    return vaultRelativePath
}

/** Obsidian Sync path → vault-root-relative path for UI and [VaultIndex]. */
fun syncPathToVaultRelative(vault: VaultConfig, syncRelativePath: String): String {
    val prefix = inboxDirRelativeToVault(vault) ?: return syncRelativePath
    if (syncRelativePath.isEmpty()) return prefix
    return "$prefix/$syncRelativePath"
}

/**
 * Resolves a note file from a vault-root-relative path.
 * Checks the canonical vault-root location first, then the sync-root layout.
 */
fun resolveVaultNoteFile(vault: VaultConfig, vaultRelativePath: String): File? {
    val root = vaultRootDir(vault) ?: return null
    val normalized = vaultRelativePath.replace('/', File.separatorChar)
    val direct = File(root, normalized)
    if (direct.exists()) return direct
    val syncRoot = obsidianSyncVaultRoot(vault) ?: return direct
    val syncPath = vaultRelativeToSyncPath(vault, vaultRelativePath)
    val viaSync = File(syncRoot, syncPath.replace('/', File.separatorChar))
    if (viaSync.exists()) return viaSync
    return direct
}

/** Notes under the vault inbox that use unified Excalidraw format with a drawing block. */
fun listInboxNotesWithInk(vault: VaultConfig): List<VaultNote> {
    if (vault.inboxPath.isBlank()) return emptyList()
    val inboxDir = resolveExternalStoragePath(vault.inboxPath)
    if (!inboxDir.isDirectory) return emptyList()
    val root = vaultRootDir(vault) ?: return emptyList()

    return inboxDir.listFiles().orEmpty()
        .asSequence()
        .filter { file ->
            file.isFile &&
                file.name.endsWith(".md", ignoreCase = true) &&
                !file.name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true) &&
                !file.name.endsWith(DRAWING_EXTENSION, ignoreCase = true)
        }
        .mapNotNull { file -> inboxCaptureNote(file, root, inboxDir) }
        .sortedByDescending { it.lastModified }
        .toList()
}

private const val INBOX_HEAD_SCAN_BYTES = 8 * 1024

private fun readFileHead(file: File, maxBytes: Int = INBOX_HEAD_SCAN_BYTES): String? {
    return try {
        file.inputStream().use { input ->
            val buf = ByteArray(maxBytes)
            val read = input.read(buf)
            if (read <= 0) return null
            String(buf, 0, read, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        log.e("Failed to read head of ${file.absolutePath}: ${e.message}")
        null
    }
}

private fun inboxCaptureNote(file: File, vaultRoot: File, linkRoot: File): VaultNote? {
    val head = readFileHead(file) ?: return null
    val drawing = resolveAssociatedDrawingFile(file, vaultRoot, head, linkRoot)
    val legacyUnified = ExcalidrawSerializer.isExcalidrawNote(head) &&
        ExcalidrawSerializer.hasDrawingSectionForListing(file)
    if (drawing == null && !legacyUnified) return null
    val relative = file.relativeTo(vaultRoot).path.replace('\\', '/')
    return VaultNote(
        file = file,
        relativePath = relative,
        name = file.name.removeSuffix(".md"),
        hasInk = drawing != null || ExcalidrawSerializer.hasNonemptyInkForListing(file),
        lastModified = file.lastModified()
    )
}

private fun hasInkInNoteContent(content: String): Boolean =
    ExcalidrawSerializer.hasNonemptyInk(content)

/** Inbox captures with ink across [vaults]. */
fun listInboxNotesWithInkForVaults(
    vaults: List<VaultConfig>
): List<Pair<VaultConfig, VaultNote>> = buildList {
    for (vault in vaults) {
        for (note in listInboxNotesWithInk(vault)) {
            add(vault to note)
        }
    }
}

/**
 * Index of the markdown notes of a single vault. Scans the filesystem directly (the app
 * holds all-files access) and provides Obsidian-style wikilink resolution by file name.
 */
class VaultIndex(val vaultRoot: File, private val linkRoot: File = vaultRoot) {

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
                !name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true) &&
                !name.endsWith(DRAWING_EXTENSION, ignoreCase = true)
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
            hasInk = hasInkInFile(file),
            lastModified = file.lastModified()
        )
    }

    private fun hasInkInFile(file: File): Boolean {
        val drawing = resolveAssociatedDrawingFile(file, vaultRoot, linkRoot = linkRoot)
        return drawing != null || ExcalidrawSerializer.hasNonemptyInkForListing(file)
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
                !name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true) &&
                !name.endsWith(DRAWING_EXTENSION, ignoreCase = true)
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

/** Resolves the drawing linked by `singularity-drawing`, including legacy raw files. */
fun resolveAssociatedDrawingFile(
    noteFile: File,
    vaultRoot: File,
    content: String? = null,
    linkRoot: File = vaultRoot
): File? {
    if (!noteFile.isFile) return null
    val noteContent = content ?: runCatching { noteFile.readText() }.getOrNull() ?: return null
    val target = ExcalidrawSerializer.drawingLinkPath(noteContent) ?: return null
    val normalized = target.replace('/', File.separatorChar)
    fun safeChild(root: File, relative: String): File? {
        val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path == canonicalRoot.path || it.path.startsWith("${canonicalRoot.path}${File.separator}")
        }
    }
    val exact = safeChild(linkRoot, normalized) ?: return null
    if (exact.isFile) return exact
    if (!target.endsWith(DRAWING_EXTENSION, ignoreCase = true)) {
        val withExtension = safeChild(linkRoot, "$normalized$DRAWING_EXTENSION") ?: return null
        if (withExtension.isFile) return withExtension
    }
    val legacyRootRelative = safeChild(vaultRoot, normalized) ?: return null
    if (legacyRootRelative.isFile) return legacyRootRelative
    return null
}

/** Chooses a non-conflicting attachment path for a note's modern drawing file. */
fun availableDrawingFile(noteFile: File, vault: VaultConfig): File? {
    val attachmentDir = resolveVaultAttachmentDir(vault.inboxPath, vault.attachmentPath) ?: return null
    attachmentDir.mkdirs()
    val base = noteFile.name.removeSuffix(".md")
    var candidate = File(attachmentDir, "$base$DRAWING_EXTENSION")
    var suffix = 2
    while (candidate.exists()) {
        candidate = File(attachmentDir, "$base-$suffix$DRAWING_EXTENSION")
        suffix++
    }
    return candidate
}

private val FLIP_SIDE_FRONTMATTER_REGEX =
    Regex("""^flip-side:\s*["']?\[\[([^\]]+)]]""", RegexOption.MULTILINE)

/**
 * Resolves the Excalidraw file for a note's flip side. Uses the `flip-side` frontmatter
 * wikilink when present (Obsidian may point at a different path than the default sidecar),
 * otherwise [flipSideFileFor].
 */
fun resolveFlipSideFile(noteFile: File, vaultRoot: File): File {
    val default = flipSideFileFor(noteFile)
    if (!noteFile.isFile) return default
    val content = runCatching { noteFile.readText() }.getOrNull() ?: return default
    val target = FLIP_SIDE_FRONTMATTER_REGEX.find(content)?.groupValues?.get(1)?.trim()
        ?: return default

    val noteDir = noteFile.parentFile?.let { parent ->
        parent.relativeTo(vaultRoot).path.replace('\\', '/').trimStart('/')
    }.orEmpty()

    val withMd = if (target.endsWith(".md", ignoreCase = true)) target else "$target.md"
    val candidates = buildList {
        if (noteDir.isNotEmpty()) {
            add(File(vaultRoot, "$noteDir/$withMd"))
            add(File(vaultRoot, "$noteDir/$target"))
        }
        add(File(vaultRoot, withMd.replace('/', File.separatorChar)))
        add(File(vaultRoot, target.replace('/', File.separatorChar)))
        add(default)
    }
    return candidates.firstOrNull { it.isFile } ?: default
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
        val index = VaultIndex(root, obsidianSyncVaultRoot(vault) ?: root)
        indexes[vault.id] = index
        return index
    }

    @Synchronized
    fun invalidateAll() {
        indexes.clear()
    }
}
