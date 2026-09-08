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
import com.ethran.notable.io.resolveVaultAttachmentDir
import com.ethran.notable.io.vault.availableDrawingFile
import com.ethran.notable.io.vault.FLIP_SIDE_SUFFIX
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.inboxDirRelativeToVault
import com.ethran.notable.io.vault.obsidianSyncVaultRoot
import com.ethran.notable.io.vault.resolveAssociatedDrawingFile
import com.ethran.notable.io.vault.resolveVaultNoteFile
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
import org.json.JSONObject
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
    /** Vault-relative path of the modern `.excalidraw.md` drawing. */
    val drawingRelativePath: String = "",
    /** Hashes are independent so text and drawing edits cannot overwrite each other. */
    val drawingFileHash: String = VaultFileStore.HASH_MISSING,
    val noteFileHash: String = VaultFileStore.HASH_MISSING,
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
 * The "flip side" of a vault note: text lives in `.md`, while ink lives in a linked
 * modern `.excalidraw.md` file under the configured attachment directory.
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
    private val DAILY_NOTE_DATE_FORMAT =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)

    enum class HwrApplyMode { REPLACE, APPEND }

    private fun noteKey(vaultId: String, relativePath: String) = "FLIP_PAGE:$vaultId:$relativePath"
    private fun pageKey(pageId: String) = "FLIP_NOTE:$pageId"

    private fun defaultNativeBackground(): String {
        val bg = GlobalAppSettings.current.defaultNativeTemplate
        return bg.ifBlank { "blank" }
    }

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

    private fun vaultRelativePath(file: File, root: File): String? = runCatching {
        file.relativeTo(root).path.replace('\\', '/')
    }.getOrNull()?.takeUnless { it == ".." || it.startsWith("../") }

    private fun drawingFileForLink(link: FlipSideLink, vault: VaultConfig): File? {
        val root = vaultRootDir(vault) ?: return null
        if (link.drawingRelativePath.isNotBlank()) {
            return File(root, link.drawingRelativePath.replace('/', File.separatorChar))
        }
        val noteFile = resolveVaultNoteFile(vault, link.relativePath) ?: return null
        return resolveAssociatedDrawingFile(
            noteFile,
            root,
            linkRoot = obsidianSyncVaultRoot(vault) ?: root
        )
    }

    private fun isDrawingReferencedElsewhere(
        vault: VaultConfig,
        noteFile: File,
        drawingFile: File
    ): Boolean {
        val root = vaultRootDir(vault) ?: return true
        val linkRoot = obsidianSyncVaultRoot(vault) ?: root
        val target = runCatching { drawingFile.canonicalPath }.getOrNull() ?: return true
        return root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter {
                it.isFile && it != noteFile && it.name.endsWith(".md", ignoreCase = true)
            }
            .any { candidate ->
                val content = runCatching { candidate.readText() }.getOrNull() ?: return@any false
                ExcalidrawSerializer.allDrawingLinkPaths(content).any { path ->
                    val singleLink = ExcalidrawSerializer.withDrawingLink("", path)
                    resolveAssociatedDrawingFile(candidate, root, singleLink, linkRoot)
                        ?.let { runCatching { it.canonicalPath }.getOrNull() == target } == true
                }
            }
    }

    private suspend fun ensureDrawingAssociation(
        noteFile: File,
        vault: VaultConfig,
        strokes: List<Stroke> = emptyList()
    ): File? {
        val root = vaultRootDir(vault) ?: return null
        val syncRoot = obsidianSyncVaultRoot(vault) ?: root
        val currentNote = VaultFileStore.read(noteFile)
        val associated = resolveAssociatedDrawingFile(noteFile, root, linkRoot = syncRoot)
        val activePath = currentNote?.content?.let(ExcalidrawSerializer::drawingLinkPath)
        if (associated != null && activePath?.endsWith(".excalidraw", ignoreCase = true) == true) {
            val noteRead = currentNote ?: return null
            val modernFile = availableDrawingFile(noteFile, vault) ?: return null
            val modernMetadataPath = vaultRelativePath(modernFile, syncRoot) ?: return null
            val rawJson = VaultFileStore.read(associated)?.content
                ?.let(ExcalidrawSerializer::extractDrawingJson)
                ?: return null
            val modernContent = ExcalidrawSerializer.wrapRawDrawingMarkdown(rawJson)
            if (VaultFileStore.write(modernFile, modernContent, VaultFileStore.HASH_MISSING)
                !is VaultFileStore.WriteResult.Success
            ) {
                return null
            }
            val linked = ExcalidrawSerializer.withDrawingLinks(
                noteRead.content,
                modernMetadataPath,
                ExcalidrawSerializer.drawingHistoryPaths(noteRead.content)
            )
            return when (VaultFileStore.write(noteFile, linked, noteRead.hash)) {
                is VaultFileStore.WriteResult.Success -> modernFile
                else -> {
                    VaultFileStore.delete(modernFile, VaultFileStore.hashOf(modernContent))
                    null
                }
            }
        }
        associated?.let { return it }
        if (currentNote?.content?.let(ExcalidrawSerializer::drawingLinkPath) != null) {
            log.w("Linked drawing is missing for ${noteFile.name}; preserving the association")
            return null
        }
        val drawingFile = availableDrawingFile(noteFile, vault) ?: return null
        val metadataDrawingPath = vaultRelativePath(drawingFile, syncRoot) ?: return null
        val noteRead = currentNote
        val embeddedJson = noteRead?.content?.let(ExcalidrawSerializer::extractDrawingJson)
        val drawingContent = embeddedJson
            ?.let {
                runCatching {
                    ExcalidrawSerializer.wrapRawDrawingMarkdown(JSONObject(it).toString())
                }.getOrNull()
            }
            ?: ExcalidrawSerializer.serializeDrawingMarkdown(strokes)
        if (VaultFileStore.write(drawingFile, drawingContent) !is VaultFileStore.WriteResult.Success) {
            return null
        }
        if (ExcalidrawSerializer.extractDrawingJson(drawingContent) == null) {
            VaultFileStore.delete(drawingFile)
            return null
        }
        val textOnly = noteRead?.content?.let(ExcalidrawSerializer::stripDrawingFromUnified).orEmpty()
        val linked = ExcalidrawSerializer.withDrawingLink(textOnly, metadataDrawingPath)
        return when (
            VaultFileStore.write(
                noteFile,
                linked,
                expectedHash = noteRead?.hash ?: VaultFileStore.HASH_MISSING
            )
        ) {
            is VaultFileStore.WriteResult.Success -> drawingFile
            else -> {
                VaultFileStore.delete(drawingFile)
                null
            }
        }
    }

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
        val noteFile = resolveVaultNoteFile(vault, noteRelativePath) ?: return null
        if (!noteFile.exists()) return null
        val drawingFile = ensureDrawingAssociation(noteFile, vault) ?: return null
        val drawingRelativePath = vaultRelativePath(drawingFile, root) ?: return null

        val existingLink = appRepository.kvProxy.get(
            noteKey(vault.id, noteRelativePath), FlipSideLink.serializer()
        )
        val existingPage = existingLink?.let { appRepository.pageRepository.getById(it.pageId) }
        val drawingHash = VaultFileStore.currentHash(drawingFile)
        val noteHash = VaultFileStore.currentHash(noteFile)

        if (existingLink != null && existingPage != null) {
            if (!acquireGuard(vault.id, noteRelativePath, existingLink.pageId)) return null
            val previousDrawingHash = existingLink.drawingFileHash
                .takeUnless { it == VaultFileStore.HASH_MISSING }
                ?: existingLink.fileHash
            if (drawingHash != previousDrawingHash && drawingHash != VaultFileStore.HASH_MISSING) {
                reimportFromFile(appRepository, existingLink.pageId, drawingFile)
            }
            saveLink(
                appRepository,
                existingLink.copy(
                    fileHash = drawingHash,
                    drawingRelativePath = drawingRelativePath,
                    drawingFileHash = drawingHash,
                    noteFileHash = noteHash
                )
            )
            ensureHwrStrokeBaseline(appRepository, existingLink.pageId)
            return existingLink.pageId
        }

        // Create the drawing page in the "Flip Sides" folder
        val folderId = ensureFlipFolder(appRepository)
        val page = Page(
            notebookId = null,
            parentFolderId = folderId,
            background = defaultNativeBackground(),
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

        reimportFromFile(appRepository, page.id, drawingFile)

        val link = FlipSideLink(
            vaultId = vault.id,
            relativePath = noteRelativePath,
            pageId = page.id,
            fileHash = drawingHash,
            drawingRelativePath = drawingRelativePath,
            drawingFileHash = drawingHash,
            noteFileHash = noteHash
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
    suspend fun createNewCapture(
        appRepository: AppRepository,
        vaultId: String,
        targetRelativeDir: String? = null
    ): String? {
        val vault = vaultById(vaultId) ?: return missingVaultSnack()
        if (vault.inboxPath.isBlank()) {
            return missingVaultSnack()
        }
        val root = vaultRootDir(vault) ?: return null
        val parentDir = targetRelativeDir?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
            ?.let { File(root, it.replace('/', File.separatorChar)) }
            ?: resolveExternalStoragePath(vault.inboxPath)
        parentDir.mkdirs()

        val createdAt = Date()
        val timestamp = CAPTURE_TIMESTAMP_FORMAT.format(createdAt)
        val noteFile = File(parentDir, "$timestamp.md")
        if (noteFile.exists()) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Capture file already exists: $timestamp", duration = 4000)
            )
            return null
        }

        val markdown = buildCaptureNoteStub(createdAt)
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

        if (ensureDrawingAssociation(noteFile, vault) == null) {
            VaultFileStore.delete(noteFile)
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Could not create drawing in attachment folder", duration = 4000)
            )
            return null
        }
        val relativePath = noteFile.relativeTo(root).path.replace('\\', '/')
        return openFlipSide(appRepository, vaultId, relativePath)
    }

    /** Vault-relative path for today's daily note (`YYYY-MM-DD.md`). */
    fun dailyNoteRelativePath(
        vault: VaultConfig,
        date: Date = Date(),
        dailyNoteFolder: String = GlobalAppSettings.current.dailyNoteFolder
    ): String? {
        if (vault.inboxPath.isBlank()) return null
        return dailyNoteRelativePath(
            inboxRelativeToVault = inboxDirRelativeToVault(vault),
            date = date,
            dailyNoteFolder = dailyNoteFolder
        )
    }

    /** Pure path builder for tests and callers that already know the inbox-relative folder. */
    fun dailyNoteRelativePath(
        inboxRelativeToVault: String?,
        date: Date,
        dailyNoteFolder: String
    ): String? {
        val fileName = "${DAILY_NOTE_DATE_FORMAT.format(date)}.md"
        val folder = dailyNoteFolder.trim().trim('/')
        return if (folder.isBlank()) {
            val inboxRel = inboxRelativeToVault ?: return null
            if (inboxRel.isBlank()) fileName else "$inboxRel/$fileName"
        } else {
            "$folder/$fileName"
        }
    }

    /**
     * Opens today's daily note in the flip-side editor, creating its Markdown note and
     * linked Excalidraw attachment when they do not exist yet.
     */
    suspend fun openOrCreateDailyNote(appRepository: AppRepository, vaultId: String): String? {
        val vault = vaultById(vaultId) ?: return missingVaultSnack()
        val relativePath = dailyNoteRelativePath(vault) ?: return missingVaultSnack()
        val noteFile = resolveVaultNoteFile(vault, relativePath) ?: return null

        if (!noteFile.exists()) {
            noteFile.parentFile?.mkdirs()
            val markdown = buildCaptureNoteStub(Date())
            when (val result = VaultFileStore.write(noteFile, markdown)) {
                is VaultFileStore.WriteResult.Success -> { /* ok */ }
                else -> {
                    log.e("Failed to create daily note ${noteFile.name}: $result")
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Could not create daily note", duration = 4000)
                    )
                    return null
                }
            }
            if (ensureDrawingAssociation(noteFile, vault) == null) {
                VaultFileStore.delete(noteFile)
                return null
            }
        }
        return openFlipSide(appRepository, vaultId, relativePath)
    }

    suspend fun openOrCreateDailyNote(appRepository: AppRepository): String? {
        val vault = GlobalAppSettings.current.activeVault ?: return missingVaultSnack()
        return openOrCreateDailyNote(appRepository, vault.id)
    }

    private fun missingVaultSnack(): String? {
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(text = "Configure a vault and inbox in Settings", duration = 4000)
        )
        return null
    }

    /** Builds the small text-note half of a new capture pair. */
    fun buildCaptureNoteStub(createdAt: Date): String {
        val createdDate = CAPTURE_CREATED_DATE_FORMAT.format(createdAt)
        return "---\ncreated: \"[[$createdDate]]\"\n---\n"
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

    /** Sanitizes a capture filename base (no `.md`). Returns null when invalid. */
    fun sanitizeCaptureBaseName(input: String): String? {
        val trimmed = input.trim().removeSuffix(".md")
        if (trimmed.isBlank()) return null
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        val illegal = charArrayOf('<', '>', ':', '"', '|', '?', '*')
        if (trimmed.any { it in illegal }) return null
        return trimmed
    }

    /**
     * Renames a vault capture file in place and migrates the flip-side KV link.
     * Returns the new vault-relative path on success.
     */
    suspend fun renameCapture(
        appRepository: AppRepository,
        vaultId: String,
        oldRelativePath: String,
        newBaseName: String
    ): Result<String> {
        val safeName = sanitizeCaptureBaseName(newBaseName)
            ?: return Result.failure(IllegalArgumentException("Invalid name"))
        val vault = vaultById(vaultId)
            ?: return Result.failure(IllegalStateException("Vault not found"))
        val root = vaultRootDir(vault)
            ?: return Result.failure(IllegalStateException("Vault root not found"))
        val oldFile = File(root, oldRelativePath.replace('/', File.separatorChar))
        if (!oldFile.exists()) {
            return Result.failure(IllegalStateException("Note not found"))
        }
        val parent = oldFile.parentFile
            ?: return Result.failure(IllegalStateException("Invalid path"))
        val newFile = File(parent, "$safeName.md")
        if (newFile.exists()) {
            return Result.failure(IllegalStateException("Name already exists"))
        }
        val newRelativePath = noteRelativePath(newFile, vault)
            ?: return Result.failure(IllegalStateException("Invalid path"))
        val oldRead = VaultFileStore.read(oldFile)
            ?: return Result.failure(IllegalStateException("Note could not be read"))
        if (VaultFileStore.write(
                newFile,
                oldRead.content,
                expectedHash = VaultFileStore.HASH_MISSING
            ) !is VaultFileStore.WriteResult.Success
        ) {
            return Result.failure(IllegalStateException("Rename failed"))
        }
        if (VaultFileStore.delete(oldFile, expectedHash = oldRead.hash)
            !is VaultFileStore.WriteResult.Success
        ) {
            VaultFileStore.delete(newFile, VaultFileStore.currentHash(newFile))
            return Result.failure(IllegalStateException("Note changed during rename"))
        }

        val link = appRepository.kvProxy.get(
            noteKey(vaultId, oldRelativePath), FlipSideLink.serializer()
        )
        if (link != null) {
            appRepository.kvProxy.delete(noteKey(vaultId, oldRelativePath))
            saveLink(
                appRepository,
                link.copy(
                    relativePath = newRelativePath,
                    noteFileHash = VaultFileStore.currentHash(newFile)
                )
            )
        }
        log.i("Renamed capture $oldRelativePath -> $newRelativePath")
        return Result.success(newRelativePath)
    }

    /** Resolves the global default native page background for new flip-side pages. */
    internal fun resolveDefaultNativeBackground(): String = defaultNativeBackground()

    /** True when [pageId] is a flip-side drawing page (of either purpose). */
    suspend fun isFlipPage(appRepository: AppRepository, pageId: String): Boolean =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer()) != null

    /** The link for [pageId], or null when it isn't a flip-side page. */
    suspend fun linkForPage(appRepository: AppRepository, pageId: String): FlipSideLink? =
        appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())

    /** Re-imports the unified note when it changed on disk (e.g. edited in Obsidian). */
    suspend fun syncFlipSideFromVaultIfChanged(
        appRepository: AppRepository,
        pageId: String
    ): Boolean {
        val link = linkForPage(appRepository, pageId) ?: return false
        if (link.purpose != PURPOSE_FLIP) return false
        val vault = GlobalAppSettings.current.vaults.find { it.id == link.vaultId }
            ?: GlobalAppSettings.current.activeVault ?: return false
        val drawingFile = drawingFileForLink(link, vault) ?: return false
        val fileHash = VaultFileStore.currentHash(drawingFile)
        val previousHash = link.drawingFileHash
            .takeUnless { it == VaultFileStore.HASH_MISSING }
            ?: link.fileHash
        if (fileHash == previousHash || fileHash == VaultFileStore.HASH_MISSING) return false
        reimportFromFile(appRepository, pageId, drawingFile)
        saveLink(
            appRepository,
            link.copy(fileHash = fileHash, drawingFileHash = fileHash)
        )
        updateHwrStrokeBaseline(appRepository, pageId)
        return true
    }

    /** Stable fingerprint of a flip-side page's strokes for HWR change detection. */
    internal fun strokesFingerprint(strokes: List<Stroke>): String {
        if (strokes.isEmpty()) return "empty"
        return strokes
            .sortedBy { it.id }
            .joinToString("|") { stroke ->
                val pointsHash = stroke.points.fold(1) { hash, point ->
                    var next = 31 * hash + point.x.toBits()
                    next = 31 * next + point.y.toBits()
                    next = 31 * next + (point.pressure?.toBits() ?: 0)
                    next
                }
                "${stroke.id}:${stroke.points.size}:$pointsHash:" +
                    "${stroke.top}:${stroke.bottom}:${stroke.left}:${stroke.right}"
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
        return VaultFileStore.withFileLock(noteFile) {
            val read = VaultFileStore.read(noteFile)
                ?: return@withFileLock "Note is missing — recognized text was not saved"
            val existingBody = ExcalidrawSerializer.extractMarkdownBody(read.content)
            val newBody = when (mode) {
                HwrApplyMode.APPEND ->
                    (existingBody + "\n\n" + text).trim()
                HwrApplyMode.REPLACE -> text.trim()
            }
            val nextCanvas = if (mode == HwrApplyMode.APPEND && link.purpose == PURPOSE_FLIP) {
                prepareNextCanvas(appRepository, link, vault, noteFile, read.content)
                    ?: return@withFileLock "Drawing changed elsewhere — append cancelled"
            } else {
                null
            }
            val metadataContent = nextCanvas?.noteContent ?: read.content
            val newContent = ExcalidrawSerializer.rewriteMarkdownBody(metadataContent, newBody)

            when (val result = VaultFileStore.write(noteFile, newContent, read.hash)) {
                is VaultFileStore.WriteResult.Success -> {
                    if (nextCanvas != null) {
                        val strokes = appRepository.pageRepository.getWithStrokeById(pageId).strokes
                        if (strokes.isNotEmpty()) {
                            appRepository.strokeRepository.deleteAll(strokes.map { it.id })
                        }
                        PageDataManager.evictLoadedPageData(pageId)
                    }
                    saveLink(
                        appRepository,
                        nextCanvas?.link?.copy(noteFileHash = VaultFileStore.hashOf(newContent))
                            ?: link.copy(noteFileHash = VaultFileStore.hashOf(newContent))
                    )
                    updateHwrStrokeBaseline(appRepository, pageId)
                    "Saved to ${noteFile.name}"
                }
                is VaultFileStore.WriteResult.Conflict -> {
                    val copy = VaultFileStore.writeConflictCopy(noteFile, newContent)
                    if (copy == null) {
                        nextCanvas?.file?.let {
                            VaultFileStore.delete(it, nextCanvas.link.drawingFileHash)
                        }
                    }
                    "Note changed elsewhere — saved as ${copy?.name ?: "conflict copy"}"
                }
                is VaultFileStore.WriteResult.Error -> {
                    nextCanvas?.file?.let {
                        VaultFileStore.delete(it, nextCanvas.link.drawingFileHash)
                    }
                    "Save failed: ${result.message}"
                }
            }
        }
    }

    private data class NextCanvas(
        val file: File,
        val noteContent: String,
        val link: FlipSideLink
    )

    private suspend fun prepareNextCanvas(
        appRepository: AppRepository,
        link: FlipSideLink,
        vault: VaultConfig,
        noteFile: File,
        noteContent: String
    ): NextCanvas? {
        val root = vaultRootDir(vault) ?: return null
        val syncRoot = obsidianSyncVaultRoot(vault) ?: root
        val currentDrawing = drawingFileForLink(link, vault) ?: return null
        val strokes = appRepository.pageRepository.getWithStrokeById(link.pageId).strokes
        val currentRead = VaultFileStore.read(currentDrawing)
        val currentContent = ExcalidrawSerializer.serializeDrawingMarkdown(
            strokes,
            currentRead?.content
        )
        val expectedCurrentHash = link.drawingFileHash
            .takeUnless { it == VaultFileStore.HASH_MISSING }
            ?: link.fileHash
        if (VaultFileStore.write(currentDrawing, currentContent, expectedCurrentHash)
            !is VaultFileStore.WriteResult.Success
        ) {
            return null
        }
        val currentHash = VaultFileStore.hashOf(currentContent)
        saveLink(
            appRepository,
            link.copy(fileHash = currentHash, drawingFileHash = currentHash)
        )

        val nextFile = availableDrawingFile(noteFile, vault) ?: return null
        val nextMetadataPath = vaultRelativePath(nextFile, syncRoot) ?: return null
        val nextVaultPath = vaultRelativePath(nextFile, root) ?: return null
        val blankContent = ExcalidrawSerializer.serializeDrawingMarkdown(emptyList())
        if (VaultFileStore.write(nextFile, blankContent, VaultFileStore.HASH_MISSING)
            !is VaultFileStore.WriteResult.Success
        ) {
            return null
        }

        val currentMetadataPath = ExcalidrawSerializer.drawingLinkPath(noteContent)
            ?: vaultRelativePath(currentDrawing, syncRoot)
            ?: return null
        val history = ExcalidrawSerializer.drawingHistoryPaths(noteContent) + currentMetadataPath
        val updatedNote = ExcalidrawSerializer.withDrawingLinks(
            noteContent,
            nextMetadataPath,
            history
        )
        val nextHash = VaultFileStore.hashOf(blankContent)
        return NextCanvas(
            file = nextFile,
            noteContent = updatedNote,
            link = link.copy(
                fileHash = nextHash,
                drawingRelativePath = nextVaultPath,
                drawingFileHash = nextHash,
                hwrStrokeBaseline = "empty"
            )
        )
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
            background = defaultNativeBackground(),
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
        // The editor no longer owns the note once onDispose runs. Release synchronously
        // so a dashboard reopen is not blocked by the queued, hash-checked save.
        releaseGuard(pageId)
        ioScope.launch {
            val link = appRepository.kvProxy.get(pageKey(pageId), FlipSideLink.serializer())
            if (link == null || link.purpose != PURPOSE_FLIP) {
                return@launch
            }
            val vault = settings.vaults.find { it.id == link.vaultId }
                ?: GlobalAppSettings.current.activeVault
            if (vault == null) {
                return@launch
            }
            if (vaultRootDir(vault) == null) {
                return@launch
            }
            val drawingFile = drawingFileForLink(link, vault)
            if (drawingFile == null) {
                return@launch
            }
            VaultWriteQueue.enqueue(drawingFile, "flip-side save") {
                saveFlipSide(appRepository, link, vault)
            }
        }
    }

    private suspend fun saveFlipSide(
        appRepository: AppRepository,
        link: FlipSideLink,
        vault: VaultConfig
    ) {
        vaultRootDir(vault) ?: return
        val drawingFile = drawingFileForLink(link, vault) ?: return

        val strokes = appRepository.pageRepository.getWithStrokeById(link.pageId).strokes
        val read = VaultFileStore.read(drawingFile)
        val existing = read?.content.orEmpty()
        if (strokes.isEmpty() && !ExcalidrawSerializer.hasNonemptyInk(existing)) return

        val content = ExcalidrawSerializer.serializeDrawingMarkdown(
            strokes,
            existing.takeIf { it.isNotBlank() }
        )
        val expected = link.drawingFileHash
            .takeUnless { it == VaultFileStore.HASH_MISSING }
            ?: link.fileHash
        when (val result = VaultFileStore.write(drawingFile, content, expectedHash = expected)) {
            is VaultFileStore.WriteResult.Success -> {
                val hash = VaultFileStore.hashOf(content)
                saveLink(
                    appRepository,
                    link.copy(fileHash = hash, drawingFileHash = hash)
                )
                log.i("Flip side saved: ${drawingFile.name} (${strokes.size} strokes)")
            }
            is VaultFileStore.WriteResult.Conflict -> {
                val copy = VaultFileStore.writeConflictCopy(drawingFile, content)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Note changed elsewhere — saved as ${copy?.name ?: "conflict copy"}",
                        duration = 6000
                    )
                )
                log.w("Flip side conflict for ${drawingFile.name}; conflict copy: ${copy?.name}")
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
        val drawingFile = resolveAssociatedDrawingFile(
            noteFile,
            root,
            read.content,
            obsidianSyncVaultRoot(vault) ?: root
        )

        val stripped = ExcalidrawSerializer.withoutDrawingLink(
            ExcalidrawSerializer.stripDrawingFromUnified(read.content)
        )
        when (VaultFileStore.write(noteFile, stripped, expectedHash = read.hash)) {
            is VaultFileStore.WriteResult.Success -> { /* ok */ }
            else -> return false
        }
        if (drawingFile != null && !isDrawingReferencedElsewhere(vault, noteFile, drawingFile)) {
            val expectedHash = link.drawingFileHash
                .takeUnless { it == VaultFileStore.HASH_MISSING }
                ?: link.fileHash
            if (VaultFileStore.delete(drawingFile, expectedHash) is VaultFileStore.WriteResult.Conflict) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Drawing changed elsewhere and was preserved",
                        duration = 5000
                    )
                )
            }
        }

        detachFlipSideLink(appRepository, vaultId, relativePath)
        log.i("Deleted capture ink for $relativePath")
        return true
    }

    /**
     * Permanently deletes a vault note or folder (and folder contents).
     * Detaches flip-side DB pages but does not update bookshelf or app settings — callers handle that.
     */
    suspend fun deleteVaultEntry(
        appRepository: AppRepository,
        vaultId: String,
        relativePath: String,
        isFolder: Boolean
    ): Boolean {
        val vault = vaultById(vaultId) ?: return false
        val root = vaultRootDir(vault) ?: return false
        val target = File(root, relativePath.replace('/', File.separatorChar))
        if (!target.exists()) return false
        return if (isFolder) {
            if (!target.isDirectory) return false
            deleteDirectoryTree(appRepository, vaultId, vault, target)
        } else {
            deleteNoteFile(appRepository, vaultId, vault, target)
        }
    }

    private suspend fun deleteNoteFile(
        appRepository: AppRepository,
        vaultId: String,
        vault: VaultConfig,
        file: File
    ): Boolean {
        val relativePath = noteRelativePath(file, vault) ?: return false
        val root = vaultRootDir(vault)
        val link = appRepository.kvProxy.get(
            noteKey(vaultId, relativePath),
            FlipSideLink.serializer()
        )
        val drawingFile = root?.let {
            resolveAssociatedDrawingFile(
                file,
                it,
                linkRoot = obsidianSyncVaultRoot(vault) ?: it
            )
        }
        detachFlipSideLink(appRepository, vaultId, relativePath)
        val sidecarName = file.name.removeSuffix(".md") + FLIP_SIDE_SUFFIX
        val sidecar = File(file.parentFile, sidecarName)
        if (sidecar.exists()) VaultFileStore.delete(sidecar)
        val deleted = VaultFileStore.delete(file) is VaultFileStore.WriteResult.Success
        if (deleted && drawingFile != null &&
            !isDrawingReferencedElsewhere(vault, file, drawingFile)
        ) {
            val expectedHash = link?.drawingFileHash
                ?.takeUnless { it == VaultFileStore.HASH_MISSING }
                ?: link?.fileHash
            VaultFileStore.delete(drawingFile, expectedHash)
        }
        return deleted
    }

    private suspend fun deleteDirectoryTree(
        appRepository: AppRepository,
        vaultId: String,
        vault: VaultConfig,
        dir: File
    ): Boolean {
        val files = dir.walkBottomUp().toList()
        for (file in files) {
            when {
                file.isDirectory -> file.delete()
                file.name.endsWith(".md", ignoreCase = true) &&
                    !file.name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true) -> {
                    deleteNoteFile(appRepository, vaultId, vault, file)
                }
                else -> VaultFileStore.delete(file)
            }
        }
        return !dir.exists()
    }

    private suspend fun detachFlipSideLink(
        appRepository: AppRepository,
        vaultId: String,
        relativePath: String
    ) {
        val link = appRepository.kvProxy.get(
            noteKey(vaultId, relativePath), FlipSideLink.serializer()
        ) ?: return
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
    }

    /** Replaces the page's strokes with the associated Excalidraw file contents. */
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
