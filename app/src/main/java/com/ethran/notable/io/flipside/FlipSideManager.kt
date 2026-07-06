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
import com.ethran.notable.io.resolveExternalStoragePath
import com.ethran.notable.io.vault.FLIP_SIDE_SUFFIX
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.flipSideFileFor
import com.ethran.notable.io.vault.resolveFlipSideFile
import com.ethran.notable.io.vault.vaultRootDir
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
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
    private val CAPTURE_TIMESTAMP_FORMAT =
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
    private val CAPTURE_CREATED_DATE_FORMAT =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val PDF_FRONTMATTER_REGEX =
        Regex("""^pdf:\s*.+$""", RegexOption.MULTILINE)

    enum class HwrApplyMode { REPLACE, APPEND }

    data class LegacyMigrationResult(
        val pagesMigrated: Int,
        val mdNotesUpdated: Int,
        val failed: Int
    )

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
        val flipFile = resolveFlipSideFile(noteFile, root)

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

        val flipTarget = "$timestamp.flip.excalidraw"
        val markdown = buildCaptureNoteStub(createdAt, flipTarget)
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

    /**
     * Migrates a legacy quick page (DB-only capture) to the flip-side vault model.
     * Reuses the existing page as the flip-side editor surface when possible.
     */
    suspend fun migrateQuickPageToCapture(
        appRepository: AppRepository,
        pageId: String
    ): String? {
        if (linkForPage(appRepository, pageId) != null) {
            return pageId
        }

        val vault = GlobalAppSettings.current.activeVault
        if (vault == null || vault.inboxPath.isBlank()) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Configure a vault and inbox in Settings", duration = 4000)
            )
            return null
        }
        val root = vaultRootDir(vault) ?: return null
        val inboxDir = resolveExternalStoragePath(vault.inboxPath)
        inboxDir.mkdirs()

        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(pageId)
        val page = pageWithStrokes.page
        val strokes = pageWithStrokes.strokes
        val createdAt = page.createdAt
        val timestamp = CAPTURE_TIMESTAMP_FORMAT.format(createdAt)
        val noteFile = File(inboxDir, "$timestamp.md")
        val flipFile = flipSideFileFor(noteFile)
        val flipTarget = flipFile.name.removeSuffix(".md")

        if (noteFile.exists()) {
            val read = VaultFileStore.read(noteFile) ?: return null
            val updated = ensureCaptureFrontmatter(read.content, flipTarget)
            when (val result = VaultFileStore.write(noteFile, updated, expectedHash = read.hash)) {
                is VaultFileStore.WriteResult.Success -> { /* ok */ }
                else -> {
                    log.w("Could not update frontmatter for ${noteFile.name}: $result")
                    return null
                }
            }
        } else {
            val markdown = buildCaptureNoteStub(createdAt, flipTarget)
            when (val result = VaultFileStore.write(noteFile, markdown)) {
                is VaultFileStore.WriteResult.Success -> { /* ok */ }
                else -> {
                    log.e("Failed to create capture note ${noteFile.name}: $result")
                    return null
                }
            }
        }

        if (strokes.isNotEmpty()) {
            try {
                val excalidraw = ExcalidrawSerializer.serialize(strokes)
                val expected = VaultFileStore.currentHash(flipFile)
                when (val result = VaultFileStore.write(flipFile, excalidraw, expectedHash = expected)) {
                    is VaultFileStore.WriteResult.Success -> { /* ok */ }
                    else -> log.w("Could not write flip side for migration: $result")
                }
            } catch (e: OutOfMemoryError) {
                log.e(
                    "OOM exporting flip side for $pageId (${strokes.size} strokes) — link only; export on next save",
                    e
                )
            } catch (e: Exception) {
                log.e("Failed to export flip side for migration $pageId: ${e.message}", e)
            }
        }

        val relativePath = noteFile.relativeTo(root).path.replace('\\', '/')
        val folderId = ensureFlipFolder(appRepository)
        if (page.parentFolderId != folderId) {
            appRepository.pageRepository.update(page.copy(parentFolderId = folderId))
        }

        val fileHash = VaultFileStore.currentHash(flipFile)
        if (!acquireGuard(vault.id, relativePath, pageId)) return null

        val link = FlipSideLink(
            vaultId = vault.id,
            relativePath = relativePath,
            pageId = pageId,
            fileHash = fileHash
        )
        saveLink(appRepository, link)
        PageDataManager.evictLoadedPageData(pageId)
        log.i("Migrated quick page $pageId to flip-side capture $relativePath")
        return pageId
    }

    /**
     * One-time pass: migrates all legacy DB quick pages to flip-side captures and updates
     * any inbox markdown notes that still reference a PDF to use flip-side Excalidraw instead.
     */
    suspend fun runLegacyQuickPageMigration(
        appRepository: AppRepository
    ): LegacyMigrationResult {
        var pagesMigrated = 0
        var failed = 0

        val unmigrated = appRepository.pageRepository.getAllSinglePages().filter { page ->
            page.notebookId == null && linkForPage(appRepository, page.id) == null
        }
        for (page in unmigrated) {
            try {
                if (migrateQuickPageToCapture(appRepository, page.id) != null) {
                    pagesMigrated++
                } else {
                    failed++
                }
            } catch (e: OutOfMemoryError) {
                log.e("OOM migrating quick page ${page.id}", e)
                failed++
            } catch (e: Exception) {
                log.e("Failed migrating quick page ${page.id}: ${e.message}", e)
                failed++
            }
        }

        var mdNotesUpdated = 0
        for (vault in GlobalAppSettings.current.normalizedVaults().vaults) {
            if (vault.inboxPath.isBlank()) continue
            try {
                mdNotesUpdated += migrateInboxPdfNotesToFlipSide(appRepository, vault)
            } catch (e: OutOfMemoryError) {
                log.e("OOM migrating inbox PDF notes for vault ${vault.id}", e)
            } catch (e: Exception) {
                log.e("Failed migrating inbox PDF notes for vault ${vault.id}: ${e.message}", e)
            }
        }

        log.i(
            "Legacy quick-page migration: $pagesMigrated pages, $mdNotesUpdated md notes, $failed failed"
        )
        return LegacyMigrationResult(pagesMigrated, mdNotesUpdated, failed)
    }

    /**
     * Updates inbox notes that still have `pdf:` frontmatter: strips the PDF link, adds
     * `flip-side`, and creates the Excalidraw sidecar when missing.
     */
    internal suspend fun migrateInboxPdfNotesToFlipSide(
        appRepository: AppRepository,
        vault: VaultConfig
    ): Int {
        val inboxDir = resolveExternalStoragePath(vault.inboxPath)
        if (!inboxDir.isDirectory) return 0
        val root = vaultRootDir(vault) ?: return 0
        var updated = 0

        for (noteFile in inboxDir.listFiles().orEmpty()) {
            if (!noteFile.isFile) continue
            val name = noteFile.name
            if (!name.endsWith(".md", ignoreCase = true) ||
                name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true)
            ) {
                continue
            }

            val read = VaultFileStore.read(noteFile) ?: continue
            if (!PDF_FRONTMATTER_REGEX.containsMatchIn(read.content)) continue

            val flipFile = resolveFlipSideFile(noteFile, root)
            val flipTarget = flipFile.name.removeSuffix(".md")

            if (!flipFile.exists()) {
                val strokes = findPageByNoteTimestamp(appRepository, noteFile)
                    ?.let { page ->
                        appRepository.pageRepository.getWithStrokeById(page.id).strokes
                    }
                    .orEmpty()
                try {
                    when (val flipResult = VaultFileStore.write(
                        flipFile,
                        ExcalidrawSerializer.serialize(strokes)
                    )) {
                        is VaultFileStore.WriteResult.Success -> { /* ok */ }
                        else -> {
                            log.w("Could not create flip side for ${noteFile.name}: $flipResult")
                            continue
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    log.e("OOM creating flip side for ${noteFile.name} — skipping", e)
                    continue
                } catch (e: Exception) {
                    log.e("Failed creating flip side for ${noteFile.name}: ${e.message}", e)
                    continue
                }
            }

            val newContent = ensureCaptureFrontmatter(read.content, flipTarget)
            when (VaultFileStore.write(noteFile, newContent, expectedHash = read.hash)) {
                is VaultFileStore.WriteResult.Success -> updated++
                else -> log.w("Could not update frontmatter for ${noteFile.name}")
            }
        }
        return updated
    }

    /** Matches a legacy quick page to an inbox note by timestamp filename. */
    internal suspend fun findPageByNoteTimestamp(
        appRepository: AppRepository,
        noteFile: File
    ): Page? {
        val base = noteFile.name.removeSuffix(".md")
        return appRepository.pageRepository.getAllSinglePages()
            .find { CAPTURE_TIMESTAMP_FORMAT.format(it.createdAt) == base }
    }

    /** Builds minimal frontmatter for a new inbox capture (no PDF). */
    fun buildCaptureNoteStub(createdAt: Date, flipTarget: String): String {
        val createdDate = CAPTURE_CREATED_DATE_FORMAT.format(createdAt)
        return buildString {
            appendLine("---")
            appendLine("created: \"[[$createdDate]]\"")
            appendLine("$FRONTMATTER_KEY: \"[[$flipTarget]]\"")
            appendLine("---")
            appendLine()
        }
    }

    /** Ensures flip-side link exists and removes obsolete pdf frontmatter. */
    fun ensureCaptureFrontmatter(content: String, flipTarget: String): String {
        val withoutPdf = PDF_FRONTMATTER_REGEX.replace(content, "").replace(Regex("\n{3,}"), "\n\n")
        val flipProperty = "$FRONTMATTER_KEY: \"[[$flipTarget]]\""
        if (Regex("^$FRONTMATTER_KEY:", RegexOption.MULTILINE).containsMatchIn(withoutPdf)) {
            return withoutPdf
        }
        return addFrontmatterProperty(withoutPdf, flipProperty)
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

    /** True when [pageId] is a legacy quick page not yet linked to a vault flip side. */
    suspend fun isUnmigratedQuickPage(appRepository: AppRepository, pageId: String): Boolean {
        if (linkForPage(appRepository, pageId) != null) return false
        val page = appRepository.pageRepository.getById(pageId) ?: return false
        return page.notebookId == null
    }

    /** True when [pageId] is a flip-side drawing page (of either purpose). */
    suspend fun isFlipPage(appRepository: AppRepository, pageId: String): Boolean =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer()) != null

    /** The link for [pageId], or null when it isn't a flip-side page. */
    suspend fun linkForPage(appRepository: AppRepository, pageId: String): FlipSideLink? =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())

    /** Re-imports the flip-side file when it changed on disk (e.g. edited in Obsidian). */
    suspend fun syncFlipSideFromVaultIfChanged(appRepository: AppRepository, pageId: String) {
        val link = linkForPage(appRepository, pageId) ?: return
        if (link.purpose != PURPOSE_FLIP) return
        val vault = GlobalAppSettings.current.vaults.find { it.id == link.vaultId }
            ?: GlobalAppSettings.current.activeVault ?: return
        val root = vaultRootDir(vault) ?: return
        val noteFile = File(root, link.relativePath.replace('/', File.separatorChar))
        val flipFile = resolveFlipSideFile(noteFile, root)
        val fileHash = VaultFileStore.currentHash(flipFile)
        if (fileHash == link.fileHash || fileHash == VaultFileStore.HASH_MISSING) return
        reimportFromFile(appRepository, pageId, flipFile)
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
            is VaultFileStore.WriteResult.Success -> {
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
        val flipFile = resolveFlipSideFile(noteFile, root)

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
        val content = VaultFileStore.read(flipFile)?.content
        if (content == null) {
            log.w("Flip-side file missing or unreadable: ${flipFile.name}")
            return
        }
        val imported = ExcalidrawSerializer.parse(content, pageId)
        if (imported == null) {
            log.w("Could not parse flip-side file ${flipFile.name} — keeping existing strokes")
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
        log.i("Re-imported ${imported.size} strokes from ${flipFile.name}")
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
