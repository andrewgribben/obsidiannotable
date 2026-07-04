package com.ethran.notable.io.flipside

import android.content.Context
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.InboxSyncEngine
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.VaultWriteQueue
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.vault.FLIP_SIDE_SUFFIX
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.flipSideFileFor
import com.ethran.notable.io.vault.vaultRootDir
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = ShipBook.getLogger("FlipSideManager")

/** Persistent link between a vault note, its flip-side file, and the drawing page in the DB. */
@Serializable
data class FlipSideLink(
    val vaultId: String,
    /** Relative path of the *text note* within the vault. */
    val relativePath: String,
    val pageId: String,
    /** Hash of the flip-side file when we last imported/exported it. */
    val fileHash: String = VaultFileStore.HASH_MISSING,
    /** [FlipSideManager.PURPOSE_FLIP] (persistent sketch, exported to .excalidraw.md)
     *  or [FlipSideManager.PURPOSE_INSERT] (scratch page for HWR text entry into the note). */
    val purpose: String = FlipSideManager.PURPOSE_FLIP
)

/**
 * The "flip side" of a vault note: an ink drawing surface stored as an
 * Excalidraw-compatible `.flip.excalidraw.md` file in the vault, linked to the note via
 * a `flip-side` frontmatter property. The drawing is edited on a regular editor page
 * (kept in a "Flip Sides" folder); the vault file is the source of truth — the page is
 * re-imported whenever the file changes externally (e.g. edited in Obsidian).
 */
object FlipSideManager {

    const val PURPOSE_FLIP = "flip"
    const val PURPOSE_INSERT = "insert"

    private const val FLIP_FOLDER_KEY = "FLIP_FOLDER_ID"
    private const val FRONTMATTER_KEY = "flip-side"

    enum class HwrApplyMode { REPLACE, APPEND }

    private fun noteKey(vaultId: String, relativePath: String) = "FLIP_PAGE:$vaultId:$relativePath"
    private fun pageKey(pageId: String) = "FLIP_NOTE:$pageId"

    /** Open editing sessions: pageId -> (NoteEditGuard key, guard owner token). */
    private val editSessions = ConcurrentHashMap<String, Pair<String, String>>()

    /** Acquires the single-writer guard for a note, snacking on refusal. */
    private fun acquireGuard(vaultId: String, relativePath: String, pageId: String): Boolean {
        val key = NoteEditGuard.noteKey(vaultId, relativePath)
        val owner = UUID.randomUUID().toString()
        if (!NoteEditGuard.tryAcquire(key, owner)) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    text = "Note is being edited in another window",
                    duration = 4000
                )
            )
            return false
        }
        editSessions[pageId] = key to owner
        return true
    }

    private fun releaseGuard(pageId: String) {
        editSessions.remove(pageId)?.let { (key, owner) -> NoteEditGuard.release(key, owner) }
    }

    /**
     * Finds or creates the drawing page for the flip side of [noteRelativePath] in the
     * active vault. Imports strokes from the flip-side file when it's new or changed
     * externally. Returns the page id to open in the editor, or null on failure.
     */
    suspend fun openFlipSide(appRepository: AppRepository, noteRelativePath: String): String? {
        val vault = GlobalAppSettings.current.activeVault ?: return null
        val root = vaultRootDir(vault) ?: return null
        val noteFile = File(root, noteRelativePath.replace('/', File.separatorChar))
        val flipFile = flipSideFileFor(noteFile)

        val existingLink = appRepository.kvProxy.get(
            noteKey(vault.id, noteRelativePath), FlipSideLink.serializer()
        )
        val existingPage = existingLink?.let { appRepository.pageRepository.getById(it.pageId) }
        val fileHash = VaultFileStore.currentHash(flipFile)

        if (existingLink != null && existingPage != null) {
            if (!acquireGuard(vault.id, noteRelativePath, existingLink.pageId)) return null
            if (fileHash != existingLink.fileHash && fileHash != VaultFileStore.HASH_MISSING) {
                reimportFromFile(appRepository, existingLink.pageId, flipFile)
                saveLink(appRepository, existingLink.copy(fileHash = fileHash))
            }
            return existingLink.pageId
        }

        // Create the drawing page in the "Flip Sides" folder
        val folderId = ensureFlipFolder(appRepository)
        val page = Page(
            notebookId = null,
            parentFolderId = folderId,
            background = "blank",
            backgroundType = BackgroundType.Native.key
        )
        if (!acquireGuard(vault.id, noteRelativePath, page.id)) return null
        try {
            appRepository.pageRepository.create(page)
        } catch (e: Exception) {
            log.e("Failed to create flip-side page: ${e.message}")
            releaseGuard(page.id)
            return null
        }

        if (flipFile.exists()) {
            reimportFromFile(appRepository, page.id, flipFile)
        }

        val link = FlipSideLink(
            vaultId = vault.id,
            relativePath = noteRelativePath,
            pageId = page.id,
            fileHash = fileHash
        )
        saveLink(appRepository, link)
        return page.id
    }

    /** True when [pageId] is a flip-side drawing page (of either purpose). */
    suspend fun isFlipPage(appRepository: AppRepository, pageId: String): Boolean =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer()) != null

    /** The link for [pageId], or null when it isn't a flip-side page. */
    suspend fun linkForPage(appRepository: AppRepository, pageId: String): FlipSideLink? =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())

    /**
     * Runs handwriting recognition over a flip-side page's strokes, using the same
     * markdown-aware pipeline as inbox capture. Returns null when there is nothing
     * to recognize or HWR is unavailable.
     */
    suspend fun recognizeFlipSide(
        appRepository: AppRepository,
        context: Context,
        pageId: String
    ): String? {
        val strokes = appRepository.pageRepository.getWithStrokeById(pageId).strokes
        if (strokes.isEmpty()) return null
        val text = InboxSyncEngine.recognizeStrokesToMarkdown(context, strokes)
        return text.ifBlank { null }
    }

    /**
     * Writes recognized flip-side [text] into the linked note. [HwrApplyMode.REPLACE]
     * keeps the note's frontmatter and replaces the body; [HwrApplyMode.APPEND] adds
     * the text at the end. Hash-checked — returns a user-facing status message.
     */
    suspend fun applyTextToNote(
        appRepository: AppRepository,
        pageId: String,
        text: String,
        mode: HwrApplyMode
    ): String {
        val link = linkForPage(appRepository, pageId) ?: return "Not a flip-side page"
        val vault = GlobalAppSettings.current.vaults.find { it.id == link.vaultId }
            ?: GlobalAppSettings.current.activeVault ?: return "Vault not found"
        val root = vaultRootDir(vault) ?: return "Vault root not found"
        val noteFile = File(root, link.relativePath.replace('/', File.separatorChar))

        val read = VaultFileStore.read(noteFile)
        val (newContent, expectedHash) = if (read == null) {
            text + "\n" to VaultFileStore.HASH_MISSING
        } else when (mode) {
            HwrApplyMode.APPEND ->
                (read.content.trimEnd('\n') + "\n\n" + text + "\n") to read.hash
            HwrApplyMode.REPLACE -> {
                val frontmatterEnd = frontmatterEndIndex(read.content)
                val head = read.content.substring(0, frontmatterEnd)
                (if (head.isBlank()) text + "\n"
                else head.trimEnd('\n') + "\n\n" + text + "\n") to read.hash
            }
        }

        return when (val result = VaultFileStore.write(noteFile, newContent, expectedHash)) {
            is VaultFileStore.WriteResult.Success -> "Saved to ${noteFile.name}"
            is VaultFileStore.WriteResult.Conflict -> {
                val copy = VaultFileStore.writeConflictCopy(noteFile, newContent)
                "Note changed elsewhere — saved as ${copy?.name ?: "conflict copy"}"
            }
            is VaultFileStore.WriteResult.Error -> "Save failed: ${result.message}"
        }
    }

    /**
     * Creates a fresh scratch drawing page for handwriting text into [noteRelativePath]
     * (Phase 4: handwritten entry into existing notes). The page is deleted after the
     * recognized text is saved or the entry is discarded.
     */
    suspend fun openInsertPage(appRepository: AppRepository, noteRelativePath: String): String? {
        val vault = GlobalAppSettings.current.activeVault ?: return null
        val folderId = ensureFlipFolder(appRepository)
        val page = Page(
            notebookId = null,
            parentFolderId = folderId,
            background = "blank",
            backgroundType = BackgroundType.Native.key
        )
        if (!acquireGuard(vault.id, noteRelativePath, page.id)) return null
        try {
            appRepository.pageRepository.create(page)
        } catch (e: Exception) {
            log.e("Failed to create insert page: ${e.message}")
            releaseGuard(page.id)
            return null
        }
        val link = FlipSideLink(
            vaultId = vault.id,
            relativePath = noteRelativePath,
            pageId = page.id,
            purpose = PURPOSE_INSERT
        )
        appRepository.kvProxy.setKv(pageKey(page.id), link, FlipSideLink.serializer())
        return page.id
    }

    /**
     * Finishes a handwritten-entry page: recognizes the ink, appends it to the note,
     * and deletes the scratch page on success. Returns a user-facing status message.
     */
    suspend fun completeInsertPage(
        appRepository: AppRepository,
        context: Context,
        pageId: String
    ): String {
        val text = recognizeFlipSide(appRepository, context, pageId)
            ?: return "Nothing recognized — page kept"
        val message = applyTextToNote(appRepository, pageId, text, HwrApplyMode.APPEND)
        if (message.startsWith("Saved") || message.startsWith("Note changed")) {
            discardInsertPage(appRepository, pageId)
        }
        return message
    }

    /** Deletes a handwritten-entry scratch page and its link. */
    suspend fun discardInsertPage(appRepository: AppRepository, pageId: String) {
        appRepository.kvProxy.delete(pageKey(pageId))
        try {
            appRepository.pageRepository.delete(pageId)
        } catch (e: Exception) {
            log.w("Failed to delete insert page $pageId: ${e.message}")
        }
        releaseGuard(pageId)
    }

    /** End index (exclusive) of the YAML frontmatter block, or 0 when there is none. */
    fun frontmatterEndIndex(content: String): Int {
        if (!content.startsWith("---")) return 0
        val end = content.indexOf("\n---", 3)
        if (end < 0) return 0
        val afterMarker = end + "\n---".length
        // Include the trailing newline after the closing marker
        return if (afterMarker < content.length && content[afterMarker] == '\n') afterMarker + 1
        else afterMarker
    }

    /**
     * Schedules a background save of a flip-side page to its vault file (no-op for
     * regular pages). Called when the editor closes; runs on the vault write queue so
     * navigation never blocks.
     */
    fun scheduleSaveIfFlipPage(appRepository: AppRepository, pageId: String) {
        val settings = GlobalAppSettings.current
        // Cheap pre-check needs a file for the queue; resolve link first inside the queue
        // is impossible (queue is keyed by file), so resolve link synchronously via a
        // lightweight queue keyed by a placeholder when needed.
        VaultWriteQueue.enqueue(File("flipside-$pageId"), "flip-side save") {
            try {
                val link = appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())
                    ?: return@enqueue
                // Insert-purpose pages are scratch surfaces for HWR text entry — no sidecar export.
                if (link.purpose != PURPOSE_FLIP) return@enqueue
                val vault = settings.vaults.find { it.id == link.vaultId }
                    ?: GlobalAppSettings.current.activeVault ?: return@enqueue
                saveFlipSide(appRepository, link, vault)
            } finally {
                // Editor closed: this window is done editing the note.
                releaseGuard(pageId)
            }
        }
    }

    private suspend fun saveFlipSide(
        appRepository: AppRepository,
        link: FlipSideLink,
        vault: VaultConfig
    ) {
        val root = vaultRootDir(vault) ?: return
        val noteFile = File(root, link.relativePath.replace('/', File.separatorChar))
        val flipFile = flipSideFileFor(noteFile)

        val strokes = appRepository.pageRepository.getWithStrokeById(link.pageId).strokes
        if (strokes.isEmpty() && !flipFile.exists()) return

        val content = ExcalidrawSerializer.serialize(strokes)
        val expected = link.fileHash
        when (val result = VaultFileStore.write(flipFile, content, expectedHash = expected)) {
            is VaultFileStore.WriteResult.Success -> {
                saveLink(appRepository, link.copy(fileHash = VaultFileStore.hashOf(content)))
                ensureFrontmatterLink(noteFile, flipFile)
                log.i("Flip side saved: ${flipFile.name} (${strokes.size} strokes)")
            }
            is VaultFileStore.WriteResult.Conflict -> {
                val copy = VaultFileStore.writeConflictCopy(flipFile, content)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Flip side changed elsewhere — saved as ${copy?.name ?: "conflict copy"}",
                        duration = 6000
                    )
                )
                log.w("Flip side conflict for ${flipFile.name}; conflict copy: ${copy?.name}")
            }
            is VaultFileStore.WriteResult.Error -> {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Flip side save failed: ${result.message}", duration = 5000)
                )
            }
        }
    }

    /** Replaces the page's strokes with the flip-side file contents. */
    private suspend fun reimportFromFile(
        appRepository: AppRepository,
        pageId: String,
        flipFile: File
    ) {
        val content = VaultFileStore.read(flipFile)?.content ?: return
        val imported = ExcalidrawSerializer.parse(content, pageId) ?: return
        val existing = appRepository.pageRepository.getWithStrokeById(pageId).strokes
        if (existing.isNotEmpty()) {
            appRepository.strokeRepository.deleteAll(existing.map { it.id })
        }
        if (imported.isNotEmpty()) {
            appRepository.strokeRepository.create(imported)
        }
        log.i("Re-imported ${imported.size} strokes from ${flipFile.name}")
    }

    private suspend fun saveLink(appRepository: AppRepository, link: FlipSideLink) {
        appRepository.kvProxy.setKv(
            noteKey(link.vaultId, link.relativePath), link, FlipSideLink.serializer()
        )
        appRepository.kvProxy.setKv(pageKey(link.pageId), link, FlipSideLink.serializer())
    }

    private suspend fun ensureFlipFolder(appRepository: AppRepository): String {
        val existingId = appRepository.kvProxy.get(FLIP_FOLDER_KEY, String.serializer())
        if (existingId != null && appRepository.folderRepository.get(existingId) != null) {
            return existingId
        }
        val folder = Folder(title = "Flip Sides")
        appRepository.folderRepository.create(folder)
        appRepository.kvProxy.setKv(FLIP_FOLDER_KEY, folder.id, String.serializer())
        return folder.id
    }

    /**
     * Adds (or leaves in place) the `flip-side` frontmatter property on the text note,
     * linking it to the flip-side file. Skips silently on conflict — retried on the
     * next save.
     */
    private fun ensureFrontmatterLink(noteFile: File, flipFile: File) {
        if (!noteFile.exists()) return
        val read = VaultFileStore.read(noteFile) ?: return
        val target = flipFile.name.removeSuffix(".md")
        val property = "$FRONTMATTER_KEY: \"[[$target]]\""
        if (read.content.contains(property)) return
        if (Regex("^$FRONTMATTER_KEY:", RegexOption.MULTILINE).containsMatchIn(read.content)) return

        val updated = addFrontmatterProperty(read.content, property)
        val result = VaultFileStore.write(noteFile, updated, expectedHash = read.hash)
        if (result !is VaultFileStore.WriteResult.Success) {
            log.w("Could not add flip-side frontmatter to ${noteFile.name}: $result")
        }
    }

    /** Inserts [propertyLine] into the note's YAML frontmatter, creating the block if absent. */
    fun addFrontmatterProperty(content: String, propertyLine: String): String {
        if (content.startsWith("---")) {
            val end = content.indexOf("\n---", 3)
            if (end >= 0) {
                return content.substring(0, end) + "\n" + propertyLine + content.substring(end)
            }
        }
        return "---\n$propertyLine\n---\n\n$content"
    }
}

/** Note name (for display) of a flip-side page's linked note. */
fun flipSideNoteName(relativePath: String): String =
    relativePath.substringAfterLast('/').removeSuffix(".md").removeSuffix(FLIP_SIDE_SUFFIX)
