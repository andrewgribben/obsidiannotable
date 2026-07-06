package com.ethran.notable.io.flipside

import android.content.Context
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.InboxSyncEngine
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.VaultWriteQueue
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.excalidraw.ExcalidrawUnifiedTemplate
import com.ethran.notable.io.resolveExternalStoragePath
import com.ethran.notable.io.vault.FLIP_SIDE_SUFFIX
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.vaultRootDir
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    /**
     * Fingerprint of page strokes when handwriting was last synced to the text note
     * (or when the flip side was opened / re-imported). Used to decide whether to run
     * HWR before flipping to the text note.
     */
    val hwrStrokeBaseline: String = "",
    /** [FlipSideManager.PURPOSE_FLIP] (persistent sketch, exported to .excalidraw.md)
     *  or [FlipSideManager.PURPOSE_INSERT] (scratch page for HWR text entry into the note). */
    val purpose: String = FlipSideManager.PURPOSE_FLIP
)

/**
 * The "flip side" of a vault note: ink stored in the same `.md` file using Obsidian's
 * unified Excalidraw format (frontmatter + markdown text + compressed-json drawing).
 * The drawing is edited on a regular editor page (kept in a "Flip Sides" folder); the
 * vault file is the source of truth — the page is re-imported whenever the file changes
 * externally (e.g. edited in Obsidian).
 */
object FlipSideManager {

    const val PURPOSE_FLIP = "flip"
    const val PURPOSE_INSERT = "insert"

    private const val FLIP_FOLDER_KEY = "FLIP_FOLDER_ID"
    private val CAPTURE_TIMESTAMP_FORMAT =
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
    private val CAPTURE_CREATED_DATE_FORMAT =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)

    enum class HwrApplyMode { REPLACE, APPEND }

    private fun noteKey(vaultId: String, relativePath: String) = "FLIP_PAGE:$vaultId:$relativePath"
    private fun pageKey(pageId: String) = "FLIP_NOTE:$pageId"

    /** Open editing sessions: pageId -> (NoteEditGuard key, guard owner token). */
    private val editSessions = ConcurrentHashMap<String, Pair<String, String>>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun vaultById(vaultId: String): VaultConfig? =
        GlobalAppSettings.current.normalizedVaults().vaults.find { it.id == vaultId }

    /**
     * Finds or creates the drawing page for the flip side of [noteRelativePath] in the
     * active vault.
     */
    suspend fun openFlipSide(appRepository: AppRepository, noteRelativePath: String): String? {
        val vault = GlobalAppSettings.current.activeVault ?: return null
        return openFlipSide(appRepository, vault.id, noteRelativePath)
    }

    /**
     * Finds or creates the drawing page for the flip side of [noteRelativePath] in
     * [vaultId]. Does not change the active vault.
     */
    suspend fun openFlipSide(
        appRepository: AppRepository,
        vaultId: String,
        noteRelativePath: String
    ): String? {
        val vault = vaultById(vaultId) ?: return null
        val root = vaultRootDir(vault) ?: return null
        val noteFile = File(root, noteRelativePath.replace('/', File.separatorChar))

        val existingLink = appRepository.kvProxy.get(
            noteKey(vault.id, noteRelativePath), FlipSideLink.serializer()
        )
        val existingPage = existingLink?.let { appRepository.pageRepository.getById(it.pageId) }
        val fileHash = VaultFileStore.currentHash(noteFile)

        if (existingLink != null && existingPage != null) {
            if (!acquireGuard(vault.id, noteRelativePath, existingLink.pageId)) return null
            if (fileHash != existingLink.fileHash && fileHash != VaultFileStore.HASH_MISSING) {
                reimportFromFile(appRepository, existingLink.pageId, noteFile)
                saveLink(appRepository, existingLink.copy(fileHash = fileHash))
            }
            ensureHwrStrokeBaseline(appRepository, existingLink.pageId)
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

        if (noteFile.exists()) {
            reimportFromFile(appRepository, page.id, noteFile)
        }

        val link = FlipSideLink(
            vaultId = vault.id,
            relativePath = noteRelativePath,
            pageId = page.id,
            fileHash = fileHash
        )
        saveLink(appRepository, link)
        updateHwrStrokeBaseline(appRepository, page.id)
        return page.id
    }

    /**
     * Creates a new timestamped vault note in the active vault's inbox and opens its flip-side editor.
     */
    suspend fun createNewCapture(appRepository: AppRepository): String? {
        val vault = GlobalAppSettings.current.activeVault
            ?: return missingVaultSnack()
        return createNewCapture(appRepository, vault.id)
    }

    /**
     * Creates a new timestamped vault note in [vaultId]'s inbox and opens its flip-side editor.
     */
    suspend fun createNewCapture(appRepository: AppRepository, vaultId: String): String? {
        val vault = vaultById(vaultId) ?: return missingVaultSnack()
        if (vault.inboxPath.isBlank()) {
            return missingVaultSnack()
        }
        val root = vaultRootDir(vault) ?: return null
        val inboxDir = resolveExternalStoragePath(vault.inboxPath)
        inboxDir.mkdirs()

        val createdAt = Date()
        val timestamp = CAPTURE_TIMESTAMP_FORMAT.format(createdAt)
        val noteFile = File(inboxDir, "$timestamp.md")
        if (noteFile.exists()) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Capture file already exists: $timestamp", duration = 4000)
            )
            return null
        }

        val markdown = buildCaptureUnifiedStub(createdAt)
        when (val result = VaultFileStore.write(noteFile, markdown)) {
            is VaultFileStore.WriteResult.Success -> { /* ok */ }
            else -> {
                log.e("Failed to create capture note ${noteFile.name}: $result")
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Could not create note in vault", duration = 4000)
                )
                return null
            }
        }

        val relativePath = noteFile.relativeTo(root).path.replace('\\', '/')
        return openFlipSide(appRepository, vaultId, relativePath)
    }

    private fun missingVaultSnack(): String? {
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(text = "Configure a vault and inbox in Settings", duration = 4000)
        )
        return null
    }

    /** Builds a new unified inbox capture stub matching [Template.excalidraw.md]. */
    fun buildCaptureUnifiedStub(createdAt: Date): String {
        val createdDate = CAPTURE_CREATED_DATE_FORMAT.format(createdAt)
        return ExcalidrawUnifiedTemplate.buildNewCapture(createdDate, emptyList())
    }

    /** Relative path of a vault note within [vault], or null when unconfigured. */
    fun noteRelativePath(noteFile: File, vault: VaultConfig): String? {
        val root = vaultRootDir(vault) ?: return null
        return noteFile.relativeTo(root).path.replace('\\', '/')
    }

    /** KV lookup: flip-side page id for a vault note path, if linked. */
    suspend fun pageIdForNote(
        appRepository: AppRepository,
        vaultId: String,
        relativePath: String
    ): String? = appRepository.kvProxy.get(
        noteKey(vaultId, relativePath), FlipSideLink.serializer()
    )?.pageId

    /** True when [pageId] is a flip-side drawing page (of either purpose). */
    suspend fun isFlipPage(appRepository: AppRepository, pageId: String): Boolean =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer()) != null

    /** The link for [pageId], or null when it isn't a flip-side page. */
    suspend fun linkForPage(appRepository: AppRepository, pageId: String): FlipSideLink? =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())

    /** Re-imports the unified note when it changed on disk (e.g. edited in Obsidian). */
    suspend fun syncFlipSideFromVaultIfChanged(appRepository: AppRepository, pageId: String) {
        val link = linkForPage(appRepository, pageId) ?: return
        if (link.purpose != PURPOSE_FLIP) return
        val vault = GlobalAppSettings.current.vaults.find { it.id == link.vaultId }
            ?: GlobalAppSettings.current.activeVault ?: return
        val root = vaultRootDir(vault) ?: return
        val noteFile = File(root, link.relativePath.replace('/', File.separatorChar))
        val fileHash = VaultFileStore.currentHash(noteFile)
        if (fileHash == link.fileHash || fileHash == VaultFileStore.HASH_MISSING) return
        reimportFromFile(appRepository, pageId, noteFile)
        saveLink(appRepository, link.copy(fileHash = fileHash))
        updateHwrStrokeBaseline(appRepository, pageId)
    }

    /** Stable fingerprint of a flip-side page's strokes for HWR change detection. */
    internal fun strokesFingerprint(strokes: List<Stroke>): String {
        if (strokes.isEmpty()) return "empty"
        return strokes
            .sortedBy { it.id }
            .joinToString("|") { stroke ->
                "${stroke.id}:${stroke.points.size}:${stroke.top}:${stroke.bottom}:${stroke.left}:${stroke.right}"
            }
    }

    private suspend fun strokesFingerprint(
        appRepository: AppRepository,
        pageId: String
    ): String = strokesFingerprint(
        appRepository.pageRepository.getWithStrokeById(pageId).strokes
    )

    /** True when ink on the flip side changed since the last HWR sync or open/re-import. */
    suspend fun hasFlipSideDrawingChanged(
        appRepository: AppRepository,
        pageId: String
    ): Boolean {
        val link = linkForPage(appRepository, pageId) ?: return false
        return strokesFingerprint(appRepository, pageId) != link.hwrStrokeBaseline
    }

    /** Records the current strokes as the HWR baseline (after apply, open, or re-import). */
    suspend fun updateHwrStrokeBaseline(appRepository: AppRepository, pageId: String) {
        val link = linkForPage(appRepository, pageId) ?: return
        val fingerprint = strokesFingerprint(appRepository, pageId)
        if (link.hwrStrokeBaseline == fingerprint) return
        saveLink(appRepository, link.copy(hwrStrokeBaseline = fingerprint))
    }

    /**
     * One-time migration: existing flip links without a baseline get one on first open
     * so users are not prompted to recognise unchanged drawings.
     */
    suspend fun ensureHwrStrokeBaseline(appRepository: AppRepository, pageId: String) {
        val link = linkForPage(appRepository, pageId) ?: return
        if (link.hwrStrokeBaseline.isNotEmpty()) return
        updateHwrStrokeBaseline(appRepository, pageId)
    }

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
        val pageStrokes = appRepository.pageRepository.getWithStrokeById(pageId).strokes

        return VaultFileStore.withFileLock(noteFile) {
            val read = VaultFileStore.read(noteFile)
            val (newContent, expectedHash) = if (read == null) {
                ExcalidrawSerializer.serializeUnified(text.trim(), pageStrokes) to
                    VaultFileStore.HASH_MISSING
            } else {
                val existingBody = ExcalidrawSerializer.extractMarkdownBody(read.content)
                val newBody = when (mode) {
                    HwrApplyMode.APPEND ->
                        (existingBody + "\n\n" + text).trim()
                    HwrApplyMode.REPLACE -> text.trim()
                }
                ExcalidrawSerializer.rewriteUnified(read.content, newBody, pageStrokes) to read.hash
            }

            when (val result = VaultFileStore.write(noteFile, newContent, expectedHash)) {
                is VaultFileStore.WriteResult.Success -> {
                    saveLink(appRepository, link.copy(fileHash = VaultFileStore.hashOf(newContent)))
                    updateHwrStrokeBaseline(appRepository, pageId)
                    "Saved to ${noteFile.name}"
                }
                is VaultFileStore.WriteResult.Conflict -> {
                    val copy = VaultFileStore.writeConflictCopy(noteFile, newContent)
                    "Note changed elsewhere — saved as ${copy?.name ?: "conflict copy"}"
                }
                is VaultFileStore.WriteResult.Error -> "Save failed: ${result.message}"
            }
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
        ioScope.launch {
            val link = appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())
            if (link == null || link.purpose != PURPOSE_FLIP) {
                releaseGuard(pageId)
                return@launch
            }
            val vault = settings.vaults.find { it.id == link.vaultId }
                ?: GlobalAppSettings.current.activeVault
            if (vault == null) {
                releaseGuard(pageId)
                return@launch
            }
            val root = vaultRootDir(vault)
            if (root == null) {
                releaseGuard(pageId)
                return@launch
            }
            val noteFile = File(root, link.relativePath.replace('/', File.separatorChar))
            VaultWriteQueue.enqueue(noteFile, "flip-side save") {
                try {
                    saveFlipSide(appRepository, link, vault)
                } finally {
                    releaseGuard(pageId)
                }
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

        val strokes = appRepository.pageRepository.getWithStrokeById(link.pageId).strokes
        val read = VaultFileStore.read(noteFile)
        val existing = read?.content.orEmpty()
        if (strokes.isEmpty() && !ExcalidrawSerializer.hasEmbeddedDrawing(existing)) return

        val content = if (existing.isBlank()) {
            ExcalidrawSerializer.serializeUnified("", strokes)
        } else {
            ExcalidrawSerializer.replaceDrawingInUnified(existing, strokes)
        }
        val expected = link.fileHash
        when (val result = VaultFileStore.write(noteFile, content, expectedHash = expected)) {
            is VaultFileStore.WriteResult.Success -> {
                saveLink(appRepository, link.copy(fileHash = VaultFileStore.hashOf(content)))
                log.i("Flip side saved: ${noteFile.name} (${strokes.size} strokes)")
            }
            is VaultFileStore.WriteResult.Conflict -> {
                val copy = VaultFileStore.writeConflictCopy(noteFile, content)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Note changed elsewhere — saved as ${copy?.name ?: "conflict copy"}",
                        duration = 6000
                    )
                )
                log.w("Flip side conflict for ${noteFile.name}; conflict copy: ${copy?.name}")
            }
            is VaultFileStore.WriteResult.Error -> {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Flip side save failed: ${result.message}", duration = 5000)
                )
            }
        }
    }

    /**
     * Removes embedded drawing ink from an inbox capture while keeping markdown text.
     * Deletes the linked DB page and KV entries.
     */
    suspend fun deleteCaptureInk(
        appRepository: AppRepository,
        vaultId: String,
        relativePath: String
    ): Boolean {
        val link = appRepository.kvProxy.get(
            noteKey(vaultId, relativePath), FlipSideLink.serializer()
        ) ?: return false
        val vault = vaultById(vaultId) ?: return false
        val root = vaultRootDir(vault) ?: return false
        val noteFile = File(root, relativePath.replace('/', File.separatorChar))
        val read = VaultFileStore.read(noteFile) ?: return false

        val stripped = ExcalidrawSerializer.stripDrawingFromUnified(read.content)
        when (VaultFileStore.write(noteFile, stripped, expectedHash = read.hash)) {
            is VaultFileStore.WriteResult.Success -> { /* ok */ }
            else -> return false
        }

        val strokes = appRepository.pageRepository.getWithStrokeById(link.pageId).strokes
        if (strokes.isNotEmpty()) {
            appRepository.strokeRepository.deleteAll(strokes.map { it.id })
        }
        appRepository.kvProxy.delete(noteKey(vaultId, relativePath))
        appRepository.kvProxy.delete(pageKey(link.pageId))
        try {
            appRepository.pageRepository.delete(link.pageId)
        } catch (e: Exception) {
            log.w("Failed to delete flip-side page ${link.pageId}: ${e.message}")
        }
        PageDataManager.evictLoadedPageData(link.pageId)
        releaseGuard(link.pageId)
        log.i("Deleted capture ink for $relativePath")
        return true
    }

    /** Replaces the page's strokes with the unified note file contents. */
    private suspend fun reimportFromFile(
        appRepository: AppRepository,
        pageId: String,
        vaultFile: File
    ) {
        val content = VaultFileStore.read(vaultFile)?.content
        if (content == null) {
            log.w("Flip-side file missing or unreadable: ${vaultFile.name}")
            return
        }
        val imported = ExcalidrawSerializer.parse(content, pageId)
        if (imported == null) {
            log.w("Could not parse flip-side file ${vaultFile.name} — keeping existing strokes")
            return
        }
        val existing = appRepository.pageRepository.getWithStrokeById(pageId).strokes
        if (existing.isNotEmpty()) {
            appRepository.strokeRepository.deleteAll(existing.map { it.id })
        }
        if (imported.isNotEmpty()) {
            appRepository.strokeRepository.create(imported)
        }
        PageDataManager.evictLoadedPageData(pageId)
        log.i("Re-imported ${imported.size} strokes from ${vaultFile.name}")
        updateHwrStrokeBaseline(appRepository, pageId)
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
