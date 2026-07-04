package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.VaultTagScanner
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.VaultNote
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.QuickSwitcher
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Grid
import compose.icons.feathericons.List
import compose.icons.feathericons.Search
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VaultBrowserDestination : NavigationDestination {
    override val route = "vaultbrowser"
    const val DIR_ARG = "dir"
    val routeWithArgs = "$route?$DIR_ARG={$DIR_ARG}"
    fun createRoute(dir: String?) =
        if (dir.isNullOrBlank()) route else "$route?$DIR_ARG=${android.net.Uri.encode(dir)}"
}

/** File sort orders for the vault browser. */
object VaultSort {
    const val NAME_ASC = "name"
    const val NAME_DESC = "nameDesc"
    const val NEWEST = "newest"
    const val OLDEST = "oldest"

    fun label(mode: String): String = when (mode) {
        NAME_DESC -> "Name Z–A"
        NEWEST -> "Newest first"
        OLDEST -> "Oldest first"
        else -> "Name A–Z"
    }

    fun apply(files: List<VaultNote>, mode: String): List<VaultNote> = when (mode) {
        NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
        NEWEST -> files.sortedByDescending { it.lastModified }
        OLDEST -> files.sortedBy { it.lastModified }
        else -> files.sortedBy { it.name.lowercase() }
    }
}

/**
 * Full-screen vault browser (navigation route, also the notable://vault/browse
 * deep-link target).
 */
@Composable
fun VaultBrowserView(
    dir: String?,
    appRepository: AppRepository,
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        VaultBrowserContent(
            initialDir = dir.orEmpty(),
            appRepository = appRepository,
            onOpenNote = onOpenNote,
            onClose = onBack,
            isModal = false
        )
    }
}

/**
 * The vault browser as a modal over the current screen (Obsidian-style quick file
 * access). Near-full-screen on e-ink: big tap targets, fewer refresh artifacts.
 */
@Composable
fun VaultBrowserModal(
    appRepository: AppRepository,
    onOpenNote: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .border(2.dp, Color.Black, RoundedCornerShape(10.dp))
                .background(Color.White, RoundedCornerShape(10.dp))
        ) {
            VaultBrowserContent(
                initialDir = "",
                appRepository = appRepository,
                onOpenNote = onOpenNote,
                onClose = onDismiss,
                isModal = true
            )
        }
    }
}

/**
 * Shared browser UI: vault switcher, quick-open search, sort menu, list ⇄ grid view
 * toggle, and a "Handwritten" thumbnail filter for ink-bearing notes. Folder
 * navigation is internal — back walks up the tree, then closes.
 */
@Composable
private fun VaultBrowserContent(
    initialDir: String,
    appRepository: AppRepository,
    onOpenNote: (String) -> Unit,
    onClose: () -> Unit,
    isModal: Boolean
) {
    val scope = rememberCoroutineScope()
    val settings = GlobalAppSettings.current
    val activeVault = settings.activeVault
    val index = activeVault?.let { VaultIndexRegistry.forVault(it) }

    var currentDir by remember { mutableStateOf(initialDir) }
    var showVaultPicker by remember { mutableStateOf(false) }
    var showQuickSwitcher by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var handwrittenOnly by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val sortMode = settings.vaultSortMode
    val gridView = settings.vaultBrowserGrid

    fun updateSettings(update: (com.ethran.notable.data.datastore.AppSettings) -> com.ethran.notable.data.datastore.AppSettings) {
        scope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(update(GlobalAppSettings.current))
        }
    }

    fun switchVault(vault: VaultConfig) {
        currentDir = ""
        updateSettings { it.copy(activeVaultId = vault.id) }
        scope.launch(Dispatchers.IO) { VaultTagScanner.refreshCache(vault.inboxPath) }
    }

    fun goBack() {
        when {
            handwrittenOnly -> handwrittenOnly = false
            currentDir.isNotEmpty() -> currentDir = currentDir.substringBeforeLast('/', "")
            else -> onClose()
        }
    }

    val entries = remember(index, currentDir, handwrittenOnly, sortMode, refreshTick, activeVault?.id) {
        when {
            index == null -> emptyList()
            handwrittenOnly -> VaultSort.apply(index.refresh().filter { it.hasInk }, sortMode)
            else -> {
                val (folders, files) = index.listDir(currentDir).partition { it.isFolder }
                folders + VaultSort.apply(files, sortMode)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header: back/close, vault switcher, search, sort, view toggles
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isModal && currentDir.isEmpty() && !handwrittenOnly)
                    FeatherIcons.X else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier
                    .size(30.dp)
                    .noRippleClickable { goBack() }
            )
            Spacer(Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .noRippleClickable { showVaultPicker = true }
            ) {
                Text(
                    text = activeVault?.displayName ?: "No vault configured",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = "Switch vault",
                    modifier = Modifier.size(22.dp)
                )
            }
            Icon(
                imageVector = FeatherIcons.Search,
                contentDescription = "Quick open",
                modifier = Modifier
                    .padding(horizontal = 7.dp)
                    .size(26.dp)
                    .noRippleClickable { showQuickSwitcher = true }
            )
            Icon(
                imageVector = FeatherIcons.Sliders,
                contentDescription = "Sort",
                modifier = Modifier
                    .padding(horizontal = 7.dp)
                    .size(24.dp)
                    .noRippleClickable { showSortMenu = true }
            )
            Icon(
                imageVector = if (gridView) FeatherIcons.List else FeatherIcons.Grid,
                contentDescription = if (gridView) "List view" else "Grid view",
                modifier = Modifier
                    .padding(horizontal = 7.dp)
                    .size(24.dp)
                    .noRippleClickable { updateSettings { s -> s.copy(vaultBrowserGrid = !s.vaultBrowserGrid) } }
            )
            Icon(
                imageVector = FeatherIcons.Edit3,
                contentDescription = "Handwritten notes",
                tint = if (handwrittenOnly) Color.Black else Color.Gray,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .size(24.dp)
                    .noRippleClickable {
                        handwrittenOnly = !handwrittenOnly
                        refreshTick++
                    }
            )
        }

        // Breadcrumb for subfolders
        if (currentDir.isNotEmpty() && !handwrittenOnly) {
            Text(
                text = currentDir.replace("/", "  ›  "),
                style = MaterialTheme.typography.caption,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray)
        )

        when {
            activeVault == null || index == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Configure a vault in Settings to browse your notes.",
                        color = Color.Gray
                    )
                }
            }
            handwrittenOnly -> HandwrittenGrid(entries, onOpenNote)
            gridView -> NoteGrid(entries, onOpenNote, onOpenDir = { currentDir = it })
            else -> NoteList(entries, onOpenNote, onOpenDir = { currentDir = it })
        }
    }

    if (showVaultPicker) {
        VaultPickerDialog(
            vaults = settings.vaults,
            activeVaultId = settings.activeVaultId,
            onSelect = { vault ->
                showVaultPicker = false
                switchVault(vault)
            },
            onDismiss = { showVaultPicker = false }
        )
    }

    if (showSortMenu) {
        SortMenuDialog(
            current = sortMode,
            onSelect = { mode ->
                showSortMenu = false
                updateSettings { s -> s.copy(vaultSortMode = mode) }
            },
            onDismiss = { showSortMenu = false }
        )
    }

    if (showQuickSwitcher && index != null && activeVault != null) {
        QuickSwitcher(
            index = index,
            recentPaths = settings.recentNotesByVault[activeVault.id].orEmpty(),
            onSelect = { note ->
                showQuickSwitcher = false
                onOpenNote(note.relativePath)
            },
            onDismiss = { showQuickSwitcher = false }
        )
    }
}

@Composable
private fun SortMenuDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val modes = listOf(VaultSort.NAME_ASC, VaultSort.NAME_DESC, VaultSort.NEWEST, VaultSort.OLDEST)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("Sort by", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            modes.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { onSelect(mode) }
                        .padding(vertical = 10.dp)
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .border(1.dp, Color.Black, RoundedCornerShape(7.dp))
                            .background(
                                if (mode == current) Color.Black else Color.White,
                                RoundedCornerShape(7.dp)
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(VaultSort.label(mode), fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun NoteList(
    entries: List<VaultNote>,
    onOpenNote: (String) -> Unit,
    onOpenDir: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.relativePath }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.isFolder) onOpenDir(entry.relativePath)
                        else onOpenNote(entry.relativePath)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = when {
                        entry.isFolder -> FeatherIcons.Folder
                        entry.hasInk -> FeatherIcons.Edit3
                        else -> FeatherIcons.FileText
                    },
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry.name,
                    fontSize = 17.sp,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!entry.isFolder) {
                    Text(
                        text = dateFormat.format(Date(entry.lastModified)),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(Color(0xFFEEEEEE))
            )
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing here.",
                    color = Color.Gray,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

/** Multi-column compact card view of folders and notes. */
@Composable
private fun NoteGrid(
    entries: List<VaultNote>,
    onOpenNote: (String) -> Unit,
    onOpenDir: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        items(entries, key = { it.relativePath }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                    .clickable {
                        if (entry.isFolder) onOpenDir(entry.relativePath)
                        else onOpenNote(entry.relativePath)
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = when {
                        entry.isFolder -> FeatherIcons.Folder
                        entry.hasInk -> FeatherIcons.Edit3
                        else -> FeatherIcons.FileText
                    },
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.isFolder) {
                        Text(
                            text = dateFormat.format(Date(entry.lastModified)),
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing here.",
                    color = Color.Gray,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** Grid of ink-bearing notes (notes with a flip side). */
@Composable
private fun HandwrittenGrid(
    entries: List<VaultNote>,
    onOpenNote: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        items(entries, key = { it.relativePath }) { note ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .border(1.dp, Color.Black, RectangleShape)
                        .noRippleClickable { onOpenNote(note.relativePath) }
                ) {
                    Icon(
                        imageVector = FeatherIcons.Edit3,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    note.name,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black
                )
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "No handwritten notes yet. Notes with a flip side appear here.",
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun VaultPickerDialog(
    vaults: List<VaultConfig>,
    activeVaultId: String,
    onSelect: (VaultConfig) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("Switch vault", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            vaults.forEach { vault ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { onSelect(vault) }
                        .padding(vertical = 10.dp)
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .border(1.dp, Color.Black, RoundedCornerShape(7.dp))
                            .background(
                                if (vault.id == activeVaultId) Color.Black else Color.White,
                                RoundedCornerShape(7.dp)
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(vault.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(vault.inboxPath, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            if (vaults.isEmpty()) {
                Text("No vaults registered. Add one in Settings.", color = Color.Gray)
            }
        }
    }
}
