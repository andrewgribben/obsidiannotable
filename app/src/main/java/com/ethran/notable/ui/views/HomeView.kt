package com.ethran.notable.ui.views

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.vault.BookshelfKind
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.NotebookCard
import com.ethran.notable.ui.components.CaptureCoverPreview
import com.ethran.notable.ui.components.ObsidianSyncIndicator
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.QuickSwitcher
import com.ethran.notable.ui.components.ShowPagesRow
import com.ethran.notable.ui.components.VaultEntryMenuItem
import com.ethran.notable.ui.components.VaultEntryPopupMenu
import com.ethran.notable.ui.dialogs.CaptureRenameDialog
import com.ethran.notable.ui.dialogs.EmptyBookWarningHandler
import com.ethran.notable.ui.dialogs.FolderConfigDialog
import com.ethran.notable.ui.dialogs.NotebookConfigDialog
import com.ethran.notable.ui.dialogs.PdfImportChoiceDialog
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.HomeCaptureItem
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.LibraryViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.ShipBook
import java.text.SimpleDateFormat
import java.util.Locale


object LibraryDestination : NavigationDestination {
    override val route = "library"
    const val FOLDER_ID_ARG = "folderId"
    val routeWithArgs = "$route?$FOLDER_ID_ARG={$FOLDER_ID_ARG}"
    fun createRoute(folderId: String? = null): String {
        return if (folderId != null) "$route?$FOLDER_ID_ARG=$folderId" else route
    }
}

private val log = ShipBook.getLogger("HomeView")

@Composable
fun Library(
    navController: NavController,
    folderId: String? = null,
    goToPage: (String) -> Unit = {},
    onCreateNewCapture: (String) -> Unit = {},
    onOpenFlipSide: (String, String) -> Unit = { _, _ -> },
    onOpenVaultNote: (String, String) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncState by viewModel.obsidianSyncState.collectAsStateWithLifecycle()

    LaunchedEffect(folderId) {
        viewModel.loadFolder(folderId)
    }

    LibraryContent(
        appRepository = viewModel.appRepository,
        exportEngine = viewModel.exportEngine,
        uiState = uiState,
        onNavigateToFolder = { id -> navController.navigate(LibraryDestination.createRoute(id)) },
        onNavigateToSettings = { navController.navigate("settings") },
        onOpenVaultNote = onOpenVaultNote,
        onNavigateToEditor = { pageId, bookId ->
            navController.navigate(EditorDestination.createRoute(pageId, bookId))
        },
        goToPage = goToPage,
        onCreateNewCapture = onCreateNewCapture,
        onOpenFlipSide = onOpenFlipSide,
        onTogglePin = viewModel::togglePin,
        onArchiveFromHome = viewModel::archiveFromHome,
        onOpenBookshelfFolder = viewModel::openBookshelfFolder,
        onCloseBookshelfFolder = viewModel::closeBookshelfFolder,
        onAddToBookshelf = viewModel::addToBookshelf,
        onRenameCapture = viewModel::renameCapture,
        onSetCaptureCover = viewModel::setCaptureCover,
        onRemoveCaptureCover = viewModel::removeCaptureCover,
        onDeleteVaultEntry = viewModel::deleteVaultEntry,
        onSetHomeGridOptions = viewModel::setHomeGridOptions,
        onCreateNewFolder = viewModel::createNewFolder,
        onDeleteEmptyBook = viewModel::deleteEmptyBook,
        onCreateNewNotebook = viewModel::onCreateNewNotebook,
        onImportPdf = viewModel::onPdfFile,
        onImportXopp = viewModel::onXoppFile,
        obsidianSyncing = syncState.syncing,
        onSyncVaults = viewModel::syncObsidianVaults
    )
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryContent(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    uiState: LibraryUiState,
    onNavigateToFolder: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenVaultNote: (String, String) -> Unit = { _, _ -> },
    onNavigateToEditor: (String, String) -> Unit,
    goToPage: (String) -> Unit,
    onCreateNewCapture: (String) -> Unit,
    onOpenFlipSide: (String, String) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchiveFromHome: (String, String) -> Unit,
    onOpenBookshelfFolder: (String, String) -> Unit,
    onCloseBookshelfFolder: () -> Unit,
    onAddToBookshelf: (String, String, BookshelfKind) -> Unit,
    onRenameCapture: (String, String, String) -> Unit,
    onSetCaptureCover: (String, String, Uri) -> Unit,
    onRemoveCaptureCover: (String, String) -> Unit,
    onDeleteVaultEntry: (String, String, Boolean) -> Unit,
    onSetHomeGridOptions: (String, Set<String>) -> Unit,
    onCreateNewFolder: () -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    obsidianSyncing: Boolean = false,
    onSyncVaults: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = GlobalAppSettings.current
    val activeVault = settings.activeVault
    val vaults = settings.normalizedVaults().vaults
    val multipleVaults = vaults.size > 1
    val bookshelfDirs = settings.homeBookshelfDirByVault
    val inBookshelfFolder = bookshelfDirs.values.any { it.isNotBlank() }
    val index = activeVault?.let { VaultIndexRegistry.forVault(it) }
    var showVaultBrowser by remember { mutableStateOf(false) }
    var showQuickSwitcher by remember { mutableStateOf(false) }
    var showHomeGridOptions by remember { mutableStateOf(false) }
    var showCreateVaultPicker by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<HomeCaptureItem?>(null) }
    var coverPickTarget by remember { mutableStateOf<HomeCaptureItem?>(null) }
    var pendingDelete by remember { mutableStateOf<HomeCaptureItem?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        contract = PickVisualMedia()
    ) { uri ->
        val target = coverPickTarget
        coverPickTarget = null
        if (uri != null && target != null) {
            onSetCaptureCover(target.vaultId, target.note.relativePath, uri)
        }
    }
    val sortMode = settings.homeSortMode
    val vaultFilterIds = settings.homeVaultFilterIds

    fun onNewCaptureClick() {
        if (vaults.isEmpty()) return
        if (vaults.size == 1) {
            onCreateNewCapture(vaults.first().id)
        } else {
            showCreateVaultPicker = true
        }
    }

    BackHandler(enabled = inBookshelfFolder) {
        onCloseBookshelfFolder()
    }

    Column(Modifier.fillMaxSize()) {
        // Slim header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Singularity",
                style = androidx.compose.material.MaterialTheme.typography.h5,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Icon(
                imageVector = FeatherIcons.Search, contentDescription = "Quick open",
                Modifier
                    .padding(8.dp)
                    .noRippleClickable { showQuickSwitcher = true }
            )
            Icon(
                imageVector = FeatherIcons.BookOpen, contentDescription = "Vault",
                Modifier
                    .padding(8.dp)
                    .noRippleClickable { showVaultBrowser = true }
            )
            Icon(
                imageVector = FeatherIcons.Sliders, contentDescription = "Home grid options",
                Modifier
                    .padding(8.dp)
                    .noRippleClickable { showHomeGridOptions = true }
            )
            ObsidianSyncIndicator(
                syncing = obsidianSyncing,
                onClick = onSyncVaults
            )
            Icon(
                imageVector = FeatherIcons.Settings, contentDescription = "Settings",
                Modifier
                    .padding(8.dp)
                    .noRippleClickable(onClick = onNavigateToSettings)
            )
        }

        // Capture grid: unified inbox Excalidraw captures.
        val captures = uiState.homeCaptures
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .autoEInkAnimationOnScroll()
        ) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .aspectRatio(3f / 4f)
                        .border(2.dp, Color.Black, RectangleShape)
                        .noRippleClickable(onClick = { onNewCaptureClick() })
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FilePlus,
                            contentDescription = "New Capture",
                            tint = Color.Black,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "New Capture",
                            style = androidx.compose.material.MaterialTheme.typography.body2,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            if (inBookshelfFolder) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .aspectRatio(3f / 4f)
                            .border(2.dp, Color.Gray, RectangleShape)
                            .noRippleClickable(onClick = onCloseBookshelfFolder)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.ArrowUp,
                                contentDescription = "Close folder",
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                "Close folder",
                                style = androidx.compose.material.MaterialTheme.typography.body2,
                                color = Color.DarkGray
                            )
                        }
                    }
                }
            }

            items(captures, key = { item -> "vault:${item.vaultId}:${item.note.relativePath}" }) { item ->
                VaultCaptureCard(
                    item = item,
                    isPinned = item.isPinned,
                    hasCover = item.coverImagePath != null,
                    showVaultName = multipleVaults,
                    onOpenFlipSide = onOpenFlipSide,
                    onOpenBookshelfFolder = onOpenBookshelfFolder,
                    onOpenText = { vaultId, path -> onOpenVaultNote(vaultId, path) },
                    onTogglePin = onTogglePin,
                    onArchive = onArchiveFromHome,
                    onRename = { renameTarget = item },
                    onSetCover = {
                        coverPickTarget = item
                        coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    },
                    onRemoveCover = {
                        onRemoveCaptureCover(item.vaultId, item.note.relativePath)
                    },
                    onDelete = { pendingDelete = item }
                )
            }
        }
    }

    pendingDelete?.let { item ->
        val message = if (item.isFolder) {
            "Delete folder \"${item.note.name}\" and everything inside? This cannot be undone."
        } else {
            "Delete \"${item.note.name}\"? This cannot be undone."
        }
        ShowSimpleConfirmationDialog(
            title = "Delete",
            message = message,
            confirmButtonText = "Delete",
            onConfirm = {
                onDeleteVaultEntry(item.vaultId, item.note.relativePath, item.isFolder)
                pendingDelete = null
            },
            onCancel = { pendingDelete = null }
        )
    }

    renameTarget?.let { item ->
        CaptureRenameDialog(
            currentName = item.note.name,
            onConfirm = { newName ->
                renameTarget = null
                onRenameCapture(item.vaultId, item.note.relativePath, newName)
            },
            onDismiss = { renameTarget = null }
        )
    }

    if (showHomeGridOptions) {
        HomeGridOptionsDialog(
            vaults = vaults,
            currentSort = sortMode,
            vaultFilterIds = vaultFilterIds,
            onApply = { mode, filterIds ->
                onSetHomeGridOptions(mode, filterIds)
            },
            onDismiss = { showHomeGridOptions = false }
        )
    }

    if (showCreateVaultPicker) {
        VaultPickerDialog(
            vaults = vaults,
            activeVaultId = settings.activeVaultId,
            title = "Create in vault",
            onSelect = { vault ->
                showCreateVaultPicker = false
                onCreateNewCapture(vault.id)
            },
            onDismiss = { showCreateVaultPicker = false }
        )
    }

    if (showVaultBrowser) {
        VaultBrowserModal(
            appRepository = appRepository,
            onOpenNote = { vaultId, path ->
                showVaultBrowser = false
                onOpenVaultNote(vaultId, path)
            },
            onOpenFlipSide = { vaultId, path ->
                showVaultBrowser = false
                onOpenFlipSide(vaultId, path)
            },
            onRenameCapture = onRenameCapture,
            onAddToBookshelf = { vaultId, path, kind ->
                onAddToBookshelf(vaultId, path, kind)
            },
            onDeleteEntry = onDeleteVaultEntry,
            onDismiss = { showVaultBrowser = false }
        )
    }

    val vaultForSwitcher = activeVault
    if (showQuickSwitcher && index != null && vaultForSwitcher != null) {
        QuickSwitcher(
            index = index,
            recentPaths = settings.recentNotesByVault[vaultForSwitcher.id].orEmpty(),
            onSelect = { note ->
                showQuickSwitcher = false
                if (note.hasInk) {
                    onOpenFlipSide(vaultForSwitcher.id, note.relativePath)
                } else {
                    onOpenVaultNote(vaultForSwitcher.id, note.relativePath)
                }
            },
            onAddToBookshelf = { path ->
                onAddToBookshelf(vaultForSwitcher.id, path, BookshelfKind.NOTE)
            },
            onDismiss = { showQuickSwitcher = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCaptureCard(
    item: HomeCaptureItem,
    isPinned: Boolean,
    hasCover: Boolean,
    showVaultName: Boolean,
    onOpenFlipSide: (String, String) -> Unit,
    onOpenBookshelfFolder: (String, String) -> Unit,
    onOpenText: (String, String) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String, String) -> Unit,
    onRename: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Column {
        Box {
            val previewId = item.previewPageId
            val coverPath = item.coverImagePath
            val clickModifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (item.isFolder) {
                            onOpenBookshelfFolder(item.vaultId, item.note.relativePath)
                        } else if (item.note.hasInk) {
                            onOpenFlipSide(item.vaultId, item.note.relativePath)
                        } else {
                            onOpenText(item.vaultId, item.note.relativePath)
                        }
                    },
                    onLongClick = { showMenu = true }
                )
                .aspectRatio(3f / 4f)
                .border(1.dp, Color.Gray, RectangleShape)
            when {
                item.isFolder -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = clickModifier
                            .background(Color.LightGray.copy(alpha = 0.25f))
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Folder,
                            contentDescription = "Folder",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                coverPath != null -> {
                    CaptureCoverPreview(
                        modifier = clickModifier,
                        imagePath = coverPath
                    )
                }
                previewId != null -> {
                    PagePreview(
                        modifier = clickModifier,
                        pageId = previewId
                    )
                }
                else -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = clickModifier
                            .background(Color.LightGray.copy(alpha = 0.35f))
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Edit3,
                            contentDescription = "Drawing",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(10.dp)
                        .background(Color.Black, RectangleShape)
                )
            }
            if (showMenu) {
                CaptureCardMenu(
                    isFolder = item.isFolder,
                    isPinned = isPinned,
                    hasCover = hasCover,
                    onPin = {
                        onTogglePin(item.captureKey)
                        showMenu = false
                    },
                    onRename = {
                        onRename()
                        showMenu = false
                    },
                    onSetCover = {
                        onSetCover()
                        showMenu = false
                    },
                    onRemoveCover = {
                        onRemoveCover()
                        showMenu = false
                    },
                    onArchive = {
                        onArchive(item.vaultId, item.note.relativePath)
                        showMenu = false
                    },
                    onOpenText = {
                        onOpenText(item.vaultId, item.note.relativePath)
                        showMenu = false
                    },
                    onDelete = {
                        onDelete()
                        showMenu = false
                    },
                    onDismiss = { showMenu = false }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.note.name,
            fontSize = 12.sp,
            color = Color.DarkGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        if (showVaultName) {
            Text(
                text = item.vaultName,
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CaptureCardMenu(
    isFolder: Boolean,
    isPinned: Boolean,
    hasCover: Boolean,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onArchive: () -> Unit,
    onOpenText: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    VaultEntryPopupMenu(onDismiss = onDismiss) {
        VaultEntryMenuItem(if (isPinned) "Unpin" else "Pin", onPin)
        if (!isFolder) {
            VaultEntryMenuItem("Rename", onRename)
            VaultEntryMenuItem("Set cover image", onSetCover)
            if (hasCover) {
                VaultEntryMenuItem("Remove cover", onRemoveCover)
            }
            VaultEntryMenuItem("Open text", onOpenText)
        }
        VaultEntryMenuItem("Remove from bookshelf", onArchive)
        VaultEntryMenuItem("Delete", onDelete)
    }
}

@Composable
fun FolderList(
    appRepository: AppRepository,
    folders: List<Folder>,
    onNavigateToFolder: (String) -> Unit, onCreateNewFolder: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        item {
            // Add new folder row
            Row(
                Modifier
                    .border(0.5.dp, Color.Black)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .noRippleClickable(onClick = onCreateNewFolder)
            ) {
                Icon(
                    imageVector = FeatherIcons.FolderPlus, contentDescription = "Add Folder",
                    Modifier.height(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(text = stringResource(R.string.home_add_new_folder))
            }
        }

        if (folders.isNotEmpty()) {
            items(folders) { folder ->
                var isFolderSettingsOpen by remember { mutableStateOf(false) }
                if (isFolderSettingsOpen) FolderConfigDialog(
                    appRepository.folderRepository,
                    folderId = folder.id,
                    onClose = {
                        log.i("Closing Directory Dialog")
                        isFolderSettingsOpen = false
                    })
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = { onNavigateToFolder(folder.id) },
                            onLongClick = { isFolderSettingsOpen = true })
                        .border(0.5.dp, Color.Black)
                        .padding(10.dp, 5.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder, contentDescription = "Folder",
                        Modifier.height(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = folder.title)
                }
            }
        }
    }
}

@Composable
fun NotebookGrid(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    books: List<Notebook>,
    isImporting: Boolean,
    onNavigateToEditor: (String, String) -> Unit,
    onDeleteEmptyBook: (String) -> Unit,
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit
) {
    Text(text = stringResource(R.string.home_notebooks))
    Spacer(Modifier.height(10.dp))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.autoEInkAnimationOnScroll()
    ) {
        item {
            NotebookImportPanel(
                onCreateNewNotebook = onCreateNewNotebook,
                onImportPdf = onImportPdf,
                onImportXopp = onImportXopp
            )
        }

        if (books.isNotEmpty()) {
            items(books.reversed()) { book ->
                if (book.pageIds.isEmpty()) {
                    if (!isImporting) {
                        EmptyBookWarningHandler(
                            emptyBook = book,
                            onDelete = { onDeleteEmptyBook(book.id) },
                            onDismiss = { })
                    }
                    return@items
                }
                var isSettingsOpen by remember { mutableStateOf(false) }
                NotebookCard(
                    bookId = book.id,
                    title = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    onOpen = { bookId, pageId -> onNavigateToEditor(pageId, bookId) },
                    onOpenSettings = { isSettingsOpen = true })

                if (isSettingsOpen) {
                    NotebookConfigDialog(
                        appRepository,
                        exportEngine = exportEngine,
                        bookId = book.id, onClose = { isSettingsOpen = false })
                }
            }
        }
    }
}

@Composable
fun NotebookImportPanel(
    onCreateNewNotebook: () -> Unit,
    onImportPdf: (Uri, Boolean) -> Unit,
    onImportXopp: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPdfImportChoiceDialog by remember { mutableStateOf<Uri?>(null) }

    showPdfImportChoiceDialog?.let { uri ->
        PdfImportChoiceDialog(uri = uri, onCopy = { uri ->
            showPdfImportChoiceDialog = null
            onImportPdf(uri, /* copy= */ true)
        }, onObserve = {
            showPdfImportChoiceDialog = null
            onImportPdf(it, /* copy= */ false)
        }, onDismiss = { showPdfImportChoiceDialog = null })
    }


    Box(
        modifier = modifier
            .width(100.dp)
            .aspectRatio(3f / 4f)
            .border(1.dp, Color.Gray, RectangleShape),
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Create New Notebook Button (Top Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f) // Takes half the height
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable(onClick = onCreateNewNotebook)
            ) {
                Icon(
                    imageVector = FeatherIcons.FilePlus, contentDescription = "Create Notebook",
                    tint = Color.Gray, modifier = Modifier.size(40.dp)
                )
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) {
                    log.w("PickVisualMedia: uri is null (user cancelled or provider returned null)")
                    return@rememberLauncherForActivityResult
                }
                try {

                    val mimeType = context.contentResolver.getType(uri)
                    log.d("Selected file mimeType: $mimeType, uri: $uri")
                    if (mimeType == "application/pdf" || uri.toString()
                            .endsWith(".pdf", ignoreCase = true)
                    ) {
                        showPdfImportChoiceDialog = uri
                    } else {
                        onImportXopp(uri)
                    }
                } catch (e: Exception) {
                    log.e("contentPicker failed: ${e.message}", e)
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Importing failed: ${e.message}"))
                }
            }
            // Import Notebook (Bottom Half)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.LightGray.copy(alpha = 0.3f))
                    .border(2.dp, Color.Black, RectangleShape)
                    .noRippleClickable {
                        launcher.launch(
                            arrayOf(
                                "application/x-xopp",
                                "application/gzip",
                                "application/octet-stream",
                                "application/pdf"
                            )
                        )
                    }

            ) {
                Icon(
                    imageVector = FeatherIcons.Upload,
                    contentDescription = "Import Notebook",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}


@Preview(
    showBackground = true,
    name = "Library - Default State",
    widthDp = 800,
    heightDp = 1200
)
@Composable
fun LibraryContentPreview() {
    // 1. Create a dummy UI state with mock data
    val mockUiState = LibraryUiState(
        folderId = null,
        isImporting = false,
        breadcrumbFolders = listOf(
            // Optional: Add mock breadcrumbs if you want to preview nested folder state
             Folder(id = "root", title = "Home", parentFolderId = null)
        ),
        folders = listOf(
            // Adjust constructor arguments based on your exact entity definition
            Folder(id = "folder_1", title = "Work Notes", parentFolderId = null),
            Folder(id = "folder_2", title = "Personal", parentFolderId = null)
        ),
        books = listOf(
            // Needs pageIds to render the card (empty books show a warning)
            Notebook(id = "book_1", title = "Meeting Minutes", pageIds = listOf("page1", "page2")),
            Notebook(id = "book_2", title = "Journal", pageIds = listOf("page3"))
        ),
        homeCaptures = emptyList()
    )

    // 2. Render the stateless component with empty lambdas
//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Preview(showBackground = true, name = "Library - Importing")
@Composable
fun LibraryContentUpdatePreview() {
    val mockUiState = LibraryUiState(
        folderId = "folder_1",
        isImporting = true,
        breadcrumbFolders = emptyList(),
        folders = emptyList(),
        books = emptyList(),
        homeCaptures = emptyList()
    )

//    LibraryContent(
//        uiState = mockUiState,
//        onNavigateToFolder = {},
//        onNavigateToSettings = {},
//        onNavigateToEditor = { _, _ -> },
//        goToPage = {},
//        onCreateNewQuickPage = {},
//        onCreateNewFolder = {},
//        onDeleteEmptyBook = {},
//        onCreateNewNotebook = {},
//        onImportPdf = { _, _ -> },
//        onImportXopp = {})
}