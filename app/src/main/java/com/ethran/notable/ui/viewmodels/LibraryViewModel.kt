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
import com.ethran.notable.io.vault.listInboxNotesWithInkForVaults
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
    }

    private suspend fun buildHomeCaptures(): List<HomeCaptureItem> {
        if (_folderId.value != null) return emptyList()

        val settings = GlobalAppSettings.current.normalizedVaults()
        val vaultsToScan = settings.vaults.let { all ->
            if (settings.homeVaultFilterIds.isEmpty()) all
            else all.filter { it.id in settings.homeVaultFilterIds }
        }
        val vaultItems = listInboxNotesWithInkForVaults(vaultsToScan).map { (vault, note) ->
            val captureKey = HomeCaptureKeys.vault(vault.id, note.relativePath)
            HomeCaptureItem(
                vaultId = vault.id,
                vaultName = vault.displayName,
                note = note,
                previewPageId = FlipSideManager.pageIdForNote(
                    appRepository, vault.id, note.relativePath
                ),
                coverImagePath = settings.homeCaptureCoverImages[captureKey]
            )
        }
        val validKeys = vaultItems.map { it.captureKey }.toSet()
        val prunedPins = settings.homePinnedCaptureKeys.filter { it in validKeys }
        val prunedCovers = settings.homeCaptureCoverImages.filterKeys { it in validKeys }
        if (prunedPins != settings.homePinnedCaptureKeys ||
            prunedCovers != settings.homeCaptureCoverImages
        ) {
            appRepository.kvProxy.setAppSettings(
                settings.copy(
                    homePinnedCaptureKeys = prunedPins,
                    homeCaptureCoverImages = prunedCovers
                )
            )
        }
        return orderHomeCaptures(
            items = vaultItems,
            sortMode = settings.homeSortMode,
            pinnedKeys = prunedPins,
            vaultFilterIds = settings.homeVaultFilterIds
        )
    }

    fun togglePin(captureKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val pins = settings.homePinnedCaptureKeys.toMutableList()
            if (captureKey in pins) {
                pins.remove(captureKey)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Unpinned", duration = 2000)
                )
            } else {
                pins.add(captureKey)
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Pinned", duration = 2000)
                )
            }
            appRepository.kvProxy.setAppSettings(
                settings.copy(homePinnedCaptureKeys = pins)
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

    fun renameCapture(vaultId: String, relativePath: String, newBaseName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldKey = HomeCaptureKeys.vault(vaultId, relativePath)
            val result = FlipSideManager.renameCapture(
                appRepository, vaultId, relativePath, newBaseName
            )
            result.onSuccess { newRelativePath ->
                val settings = GlobalAppSettings.current
                val newKey = HomeCaptureKeys.vault(vaultId, newRelativePath)
                appRepository.kvProxy.setAppSettings(
                    HomeCaptureKeys.migrateCaptureKey(settings, oldKey, newKey)
                )
                SnackState.globalSnackFlow.tryEmit(
                    SnackConf(text = "Renamed", duration = 2000)
                )
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
