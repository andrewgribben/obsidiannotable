package com.ethran.notable.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.io.flipside.FlipSideManager
import com.ethran.notable.io.obsidiansync.ObsidianSyncManager
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.NoteReaderDestination
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.VaultBrowserDestination
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.utils.hasFilePermission
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("NotableAppState")

@Composable
fun rememberNotableAppState(
    navController: NavHostController = rememberNavController(),
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    obsidianSyncManager: ObsidianSyncManager
): NotableNavigator {
    val context = LocalContext.current
    return remember(navController, context, coroutineScope, obsidianSyncManager) {
        NotableNavigator(navController, hasFilePermission(context), coroutineScope, obsidianSyncManager)
    }
}

@Stable
class NotableNavigator(
    val navController: NavHostController,
    private val hasFilePermission: Boolean,
    private val coroutineScope: CoroutineScope,
    private val obsidianSyncManager: ObsidianSyncManager
) {
    var isQuickNavOpen by mutableStateOf(false)
    var isQuickSwitcherOpen by mutableStateOf(false)
    var currentPageId by mutableStateOf<String?>(null)


    val startDestination: String
        get() = if (GlobalAppSettings.current.showWelcome || !hasFilePermission) WelcomeDestination.route
        else LibraryDestination.route

    val quickNavSourcePageId: String?
        get() = navController.currentBackStackEntry?.savedStateHandle?.get<String>("quickNavSourcePageId")

    fun openQuickNav() {
        navController.currentBackStackEntry?.savedStateHandle?.set(
            "quickNavSourcePageId", currentPageId
        )
        isQuickNavOpen = true
        updateDrawingState()
    }

    fun closeQuickNav() {
        isQuickNavOpen = false
        updateDrawingState()
        if (quickNavSourcePageId == currentPageId) {
            // User didn't use the QuickNav, so remove the savedStateHandle
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("quickNavSourcePageId")
        } else {
            // user did change page with QuickNav, start counting page changes
            navController.currentBackStackEntry?.savedStateHandle?.set("pageChangesSinceJump", 2)
        }
        refreshScreen()
    }

    fun openQuickSwitcher() {
        if (GlobalAppSettings.current.activeVault == null) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "No vault configured", duration = 3000)
            )
            return
        }
        isQuickSwitcherOpen = true
        updateDrawingState()
    }

    fun closeQuickSwitcher() {
        isQuickSwitcherOpen = false
        updateDrawingState()
        refreshScreen()
    }

    fun goToAnchor(appRepository: AppRepository){
        val targetPageId = quickNavSourcePageId
        if (targetPageId == null) {
            log.e("QuickNav source pageId is null")
            return
        }
        coroutineScope.launch {
            val notebookId = runCatching {
                withContext(Dispatchers.IO) {
                    appRepository.pageRepository.getById(quickNavSourcePageId ?: return@withContext null)?.notebookId
                }
            }.onFailure {
                log.w("Failed to load page $quickNavSourcePageId", it)
            }.getOrNull()
            navController.navigate(EditorDestination.createRoute(targetPageId, notebookId))
        }
    }

    fun shouldAnchorBeVisible(): Boolean {
        return isQuickNavOpen && quickNavSourcePageId != currentPageId
    }

    // Updates the drawing state based on whether a global overlay is open
    fun updateDrawingState() {
        coroutineScope.launch {
            val overlayOpen = isQuickNavOpen || isQuickSwitcherOpen
            log.d("Changing drawing state, overlayOpen: $overlayOpen")
            CanvasEventBus.isDrawing.emit(!overlayOpen)
        }
    }

    fun goToLibrary(folderId: String?) {
        navController.navigate(LibraryDestination.createRoute(folderId))
    }

    fun goToEditor(pageId: String, bookId: String?) {
        navController.navigate(EditorDestination.createRoute(pageId, bookId))
    }

    fun goBack() {
        navController.popBackStack()
    }

    fun goToWelcome() {
        navController.navigate(WelcomeDestination.route)
    }

    fun goToSystemInfo() {
        navController.navigate(SystemInformationDestination.route)
    }

    fun goToPage(appRepository: AppRepository, pageId: String) {
        coroutineScope.launch {
            val bookId = runCatching {
                withContext(Dispatchers.IO) {
                    appRepository.pageRepository.getById(pageId)?.notebookId
                }
            }.onFailure {
                log.d("failed to resolve bookId for $pageId", it)
            }.getOrNull()
            val url = EditorDestination.createRoute(pageId, bookId)
            log.d("navigate -> $url")
            navController.navigate(url)
        }
    }

    fun goToVaultBrowser(dir: String? = null) {
        navController.navigate(VaultBrowserDestination.createRoute(dir))
    }

    fun goToVaultNote(vaultId: String, relativePath: String) {
        navController.navigate(NoteReaderDestination.createRoute(vaultId, relativePath))
    }

    fun goToVaultNote(relativePath: String) {
        val vaultId = GlobalAppSettings.current.activeVault?.id ?: return
        goToVaultNote(vaultId, relativePath)
    }

    fun goToFlipSide(appRepository: AppRepository, noteRelativePath: String) {
        val vaultId = GlobalAppSettings.current.activeVault?.id ?: return
        goToFlipSide(appRepository, vaultId, noteRelativePath)
    }

    fun goToFlipSide(
        appRepository: AppRepository,
        vaultId: String,
        noteRelativePath: String
    ) {
        coroutineScope.launch {
            val pageId = withContext(Dispatchers.IO) {
                FlipSideManager.openFlipSide(appRepository, vaultId, noteRelativePath)
            } ?: return@launch
            navController.navigate(EditorDestination.createRoute(pageId, null))
        }
    }

    /** Opens a scratch handwriting page whose recognized text appends to the vault note. */
    fun goToHandwritingInsert(appRepository: AppRepository, noteRelativePath: String) {
        coroutineScope.launch {
            val pageId = withContext(Dispatchers.IO) {
                FlipSideManager.openInsertPage(appRepository, noteRelativePath)
            }
            if (pageId != null) {
                navController.navigate(EditorDestination.createRoute(pageId, null))
            } else {
                log.e("Could not open handwriting insert page for $noteRelativePath")
            }
        }
    }

    fun onCreateNewCapture(appRepository: AppRepository, vaultId: String) {
        coroutineScope.launch {
            val targetDir = GlobalAppSettings.current.homeBookshelfDirByVault[vaultId]
                ?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
            val pageId = withContext(Dispatchers.IO) {
                FlipSideManager.createNewCapture(appRepository, vaultId, targetDir)
            } ?: return@launch
            navController.navigate(EditorDestination.createRoute(pageId, null))
        }
    }

    fun onOpenDailyNote(appRepository: AppRepository, vaultId: String) {
        coroutineScope.launch {
            val pageId = withContext(Dispatchers.IO) {
                FlipSideManager.openOrCreateDailyNote(appRepository, vaultId)
            } ?: return@launch
            navController.navigate(EditorDestination.createRoute(pageId, null))
        }
    }

    fun onCreateNewQuickPage(appRepository: AppRepository, folderId: String?) {
        coroutineScope.launch {
            val pageId = withContext(Dispatchers.IO) {
                appRepository.createNewQuickPage(parentFolderId = folderId)
            } ?: return@launch
            navController.navigate(EditorDestination.createRoute(pageId, null))
        }
    }


    fun onPageChange(backStackEntry: NavBackStackEntry, newPageId: String) {
        // SAVE new pageId in savedStateHandle - do not call navigate
        backStackEntry.savedStateHandle["pageId"] = newPageId
        if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 2) {
            backStackEntry.savedStateHandle["pageChangesSinceJump"] = 1
        } else if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 1) {
            backStackEntry.savedStateHandle.remove<Int>("pageChangesSinceJump")
            backStackEntry.savedStateHandle.remove<String>("quickNavSourcePageId")
        }
        currentPageId = newPageId
        log.d("Editor changed page -> saved pageId=$newPageId (no navigate, no recreate)")
    }

    /**
     * Resolves the pageId from the backStackEntry (prioritizing saved state),
     * synchronizes the internal state, and ensures the SavedStateHandle is updated.
     */
    fun resolveAndSyncPageId(backStackEntry: NavBackStackEntry): String {

        // Priority: SavedStateHandle (for process death/recomposition) > Nav Argument
        val newCurrentPageId =
            backStackEntry.savedStateHandle.get<String>(EditorDestination.PAGE_ID_ARG)
                ?: backStackEntry.arguments?.getString(EditorDestination.PAGE_ID_ARG)!!

        // Sync state
        currentPageId = newCurrentPageId
        backStackEntry.savedStateHandle[EditorDestination.PAGE_ID_ARG] = currentPageId
        return newCurrentPageId
    }

    fun cleanCurrentPageId() {
        currentPageId = null
    }
}
