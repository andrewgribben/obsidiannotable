package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.flipside.FlipSideManager
import com.ethran.notable.io.vault.listInboxNotesWithInkForVaults
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.viewmodels.HomeCaptureKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val _singlePagesFlow =
        _folderId.flatMapLatest { id -> pageRepository.getSinglePagesInFolder(id).asFlow() }

    private val _homeCapturesFlow = combine(_singlePagesFlow, _homeCapturesRefresh) { pages, _ ->
        pages
    }.flatMapLatest { legacyPages ->
        flow {
            emit(buildHomeCaptures(legacyPages))
        }
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

    private suspend fun buildHomeCaptures(legacyPages: List<Page>): List<HomeCaptureItem> {
        if (_folderId.value != null) return emptyList()

        val settings = GlobalAppSettings.current.normalizedVaults()
        val vaultItems = listInboxNotesWithInkForVaults(settings.vaults).map { (vault, note) ->
            HomeCaptureItem.VaultCapture(
                vaultId = vault.id,
                vaultName = vault.displayName,
                note = note,
                previewPageId = FlipSideManager.pageIdForNote(
                    appRepository, vault.id, note.relativePath
                )
            )
        }
        val legacyItems = legacyPages.map { HomeCaptureItem.LegacyQuickPage(it) }
        val allItems = vaultItems + legacyItems
        val validKeys = allItems.map { it.captureKey }.toSet()
        val prunedPins = settings.homePinnedCaptureKeys.filter { it in validKeys }
        if (prunedPins != settings.homePinnedCaptureKeys) {
            appRepository.kvProxy.setAppSettings(
                settings.copy(homePinnedCaptureKeys = prunedPins)
            )
        }
        val showLegacy = true
        return orderHomeCaptures(
            items = allItems,
            sortMode = settings.homeSortMode,
            pinnedKeys = prunedPins,
            vaultFilterIds = settings.homeVaultFilterIds,
            showLegacy = showLegacy
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
                if (pins != settings.homePinnedCaptureKeys) {
                    appRepository.kvProxy.setAppSettings(
                        settings.copy(homePinnedCaptureKeys = pins)
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
                // Ideally, ImportEngine should be injected via Hilt rather than instantiated here
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
