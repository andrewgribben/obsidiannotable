package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.ethran.notable.io.vault.NoteEditGuard
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.vaultRootDir
import com.ethran.notable.navigation.DeepLinks
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.io.markdown.MarkdownEdits
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.AnnotatableReaderBody
import com.ethran.notable.ui.components.QuickSwitcher
import com.ethran.notable.ui.components.ReaderAnnotationState
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Layers
import compose.icons.feathericons.PenTool
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
    const val PATH_ARG = "path"
    val routeWithArgs = "$route?$PATH_ARG={$PATH_ARG}"
    fun createRoute(relativePath: String) =
        "$route?$PATH_ARG=${android.net.Uri.encode(relativePath)}"
}

/** Loaded state of a note in the reader. */
class NoteReaderState(
    val file: File,
    val relativePath: String
) {
    var content by mutableStateOf<String?>(null)
    var contentHash by mutableStateOf<String?>(null)
    var rendered by mutableStateOf<RenderedMarkdown?>(null)
    var error by mutableStateOf<String?>(null)

    fun load() {
        val result = VaultFileStore.read(file)
        if (result == null) {
            error = "Could not read ${file.name}"
            return
        }
        content = result.content
        contentHash = result.hash
        rendered = MarkdownRenderer.render(result.content)
        error = null
    }
}

/**
 * Vault note reader: Bear-style rendered markdown with tappable wikilinks and
 * markdown links, frontmatter as a collapsible properties block.
 */
@Composable
fun NoteReaderView(
    relativePath: String,
    appRepository: AppRepository,
    onOpenNote: (String) -> Unit,
    onOpenFlipSide: (String) -> Unit,
    onHandwriteInto: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = GlobalAppSettings.current
    val activeVault = settings.activeVault
    val vaultRoot = activeVault?.let { vaultRootDir(it) }
    val index = activeVault?.let { VaultIndexRegistry.forVault(it) }

    var showQuickSwitcher by remember { mutableStateOf(false) }
    var annotationMode by remember { mutableStateOf(false) }
    var conflictContent by remember { mutableStateOf<String?>(null) }
    val annotationState = remember(relativePath) { ReaderAnnotationState() }

    // Single-writer guard: only one window/surface may edit this note at a time.
    val editOwner = remember { UUID.randomUUID().toString() }
    val guardKey = activeVault?.let { NoteEditGuard.noteKey(it.id, relativePath) }

    fun enterAnnotationMode() {
        if (guardKey != null && !NoteEditGuard.tryAcquire(guardKey, editOwner)) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Note is being edited in another window", duration = 4000)
            )
            return
        }
        annotationMode = true
    }

    fun exitAnnotationMode() {
        annotationMode = false
        guardKey?.let { NoteEditGuard.release(it, editOwner) }
    }

    DisposableEffect(guardKey) {
        onDispose { guardKey?.let { NoteEditGuard.release(it, editOwner) } }
    }

    val state = remember(relativePath, vaultRoot) {
        NoteReaderState(
            file = File(vaultRoot, relativePath.replace('/', File.separatorChar)),
            relativePath = relativePath
        ).also { it.load() }
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
                        annotationState.clear()
                        exitAnnotationMode()
                        state.load()
                    }
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Annotations saved", duration = 2000)
                    )
                }
                is VaultFileStore.WriteResult.Conflict -> {
                    withContext(Dispatchers.Main) { conflictContent = newContent }
                }
                is VaultFileStore.WriteResult.Error -> {
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(text = "Save failed: ${result.message}", duration = 5000)
                    )
                }
            }
        }
    }

    // Record this note in the per-vault recents list
    LaunchedEffect(relativePath, activeVault?.id) {
        val vaultId = activeVault?.id ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val current = GlobalAppSettings.current
            val recents = current.recentNotesByVault[vaultId].orEmpty()
            val updated = (listOf(relativePath) + recents.filter { it != relativePath }).take(20)
            appRepository.kvProxy.setAppSettings(
                current.copy(recentNotesByVault = current.recentNotesByVault + (vaultId to updated))
            )
        }
    }

    fun openLink(target: String, isWikilink: Boolean) {
        if (!isWikilink && (target.startsWith("http://") || target.startsWith("https://"))) {
            openInBrowser(context, target)
            return
        }
        val resolved = index?.resolveWikilink(android.net.Uri.decode(target))
        if (resolved != null) {
            onOpenNote(resolved.relativePath)
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
                    .noRippleClickable { onBack() }
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
            if (annotationMode) {
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
                Icon(
                    imageVector = FeatherIcons.Edit2,
                    contentDescription = "Annotate",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { enterAnnotationMode() }
                )
                Icon(
                    imageVector = FeatherIcons.PenTool,
                    contentDescription = "Handwrite into note",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(26.dp)
                        .noRippleClickable { onHandwriteInto(relativePath) }
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
                Icon(
                    imageVector = FeatherIcons.Layers,
                    contentDescription = "Flip side",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(26.dp)
                        .noRippleClickable { onOpenFlipSide(relativePath) }
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )

        val rendered = state.rendered
        when {
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "", color = Color.Gray)
                }
            }
            rendered != null -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = !annotationMode)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (rendered.frontmatter.isNotEmpty()) {
                        FrontmatterBlock(rendered.frontmatter) { target ->
                            openLink(target, isWikilink = true)
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
                        }
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    if (showQuickSwitcher && index != null && activeVault != null) {
        QuickSwitcher(
            index = index,
            recentPaths = settings.recentNotesByVault[activeVault.id].orEmpty(),
            onSelect = { note ->
                showQuickSwitcher = false
                if (note.relativePath != relativePath) onOpenNote(note.relativePath)
            },
            onDismiss = { showQuickSwitcher = false }
        )
    }

    val pendingConflict = conflictContent
    if (pendingConflict != null) {
        AnnotationConflictDialog(
            onReload = {
                conflictContent = null
                annotationState.clear()
                exitAnnotationMode()
                state.load()
            },
            onSaveCopy = {
                conflictContent = null
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
                        state.load()
                    }
                }
            }
        )
    }
}

@Composable
private fun AnnotationConflictDialog(
    onReload: () -> Unit,
    onSaveCopy: () -> Unit
) {
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
            Text(
                "This note was modified outside the app (e.g. by Obsidian Sync) while you were annotating. " +
                        "Your annotations were not saved to avoid overwriting those changes.",
                fontSize = 14.sp,
                color = Color.DarkGray
            )
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
