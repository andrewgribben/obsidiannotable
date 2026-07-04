package com.ethran.notable.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.VaultTagScanner
import com.ethran.notable.io.isAttachmentPathSet
import com.ethran.notable.io.pathFromTreeUri


@Composable
fun GeneralSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onClearAllPages: ((onComplete: () -> Unit) -> Unit)? = null
) {
    Column {
        // Capture settings
        InboxCaptureSettings(settings, onSettingsChange)

        Spacer(modifier = Modifier.height(8.dp))

        SelectorRow(
            label = stringResource(R.string.toolbar_position), options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(
                    R.string.toolbar_position_bottom
                )
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                onSettingsChange(settings.copy(toolbarPosition = newPosition))
            })

        SettingToggleRow(
            label = stringResource(R.string.use_onyx_neotools_may_cause_crashes),
            value = settings.neoTools,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(neoTools = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_scribble_to_erase),
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_smooth_scrolling),
            value = settings.smoothScroll,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(smoothScroll = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.continuous_zoom),
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.continuous_stroke_slider),
            value = settings.continuousStrokeSlider,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousStrokeSlider = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.monochrome_mode) + " " + stringResource(R.string.work_in_progress),
            value = settings.monochromeMode,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(monochromeMode = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.paginate_pdf),
            value = settings.paginatePdf,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(paginatePdf = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.preview_pdf_pagination),
            value = settings.visualizePdfPagination,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(visualizePdfPagination = isChecked))
            })

        if (onClearAllPages != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ClearAllPagesButton(onClearAllPages)
        }
    }
}

@Composable
private fun ClearAllPagesButton(onClearAllPages: (onComplete: () -> Unit) -> Unit) {
    var confirmState by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    if (isClearing) {
        Text(
            "Clearing...",
            style = MaterialTheme.typography.body1,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    } else if (confirmState) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Delete all pages, notebooks, and folders?",
                style = MaterialTheme.typography.body1,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .clickable {
                        isClearing = true
                        onClearAllPages {
                            isClearing = false
                            confirmState = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Yes, delete all", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .clickable { confirmState = false }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Cancel", fontSize = 14.sp)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .border(1.dp, Color.Red, RoundedCornerShape(6.dp))
                .clickable { confirmState = true }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Clear all pages", color = Color.Red, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InboxCaptureSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val normalized = settings.normalizedVaults()
    val vaults = normalized.vaults
    var expandedVaultId by remember { mutableStateOf<String?>(null) }

    // Single SAF launcher shared by all vault rows; pendingPick tracks the target.
    var pendingPick by remember { mutableStateOf<Pair<String, VaultPickTarget>?>(null) }
    val persistFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val pick = pendingPick
            pendingPick = null
            if (uri == null || pick == null) return@rememberLauncherForActivityResult
            context.contentResolver.takePersistableUriPermission(uri, persistFlags)
            val path = pathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
            val updated = vaults.map { vault ->
                if (vault.id != pick.first) vault
                else when (pick.second) {
                    VaultPickTarget.Inbox -> vault.copy(inboxPath = path)
                    VaultPickTarget.Attachment -> vault.copy(attachmentPath = path)
                }
            }
            onSettingsChange(normalized.copy(vaults = updated))
            if (pick.first == normalized.activeVaultId && pick.second == VaultPickTarget.Inbox) {
                VaultTagScanner.refreshCache(path)
            }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Vaults",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Register your Obsidian vaults. The active vault receives handwritten captures and exports, " +
                    "and is the one you browse and read. The first vault also stores the app's database.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        vaults.forEachIndexed { index, vault ->
            VaultRow(
                vault = vault,
                isActive = vault.id == normalized.activeVaultId,
                isPrimary = index == 0,
                isExpanded = vault.id == expandedVaultId,
                onActivate = {
                    onSettingsChange(normalized.copy(activeVaultId = vault.id))
                    VaultTagScanner.refreshCache(vault.inboxPath)
                },
                onToggleExpand = {
                    expandedVaultId = if (expandedVaultId == vault.id) null else vault.id
                },
                onChange = { changed ->
                    val cleaned = changed.copy(
                        attachmentPath = changed.attachmentPath.trim()
                            .takeIf { isAttachmentPathSet(it) }.orEmpty()
                    )
                    onSettingsChange(normalized.copy(vaults = vaults.map { v ->
                        if (v.id == vault.id) cleaned else v
                    }))
                },
                onPickInbox = {
                    pendingPick = vault.id to VaultPickTarget.Inbox
                    folderPicker.launch(null)
                },
                onPickAttachment = {
                    pendingPick = vault.id to VaultPickTarget.Attachment
                    folderPicker.launch(null)
                },
                onRemove = if (index == 0) null else {
                    {
                        val remaining = vaults.filter { it.id != vault.id }
                        val newActive =
                            if (normalized.activeVaultId == vault.id) remaining.first().id
                            else normalized.activeVaultId
                        onSettingsChange(
                            normalized.copy(vaults = remaining, activeVaultId = newActive)
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier
                .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                .clickable {
                    val newVault = VaultConfig(name = "New vault")
                    onSettingsChange(normalized.copy(vaults = vaults + newVault))
                    expandedVaultId = newVault.id
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("+ Add vault", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }

    SettingsDivider()
}

private enum class VaultPickTarget { Inbox, Attachment }

@Composable
private fun VaultRow(
    vault: VaultConfig,
    isActive: Boolean,
    isPrimary: Boolean,
    isExpanded: Boolean,
    onActivate: () -> Unit,
    onToggleExpand: () -> Unit,
    onChange: (VaultConfig) -> Unit,
    onPickInbox: () -> Unit,
    onPickAttachment: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val focusManager = LocalFocusManager.current
    var nameInput by remember(vault.id) { mutableStateOf(vault.name) }
    var inboxInput by remember(vault.id, vault.inboxPath) { mutableStateOf(vault.inboxPath) }
    var attachmentInput by remember(vault.id, vault.attachmentPath) {
        mutableStateOf(vault.attachmentPath)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) Color.Black else Color.LightGray,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active selector
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Black, RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) Color.Black else Color.White,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { if (!isActive) onActivate() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (isActive) "Active" else "Activate",
                    color = if (isActive) Color.White else Color.Black,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    vault.displayName + if (isPrimary) "  (primary)" else "",
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    vault.inboxPath.ifBlank { "No inbox folder set" },
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
            }
            Box(
                modifier = Modifier
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (isExpanded) "Done" else "Edit", fontSize = 13.sp)
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(10.dp))

            Text("Name", style = MaterialTheme.typography.caption, color = Color.Gray)
            BasicTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                singleLine = true,
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onChange(vault.copy(name = nameInput.trim()))
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("Inbox folder", style = MaterialTheme.typography.caption, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = inboxInput,
                    onValueChange = { inboxInput = it },
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.Black),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onChange(vault.copy(inboxPath = inboxInput.trim()))
                        VaultTagScanner.refreshCache(inboxInput.trim())
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                        .clickable { onPickInbox() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Browse", fontSize = 13.sp) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Attachment folder (blank = next to note)",
                style = MaterialTheme.typography.caption,
                color = Color.Gray
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = attachmentInput,
                    onValueChange = { attachmentInput = it },
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.Black),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onChange(vault.copy(attachmentPath = attachmentInput.trim()))
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                        .clickable { onPickAttachment() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Browse", fontSize = 13.sp) }
            }

            if (onRemove != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, Color.Red, RoundedCornerShape(6.dp))
                        .clickable { onRemove() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Remove vault", color = Color.Red, fontSize = 13.sp)
                }
            } else if (isPrimary) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "The primary vault stores the app database and cannot be removed.",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
            }
        }
    }
}
