package com.ethran.notable.ui.views

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.markdown.MarkdownRenderer
import com.ethran.notable.io.markdown.RenderedMarkdown
import com.ethran.notable.io.obsidiansync.ObsidianSyncManager
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.VaultNoteEditModel
import com.ethran.notable.io.vault.VaultNoteEditRegions
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.resolveVaultNoteFile
import com.ethran.notable.io.vault.vaultRootDir
import com.ethran.notable.navigation.DeepLinks
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.io.markdown.MarkdownEdits
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.AnnotatableReaderBody
import com.ethran.notable.ui.components.NoteSourceEditor
import com.ethran.notable.ui.components.ObsidianSyncIndicator
import com.ethran.notable.ui.components.QuickSwitcher
import com.ethran.notable.ui.components.SingularityToggleIcon
import com.ethran.notable.ui.components.ReaderAnnotationState
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.FileText
import compose.icons.feathericons.PenTool
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object NoteReaderDestination : NavigationDestination {
    override val route = "vaultnote"
    const val VAULT_ID_ARG = "vaultId"
    const val PATH_ARG = "path"
    val routeWithArgs = "$route?$VAULT_ID_ARG={$VAULT_ID_ARG}&$PATH_ARG={$PATH_ARG}"
    fun createRoute(vaultId: String, relativePath: String) =
        "$route?$VAULT_ID_ARG=${android.net.Uri.encode(vaultId)}" +
            "&$PATH_ARG=${android.net.Uri.encode(relativePath)}"

    /** Opens using the active vault when [vaultId] is not known (deep links, legacy callers). */
    fun createRoute(relativePath: String): String {
        val vaultId = GlobalAppSettings.current.activeVault?.id.orEmpty()
        return createRoute(vaultId, relativePath)
    }
}

/** Loaded state of a note in the reader. */
class NoteReaderState(
    var file: File,
    val relativePath: String,
    val vaultName: String?
) {
    var content by mutableStateOf<String?>(null)
    var contentHash by mutableStateOf<String?>(null)
    var rendered by mutableStateOf<RenderedMarkdown?>(null)
    var error by mutableStateOf<String?>(null)
    var missingOnDisk by mutableStateOf(false)

    fun load(fontScale: Float = 1f) {
        if (!file.exists()) {
            missingOnDisk = true
            error = buildNoteMissingMessage(file.name, vaultName, relativePath)
            content = null
            contentHash = null
            rendered = null
            return
        }
        val result = VaultFileStore.read(file)
        if (result == null) {
            missingOnDisk = false
            error = "Could not read ${file.name}"
            return
        }
        missingOnDisk = false
        content = result.content
        contentHash = result.hash
        rendered = MarkdownRenderer.renderForReader(result.content, fontScale)
        error = null
    }

    /** Re-renders the already-loaded content at a new font scale. */
    fun rerender(fontScale: Float) {
        val current = content ?: return
        if (rendered?.theme?.scale == fontScale) return
        rendered = MarkdownRenderer.renderForReader(current, fontScale)
    }
}

private fun buildNoteMissingMessage(
    fileName: String,
    vaultName: String?,
    relativePath: String
): String {
    val vaultLabel = vaultName?.takeIf { it.isNotBlank() } ?: "vault"
    return "Note not on this device yet\n$fileName · $vaultLabel\n$relativePath"
}

/**
 * Vault note reader: Bear-style rendered markdown with tappable wikilinks and
 * markdown links, frontmatter as a collapsible properties block.
 */
@Composable
fun NoteReaderView(
    vaultId: String,
    relativePath: String,
    appRepository: AppRepository,
    obsidianSyncManager: ObsidianSyncManager,
    onOpenNote: (String, String) -> Unit,
    onOpenFlipSide: (String, String) -> Unit,
    onHandwriteInto: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = GlobalAppSettings.current
    val vault = settings.normalizedVaults().vaults.find { it.id == vaultId }
        ?: settings.activeVault
    val vaultRoot = vault?.let { vaultRootDir(it) }
    val index = vault?.let { VaultIndexRegistry.forVault(it) }

    var showQuickSwitcher by remember { mutableStateOf(false) }
    var annotationMode by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var editRegions by remember { mutableStateOf<VaultNoteEditRegions?>(null) }
    var draftBody by remember(relativePath) { mutableStateOf("") }
    var showDiscardEditDialog by remember { mutableStateOf(false) }
    var conflictContent by remember { mutableStateOf<String?>(null) }
    var conflictSource by remember { mutableStateOf<NoteConflictSource?>(null) }
    var showFontControls by remember { mutableStateOf(false) }
    var fontScale by remember { mutableStateOf(settings.readerFontScale) }
    var syncing by remember { mutableStateOf(false) }
    val annotationState = remember(relativePath) { ReaderAnnotationState() }

    // Undo for the last applied annotation save: (content before the save, file hash
    // right after it). Valid until the file changes again from anywhere else.
    var undoSnapshot by remember(relativePath) { mutableStateOf<Pair<String, String>?>(null) }

    fun adjustFontScale(delta: Float) {
        val clamped = (Math.round((fontScale + delta) * 10f) / 10f).coerceIn(0.7f, 1.8f)
        fontScale = clamped
    }

    fun saveDefaultFontScale() {
        scope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(
                GlobalAppSettings.current.copy(readerFontScale = fontScale)
            )
        }
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                text = "Default text size set to ${Math.round(fontScale * 100)}%",
                duration = 2500
            )
        )
    }

    // Single-writer guard: only one window/surface may edit this note at a time.
    val editOwner = remember { UUID.randomUUID().toString() }
    val guardKey = vault?.let { NoteEditGuard.noteKey(it.id, relativePath) }

    val state = remember(vaultId, relativePath, vault) {
        val noteFile = vault?.let { resolveVaultNoteFile(it, relativePath) }
            ?: File(vaultRoot ?: File("."), relativePath.replace('/', File.separatorChar))
        NoteReaderState(
            file = noteFile,
            relativePath = relativePath,
            vaultName = vault?.displayName
        )
    }

    val isEditDirty = editMode && editRegions != null && draftBody != editRegions!!.editableBody

    fun exitAnnotationMode() {
        annotationMode = false
        guardKey?.let { NoteEditGuard.release(it, editOwner) }
    }

    fun exitEditMode() {
        editMode = false
        editRegions = null
        draftBody = ""
        guardKey?.let { NoteEditGuard.release(it, editOwner) }
    }

    fun enterAnnotationMode() {
        if (editMode) exitEditMode()
        if (guardKey != null && !NoteEditGuard.tryAcquire(guardKey, editOwner)) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Note is being edited in another window", duration = 4000)
            )
            return
        }
        annotationMode = true
    }

    fun enterEditMode() {
        if (annotationMode) {
            annotationState.clear()
            exitAnnotationMode()
        }
        if (guardKey != null && !NoteEditGuard.tryAcquire(guardKey, editOwner)) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Note is being edited in another window", duration = 4000)
            )
            return
        }
        val content = state.content ?: return
        val regions = VaultNoteEditModel.splitForEditing(content)
        editRegions = regions
        draftBody = regions.editableBody
        editMode = true
    }

    fun requestBack() {
        when {
            editMode && isEditDirty -> showDiscardEditDialog = true
            editMode -> exitEditMode()
            else -> onBack()
        }
    }

    BackHandler(enabled = editMode) {
        if (isEditDirty) showDiscardEditDialog = true else exitEditMode()
    }

    DisposableEffect(guardKey) {
        onDispose { guardKey?.let { NoteEditGuard.release(it, editOwner) } }
    }

    fun reloadNote() {
        val noteFile = vault?.let { resolveVaultNoteFile(it, relativePath) }
        if (noteFile != null) {
            state.file = noteFile
        }
        state.load(fontScale)
    }

    LaunchedEffect(vaultId, relativePath, vault?.id) {
        reloadNote()
        val targetVault = vault ?: return@LaunchedEffect
        if (!targetVault.syncEnabled) return@LaunchedEffect

        val hashBefore = state.contentHash
        syncing = true
        try {
            withContext(Dispatchers.IO) {
                obsidianSyncManager.pullVaultIfEnabled(targetVault)
            }
        } finally {
            syncing = false
        }

        val noteFile = vault.let { resolveVaultNoteFile(it, relativePath) }
        if (noteFile != null) {
            state.file = noteFile
        }
        val hashAfter = withContext(Dispatchers.IO) {
            VaultFileStore.read(state.file)?.hash
        }
        when {
            hashBefore == null && hashAfter != null -> reloadNote()
            hashBefore != null && hashAfter != null && hashAfter != hashBefore -> {
                if (annotationMode) {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            text = "Newer version synced — exit annotate to reload",
                            duration = 5000
                        )
                    )
                } else if (editMode) {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            text = "Newer version synced — save or discard your edits",
                            duration = 5000
                        )
                    )
                } else {
                    reloadNote()
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Updated from sync", duration = 3000)
                    )
                }
            }
        }
    }

    LaunchedEffect(fontScale) {
        state.rerender(fontScale)
    }

    fun saveEdit() {
        val regions = editRegions ?: return
        val hash = state.contentHash ?: return
        val merged = VaultNoteEditModel.mergeAfterEdit(regions, draftBody)
        scope.launch(Dispatchers.IO) {
            when (val result = VaultFileStore.write(state.file, merged, expectedHash = hash)) {
                is VaultFileStore.WriteResult.Success -> {
                    withContext(Dispatchers.Main) {
                        exitEditMode()
                        state.load(fontScale)
                    }
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Note saved", duration = 2500)
                    )
                }
                is VaultFileStore.WriteResult.Conflict -> {
                    withContext(Dispatchers.Main) {
                        conflictContent = merged
                        conflictSource = NoteConflictSource.Edit
                    }
                }
                is VaultFileStore.WriteResult.Error -> {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Save failed: ${result.message}", duration = 5000)
                    )
                }
            }
        }
    }

    fun saveAnnotations() {
        val content = state.content ?: return
        val hash = state.contentHash
        val rendered = state.rendered ?: return
        val edits = annotationState.toEdits(rendered)
        if (edits.isEmpty()) {
            annotationState.clear()
            exitAnnotationMode()
            return
        }
        val newContent = MarkdownEdits.apply(content, edits)
        scope.launch(Dispatchers.IO) {
            when (val result = VaultFileStore.write(state.file, newContent, expectedHash = hash)) {
                is VaultFileStore.WriteResult.Success -> {
                    withContext(Dispatchers.Main) {
                        undoSnapshot = content to VaultFileStore.hashOf(newContent)
                        annotationState.clear()
                        exitAnnotationMode()
                        state.load(fontScale)
                    }
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Annotations saved — undo with ↺", duration = 3000)
                    )
                }
                is VaultFileStore.WriteResult.Conflict -> {
                    withContext(Dispatchers.Main) {
                        conflictContent = newContent
                        conflictSource = NoteConflictSource.Annotate
                    }
                }
                is VaultFileStore.WriteResult.Error -> {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Save failed: ${result.message}", duration = 5000)
                    )
                }
            }
        }
    }

    fun undoLastSave() {
        val (previousContent, expectedHash) = undoSnapshot ?: return
        scope.launch(Dispatchers.IO) {
            val onDisk = VaultFileStore.read(state.file)
            if (onDisk == null || onDisk.hash != expectedHash) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = "Note has changed since that save — cannot undo",
                        duration = 4000
                    )
                )
                withContext(Dispatchers.Main) { undoSnapshot = null }
                return@launch
            }
            when (val result = VaultFileStore.write(state.file, previousContent, onDisk.hash)) {
                is VaultFileStore.WriteResult.Success -> {
                    withContext(Dispatchers.Main) {
                        undoSnapshot = null
                        state.load(fontScale)
                    }
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Annotation save undone", duration = 2500)
                    )
                }
                else -> {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Undo failed: $result", duration = 4000)
                    )
                }
            }
        }
    }

    // Record this note in the per-vault recents list
    LaunchedEffect(relativePath, vaultId) {
        withContext(Dispatchers.IO) {
            val current = GlobalAppSettings.current
            val recents = current.recentNotesByVault[vaultId].orEmpty()
            val updated = (listOf(relativePath) + recents.filter { it != relativePath }).take(20)
            appRepository.kvProxy.setAppSettings(
                current.copy(recentNotesByVault = current.recentNotesByVault + (vaultId to updated))
            )
        }
    }

    fun retrySync() {
        val targetVault = vault ?: return
        scope.launch {
            val hashBefore = state.contentHash
            syncing = true
            try {
                withContext(Dispatchers.IO) {
                    obsidianSyncManager.pullVault(targetVault, showIndicator = false)
                }
            } finally {
                syncing = false
            }
            reloadNote()
            val hashAfter = state.contentHash
            if (hashAfter != null && hashAfter != hashBefore) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Updated from sync", duration = 3000)
                )
            }
        }
    }

    fun openLink(target: String, isWikilink: Boolean) {
        if (!isWikilink && (target.startsWith("http://") || target.startsWith("https://"))) {
            openInBrowser(context, target)
            return
        }
        val decoded = android.net.Uri.decode(target)
        // Markdown links are usually relative to the current note's folder — try that
        // first, then Obsidian-style vault-wide resolution (path, then unique name).
        val currentDir = relativePath.substringBeforeLast('/', "")
        val resolved = (if (currentDir.isNotEmpty() && !decoded.startsWith("/"))
            index?.resolveWikilink(normalizeVaultPath("$currentDir/$decoded")) else null)
            ?: index?.resolveWikilink(decoded.trimStart('/'))
        if (resolved != null) {
            onOpenNote(vaultId, resolved.relativePath)
        } else {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Note not found: $target", duration = 3000)
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier
                    .size(32.dp)
                    .noRippleClickable { requestBack() }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.file.name.removeSuffix(".md"),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (editMode) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = "Save",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { saveEdit() }
                )
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = "Discard edits",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(26.dp)
                        .noRippleClickable {
                            if (isEditDirty) showDiscardEditDialog = true else exitEditMode()
                        }
                )
            } else if (annotationMode) {
                // Annotation toolbar: pending count, undo, save, exit
                if (annotationState.pending.isNotEmpty()) {
                    Text(
                        "${annotationState.pending.size}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                Icon(
                    imageVector = FeatherIcons.RotateCcw,
                    contentDescription = "Undo mark",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { annotationState.undo() }
                )
                Icon(
                    imageVector = FeatherIcons.PenTool,
                    contentDescription = "Handwrite into note",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { onHandwriteInto(vaultId, relativePath) }
                )
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = "Save annotations",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { saveAnnotations() }
                )
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = "Discard annotations",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(26.dp)
                        .noRippleClickable {
                            annotationState.clear()
                            exitAnnotationMode()
                        }
                )
            } else {
                if (vault?.syncEnabled == true) {
                    ObsidianSyncIndicator(
                        syncing = syncing,
                        onClick = { retrySync() },
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (undoSnapshot != null) {
                    Icon(
                        imageVector = FeatherIcons.RotateCcw,
                        contentDescription = "Undo last annotation save",
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(26.dp)
                            .noRippleClickable { undoLastSave() }
                    )
                }
                Icon(
                    imageVector = FeatherIcons.Sliders,
                    contentDescription = "Text size",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { showFontControls = !showFontControls }
                )
                Icon(
                    imageVector = FeatherIcons.Edit2,
                    contentDescription = "Annotate",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { enterAnnotationMode() }
                )
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = "Edit text",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { enterEditMode() }
                )
                Icon(
                    imageVector = FeatherIcons.ExternalLink,
                    contentDescription = "Open in new window",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable {
                            DeepLinks.openNoteInNewWindow(context, relativePath)
                        }
                )
                Icon(
                    imageVector = FeatherIcons.Search,
                    contentDescription = "Quick open",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { showQuickSwitcher = true }
                )
                SingularityToggleIcon(
                    contentDescription = "Open drawing",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .noRippleClickable { onOpenFlipSide(vaultId, relativePath) }
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )

        if (showFontControls && !annotationMode && !editMode) {
            FontSizeControls(
                fontScale = fontScale,
                onDecrease = { adjustFontScale(-0.1f) },
                onIncrease = { adjustFontScale(0.1f) },
                onSaveDefault = { saveDefaultFontScale() },
                onReset = { fontScale = settings.readerFontScale },
                onClose = { showFontControls = false }
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.LightGray)
            )
        }

        val rendered = state.rendered
        when {
            editMode -> {
                NoteSourceEditor(
                    value = draftBody,
                    onValueChange = { draftBody = it },
                    showDrawingPreservedHint = editRegions?.isUnified == true,
                    modifier = Modifier.fillMaxSize()
                )
            }
            rendered != null -> {
                // Horizontal inset lives inside AnnotatableReaderBody so ink capture
                // reaches the screen edges; other children add their own inset.
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = !annotationMode)
                        .padding(vertical = 12.dp)
                ) {
                    if (rendered.frontmatter.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            FrontmatterBlock(rendered.frontmatter) { target ->
                                openLink(target, isWikilink = true)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    AnnotatableReaderBody(
                        rendered = rendered,
                        annotationMode = annotationMode,
                        annotationState = annotationState,
                        onLinkTap = { link -> openLink(link.target, link.isWikilink) },
                        onGestureRejected = {
                            SnackState.globalSnackFlow.tryEmit(
                                SnackConf(
                                    text = "Gesture not recognized — try circle, line, or scribble over text",
                                    duration = 2500
                                )
                            )
                        },
                        vaultRoot = vaultRoot,
                        noteRelativePath = relativePath
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
            state.error != null -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.error ?: "",
                        color = Color.Gray,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                    if (state.missingOnDisk && vault?.syncEnabled == true) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (syncing) "Syncing…" else "Retry sync",
                            color = Color.Black,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.noRippleClickable {
                                if (!syncing) retrySync()
                            }
                        )
                    }
                }
            }
            syncing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Syncing…", color = Color.Gray)
                }
            }
        }
    }

    if (showQuickSwitcher && index != null && vault != null) {
        QuickSwitcher(
            index = index,
            recentPaths = settings.recentNotesByVault[vault.id].orEmpty(),
            onSelect = { note ->
                showQuickSwitcher = false
                if (note.relativePath != relativePath) {
                    onOpenNote(vaultId, note.relativePath)
                }
            },
            onDismiss = { showQuickSwitcher = false }
        )
    }

    if (showDiscardEditDialog) {
        DiscardEditDialog(
            onDiscard = {
                showDiscardEditDialog = false
                exitEditMode()
            },
            onKeepEditing = { showDiscardEditDialog = false }
        )
    }

    val pendingConflict = conflictContent
    val pendingConflictSource = conflictSource
    if (pendingConflict != null && pendingConflictSource != null) {
        NoteConflictDialog(
            source = pendingConflictSource,
            onReload = {
                conflictContent = null
                conflictSource = null
                annotationState.clear()
                exitAnnotationMode()
                exitEditMode()
                state.load(fontScale)
            },
            onSaveCopy = {
                conflictContent = null
                conflictSource = null
                scope.launch(Dispatchers.IO) {
                    val copy = VaultFileStore.writeConflictCopy(state.file, pendingConflict)
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            text = if (copy != null) "Saved as ${copy.name}" else "Could not save conflict copy",
                            duration = 5000
                        )
                    )
                    withContext(Dispatchers.Main) {
                        annotationState.clear()
                        exitAnnotationMode()
                        exitEditMode()
                        state.load(fontScale)
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontSizeControls(
    fontScale: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSaveDefault: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Text size", fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.width(16.dp))
        FontSizeButton("A−", onClick = onDecrease, onLongClick = onSaveDefault)
        Spacer(Modifier.width(12.dp))
        Text(
            "${Math.round(fontScale * 100)}%",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp),
            color = Color.Black
        )
        FontSizeButton("A+", onClick = onIncrease, onLongClick = onSaveDefault)
        Spacer(Modifier.width(16.dp))
        FontSizeButton("Reset", onClick = onReset)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = FeatherIcons.X,
            contentDescription = "Close text size controls",
            modifier = Modifier
                .size(22.dp)
                .noRippleClickable { onClose() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontSizeButton(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        Modifier
            .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.noRippleClickable { onClick() }
                }
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Collapses "." and ".." segments in a vault-relative path. */
private fun normalizeVaultPath(path: String): String {
    val parts = mutableListOf<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(part)
        }
    }
    return parts.joinToString("/")
}

private enum class NoteConflictSource {
    Annotate,
    Edit,
}

@Composable
private fun DiscardEditDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit
) {
    Dialog(onDismissRequest = onKeepEditing) {
        Column(
            Modifier
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "Discard edits?",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "You have unsaved changes to this note.",
                fontSize = 14.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Box(
                    Modifier
                        .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                        .noRippleClickable(onKeepEditing)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Keep editing", fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(Color.Black, RoundedCornerShape(6.dp))
                        .noRippleClickable(onDiscard)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Discard", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun NoteConflictDialog(
    source: NoteConflictSource,
    onReload: () -> Unit,
    onSaveCopy: () -> Unit
) {
    val detail = when (source) {
        NoteConflictSource.Annotate ->
            "This note was modified outside the app while you were annotating. " +
                "Your annotations were not saved to avoid overwriting those changes."
        NoteConflictSource.Edit ->
            "This note was modified outside the app while you were editing. " +
                "Your changes were not saved to avoid overwriting those changes."
    }
    Dialog(onDismissRequest = onReload) {
        Column(
            Modifier
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "Note changed elsewhere",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(detail, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(Modifier.height(16.dp))
            Row {
                Box(
                    Modifier
                        .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                        .noRippleClickable { onSaveCopy() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Save conflict copy", fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(Color.Black, RoundedCornerShape(6.dp))
                        .noRippleClickable { onReload() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Reload note", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun FrontmatterBlock(
    frontmatter: Map<String, List<String>>,
    onWikilinkTap: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { expanded = !expanded }
        ) {
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Properties (${frontmatter.size})",
                fontSize = 13.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Medium
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            frontmatter.forEach { (key, values) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        key,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        modifier = Modifier.width(110.dp)
                    )
                    Column {
                        values.forEach { value ->
                            val wikiMatch = Regex("""^"?\[\[(.+?)]]"?$""").find(value.trim())
                            if (wikiMatch != null) {
                                Text(
                                    wikiMatch.groupValues[1],
                                    fontSize = 14.sp,
                                    color = Color.Black,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.noRippleClickable {
                                        onWikilinkTap(wikiMatch.groupValues[1])
                                    }
                                )
                            } else {
                                Text(value, fontSize = 14.sp, color = Color.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}
