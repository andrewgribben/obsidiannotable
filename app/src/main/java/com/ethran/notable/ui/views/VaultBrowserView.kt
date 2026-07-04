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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
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

/**
 * Compact list/tree browser of the active vault's markdown notes, with a vault
 * switcher in the header and a "Handwritten" thumbnail-style filter for ink-bearing notes.
 */
@Composable
fun VaultBrowserView(
    dir: String?,
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
    onOpenDir: (String) -> Unit,
    onVaultSwitched: (VaultConfig) -> Unit
) {
    val settings = GlobalAppSettings.current
    val activeVault = settings.activeVault
    val index = activeVault?.let { VaultIndexRegistry.forVault(it) }

    var showVaultPicker by remember { mutableStateOf(false) }
    var showQuickSwitcher by remember { mutableStateOf(false) }
    var handwrittenOnly by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val entries = remember(index, dir, handwrittenOnly, refreshTick) {
        when {
            index == null -> emptyList()
            handwrittenOnly -> index.refresh().filter { it.hasInk }
            else -> index.listDir(dir.orEmpty())
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // Header: back, vault switcher, search, view toggle
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
                    .padding(horizontal = 8.dp)
                    .size(26.dp)
                    .noRippleClickable { showQuickSwitcher = true }
            )
            Icon(
                imageVector = if (handwrittenOnly) FeatherIcons.List else FeatherIcons.Grid,
                contentDescription = "Toggle handwritten view",
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(26.dp)
                    .noRippleClickable {
                        handwrittenOnly = !handwrittenOnly
                        refreshTick++
                    }
            )
        }

        // Breadcrumb for subfolders
        if (!dir.isNullOrBlank() && !handwrittenOnly) {
            Text(
                text = dir.replace("/", "  ›  "),
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
            else -> NoteList(entries, onOpenNote, onOpenDir)
        }
    }

    if (showVaultPicker) {
        VaultPickerDialog(
            vaults = settings.vaults,
            activeVaultId = settings.activeVaultId,
            onSelect = { vault ->
                showVaultPicker = false
                onVaultSwitched(vault)
            },
            onDismiss = { showVaultPicker = false }
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
