package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.copyBackgroundToDatabase
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.flipside.FlipSideManager
import com.ethran.notable.io.obsidiansync.ObsidianSyncManager
import com.ethran.notable.io.vault.BookshelfIndexStore
import com.ethran.notable.io.vault.BookshelfKind
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.widget.HomeWidgetRefresher
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val folderId: String? = null,
    val isImporting: Boolean = false,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val homeCaptures: List<HomeCaptureItem> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    val appRepository: AppRepository,
    val importEngine: ImportEngine,
    val exportEngine: ExportEngine,
    private val obsidianSyncManager: ObsidianSyncManager,
    @param:ApplicationContext private val context: Context // Kept strictly for ImportEngine
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _breadcrumbFolders = MutableStateFlow<List<Folder>>(emptyList())
    private val _homeCapturesRefresh = MutableStateFlow(0)

    private val _foldersFlow =
        _folderId.flatMapLatest { id -> folderRepository.getAllInFolder(id).asFlow() }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }

    private val _homeCapturesFlow = _homeCapturesRefresh.flatMapLatest {
        flow { emit(buildHomeCaptures()) }.flowOn(Dispatchers.IO)
    }

    private val _dbDataFlow = combine(
        _foldersFlow, _booksFlow, _homeCapturesFlow
    ) { folders, books, captures ->
        Triple(folders, books, captures)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _folderId, _isImporting, _breadcrumbFolders, _dbDataFlow
    ) { folderId, isImporting, breadcrumbs, dbData ->
        LibraryUiState(
            folderId = folderId,
            isImporting = isImporting,
            breadcrumbFolders = breadcrumbs,
            folders = dbData.first,
            books = dbData.second,
            homeCaptures = dbData.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun refreshHomeCaptures() {
        _homeCapturesRefresh.value++
        HomeWidgetRefresher.refresh(context)
    }

    val obsidianSyncState = obsidianSyncManager.uiState

    fun syncObsidianVaults() {
        if (obsidianSyncManager.uiState.value.syncing) return
        viewModelScope.launch {
            val settings = GlobalAppSettings.current.normalizedVaults()
            obsidianSyncManager.syncAll(settings) { result ->
                launch(Dispatchers.Main) {
                    result.warning?.let {
                        SnackState.globalSnackFlow.tryEmit(SnackConf(text = it, duration = 5000))
                    }
                    if (result.results.isEmpty() && result.warning == null) {
                        SnackState.globalSnackFlow.tryEmit(
                            SnackConf(text = "No vaults configured for sync", duration = 3000)
                        )
                        return@launch
                    }
                    val failed = result.results.filter { it.error != null }
                    if (failed.isNotEmpty()) {
                        SnackState.globalSnackFlow.tryEmit(
                            SnackConf(
                                text = "Sync failed for ${failed.first().vaultName}: ${failed.first().error}",
                                duration = 5000
                            )
                        )
                    } else {
                        val pulled = result.results.sumOf { it.pulled }
                        val pushed = result.results.sumOf { it.pushed }
                        SnackState.globalSnackFlow.tryEmit(
                            SnackConf(
                                text = "Synced ${result.results.size} vault(s): pulled $pulled, pushed $pushed",
                                duration = 4000
                            )
                        )
                    }
                    refreshHomeCaptures()
                }
            }
        }
    }

    private suspend fun buildHomeCaptures(): List<HomeCaptureItem> {
        if (_folderId.value != null) return emptyList()

        val settings = GlobalAppSettings.current.normalizedVaults()
        val vaultsToScan = settings.vaults.let { all ->
            if (settings.homeVaultFilterIds.isEmpty()) all
            else all.filter { it.id in settings.homeVaultFilterIds }
        }
        val inputs = vaultsToScan.map { vault ->
            val index = BookshelfIndexStore.loadWithPinMigration(vault, settings)
            HomeBookshelfBuilder.BuildInput(
                vault = vault,
                index = index,
                bookshelfDir = settings.homeBookshelfDirByVault[vault.id].orEmpty(),
                coverImages = settings.homeCaptureCoverImages,
                previewPageIds = emptyMap()
            )
        }
        val raw = HomeBookshelfBuilder.buildRootItems(inputs)
        val items = raw.map { item ->
            if (item.previewPageId != null || item.isFolder) item
            else item.copy(
                previewPageId = FlipSideManager.pageIdForNote(
                    appRepository, item.vaultId, item.note.relativePath
                )
            )
        }
        val validKeys = items.map { it.captureKey }.toSet()
        val prunedCovers = settings.homeCaptureCoverImages.filterKeys { it in validKeys }
        if (prunedCovers != settings.homeCaptureCoverImages) {
            appRepository.kvProxy.setAppSettings(
                settings.copy(homeCaptureCoverImages = prunedCovers)
            )
        }
        return orderHomeBookshelfItems(
            items = items,
            sortMode = settings.homeSortMode,
            vaultFilterIds = settings.homeVaultFilterIds
        )
    }

    fun addToBookshelf(vaultId: String, relativePath: String, kind: BookshelfKind) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val vault = settings.vaults.find { it.id == vaultId } ?: return@launch
            val index = BookshelfIndexStore.load(vault)
            if (index.entries.any { it.path == relativePath }) {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Already on bookshelf", duration = 2500)
                )
                return@launch
            }
            BookshelfIndexStore.addEntry(vault, relativePath, kind)
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Added to bookshelf", duration = 2500)
            )
            refreshHomeCaptures()
        }
    }

    fun archiveFromHome(vaultId: String, relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val vault = settings.vaults.find { it.id == vaultId } ?: return@launch
            BookshelfIndexStore.archivePath(vault, relativePath)
            val captureKey = HomeCaptureKeys.vault(vaultId, relativePath)
            val pins = settings.homePinnedCaptureKeys.filter { it != captureKey }
            val covers = settings.homeCaptureCoverImages - captureKey
            if (pins != settings.homePinnedCaptureKeys ||
                covers != settings.homeCaptureCoverImages
            ) {
                appRepository.kvProxy.setAppSettings(
                    settings.copy(
                        homePinnedCaptureKeys = pins,
                        homeCaptureCoverImages = covers
                    )
                )
            }
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Removed from bookshelf", duration = 3000)
            )
            refreshHomeCaptures()
        }
    }

    fun openBookshelfFolder(vaultId: String, relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            appRepository.kvProxy.setAppSettings(
                settings.copy(
                    homeBookshelfDirByVault = settings.homeBookshelfDirByVault + (vaultId to relativePath)
                )
            )
            refreshHomeCaptures()
        }
    }

    fun closeBookshelfFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            if (settings.homeBookshelfDirByVault.isEmpty()) return@launch
            appRepository.kvProxy.setAppSettings(
                settings.copy(homeBookshelfDirByVault = emptyMap())
            )
            refreshHomeCaptures()
        }
    }

    fun bookshelfTargetDir(vaultId: String): String? {
        val dir = GlobalAppSettings.current.homeBookshelfDirByVault[vaultId].orEmpty()
            .trim().trim('/')
        return dir.takeIf { it.isNotEmpty() }
    }

    fun togglePin(captureKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val (vaultId, relativePath) = HomeCaptureKeys.parseVaultKey(captureKey) ?: return@launch
            val settings = GlobalAppSettings.current
            val vault = settings.vaults.find { it.id == vaultId } ?: return@launch
            val index = BookshelfIndexStore.load(vault)
            val currentlyPinned = index.entries.any { it.path == relativePath && it.pinned }
            BookshelfIndexStore.setPinned(vault, relativePath, !currentlyPinned)
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    text = if (currentlyPinned) "Unpinned" else "Pinned",
                    duration = 2000
                )
            )
            refreshHomeCaptures()
        }
    }

    fun deleteCaptureInk(vaultId: String, relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FlipSideManager.deleteCaptureInk(appRepository, vaultId, relativePath)
            if (ok) {
                val settings = GlobalAppSettings.current
                val captureKey = HomeCaptureKeys.vault(vaultId, relativePath)
                val pins = settings.homePinnedCaptureKeys.filter { it != captureKey }
                val covers = settings.homeCaptureCoverImages - captureKey
                if (pins != settings.homePinnedCaptureKeys ||
                    covers != settings.homeCaptureCoverImages
                ) {
                    appRepository.kvProxy.setAppSettings(
                        settings.copy(
                            homePinnedCaptureKeys = pins,
                            homeCaptureCoverImages = covers
                        )
                    )
                }
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Drawing deleted", duration = 3000)
                )
                refreshHomeCaptures()
            } else {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Could not delete drawing", duration = 4000)
                )
            }
        }
    }

    fun deleteVaultEntry(vaultId: String, relativePath: String, isFolder: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FlipSideManager.deleteVaultEntry(
                appRepository, vaultId, relativePath, isFolder
            )
            if (ok) {
                val settings = GlobalAppSettings.current
                val vault = settings.vaults.find { it.id == vaultId }
                if (vault != null) {
                    BookshelfIndexStore.removePathAndDescendants(vault, relativePath)
                    BookshelfIndexStore.invalidate(vaultId)
                }
                appRepository.kvProxy.setAppSettings(
                    HomeCaptureKeys.clearCaptureKeys(
                        settings, vaultId, relativePath, includeDescendants = isFolder
                    )
                )
                VaultIndexRegistry.invalidateAll()
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Deleted", duration = 3000)
                )
                refreshHomeCaptures()
            } else {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Could not delete", duration = 4000)
                )
            }
        }
    }

    fun renameCapture(vaultId: String, relativePath: String, newBaseName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldKey = HomeCaptureKeys.vault(vaultId, relativePath)
            val result = FlipSideManager.renameCapture(
                appRepository, vaultId, relativePath, newBaseName
            )
            result.onSuccess { newRelativePath ->
                val settings = GlobalAppSettings.current
                val newKey = HomeCaptureKeys.vault(vaultId, newRelativePath)
                val vault = settings.vaults.find { it.id == vaultId }
                if (vault != null) {
                    BookshelfIndexStore.renamePath(vault, relativePath, newRelativePath)
                }
                appRepository.kvProxy.setAppSettings(
                    HomeCaptureKeys.migrateCaptureKey(settings, oldKey, newKey)
                )
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Renamed", duration = 2000)
                )
                VaultIndexRegistry.invalidateAll()
                refreshHomeCaptures()
            }.onFailure { error ->
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(
                        text = error.message ?: "Rename failed",
                        duration = 4000
                    )
                )
            }
        }
    }

    fun setCaptureCover(vaultId: String, relativePath: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = runCatching {
                copyBackgroundToDatabase(
                    context, uri, BackgroundType.CoverImage.folderName
                )
            }.getOrElse {
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Could not save image", duration = 4000)
                )
                return@launch
            }
            val settings = GlobalAppSettings.current
            val key = HomeCaptureKeys.vault(vaultId, relativePath)
            appRepository.kvProxy.setAppSettings(
                settings.copy(
                    homeCaptureCoverImages = settings.homeCaptureCoverImages + (key to file.absolutePath)
                )
            )
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Cover image set", duration = 2000)
            )
            refreshHomeCaptures()
        }
    }

    fun removeCaptureCover(vaultId: String, relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val key = HomeCaptureKeys.vault(vaultId, relativePath)
            if (key !in settings.homeCaptureCoverImages) return@launch
            appRepository.kvProxy.setAppSettings(
                settings.copy(homeCaptureCoverImages = settings.homeCaptureCoverImages - key)
            )
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Cover removed", duration = 2000)
            )
            refreshHomeCaptures()
        }
    }

    fun setHomeGridOptions(sortMode: String, vaultFilterIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            appRepository.kvProxy.setAppSettings(
                settings.copy(
                    homeSortMode = sortMode,
                    homeVaultFilterIds = vaultFilterIds
                )
            )
            refreshHomeCaptures()
        }
    }


    fun loadFolder(folderId: String?) {
        PageDataManager.cancelLoadingPages()
        _folderId.value = folderId
        refreshHomeCaptures()

        viewModelScope.launch(Dispatchers.IO) {
            _breadcrumbFolders.value = resolveBreadcrumbs(folderId)
        }
    }

    private suspend fun resolveBreadcrumbs(folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()

        val list = mutableListOf<Folder>()
        var currentId: String? = folderId

        while (currentId != null) {
            val folder = folderRepository.get(currentId)
            if (folder != null) {
                list.add(folder)
                currentId = folder.parentFolderId
            } else {
                currentId = null
            }
        }
        return list.reversed()
    }

    fun createNewFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = Folder(parentFolderId = _folderId.value)
            folderRepository.create(folder)
        }
    }

    fun deleteEmptyBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.delete(bookId)
        }
    }

    fun onCreateNewNotebook() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current

            bookRepository.create(
                Notebook(
                    parentFolderId = _folderId.value,
                    defaultBackground = settings.defaultNativeTemplate,
                    defaultBackgroundType = BackgroundType.Native.key
                )
            )
        }
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val snackText =
                if (copy) "Importing PDF background (copy)" else "Setting up observer for PDF"

            _isImporting.value = true
            SnackState.globalSnackFlow.tryEmit(SnackConf(text = snackText, duration = 2000))

            try {
                importEngine.import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = !copy)
                )
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "PDF Import Successful"))
            } catch (e: Exception) {
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(
                    text = "Importing from xopp file...",
                    duration = 2000
                )
            )

            try {
                importEngine.import(uri, ImportOptions(folderId = _folderId.value))
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "XOPP Import Successful"))
            } catch (e: Exception) {
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
