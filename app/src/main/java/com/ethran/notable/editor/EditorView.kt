package com.ethran.notable.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.ui.EditorSidebar
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.SIDEBAR_WIDTH
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.InboxToolbar
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.io.SyncState
import com.ethran.notable.io.VaultTagScanner
import com.ethran.notable.io.exportToLinkedFile
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("EditorView")

object EditorDestination : NavigationDestination {
    override val route = "editor"

    const val PAGE_ID_ARG = "pageId"
    const val BOOK_ID_ARG = "bookId"

    // Unified route: editor/{pageId}?bookId={bookId}
    val routeWithArgs = "$route/{$PAGE_ID_ARG}?$BOOK_ID_ARG={$BOOK_ID_ARG}"

    /**
     * Helper to create the path. If bookId is null, it just won't be appended.
     */
    fun createRoute(pageId: String, bookId: String? = null): String {
        return "$route/$pageId" + if (bookId != null) "?$BOOK_ID_ARG=$bookId" else ""
    }
}


@Composable
fun EditorView(
    initialPageId: String,
    bookId: String?,
    isQuickNavOpen: Boolean = false,
    onPageChange: (String) -> Unit,
    goToLibrary: (folderId: String?) -> Unit,
    goToPages: (bookId: String) -> Unit,
    goToBugReport: () -> Unit,
    vm: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()
    val appRepository = vm.appRepository
    val exportEngine = vm.exportEngine
    val editorSettingCacheManager = vm.editorSettingCacheManager
    val pageId = initialPageId

    var pageExists by remember(pageId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pageId) {
        val exists = withContext(Dispatchers.IO) {
            appRepository.pageRepository.getById(pageId) != null
        }
        pageExists = exists

        if (!exists) {
            log.i("Could not find page, navigating to library")
            goToLibrary(null)
        }
    }


    if (pageExists == null) return

    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()


        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                appRepository = appRepository,
                currentPageId = pageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
            )
        }

        val editorState =
            remember {
                EditorState(
                    appRepository = appRepository,
                    bookId = bookId,
                    pageId = pageId,
                    pageView = page,
                    persistedEditorSettings = editorSettingCacheManager.getEditorSettings(),
                    onPageChange = onPageChange
                )
            }

        val history = remember {
            History(page)
        }
        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState).apply { registerObservers() }
        }


        // Inbox mode detection — query DB since pageFromDb loads async.
        // A page is an inbox/capture page when it has no notebook and an inbox path is configured.
        // (Legacy pages may still have background == "inbox"; treat those as inbox too.)
        var isInboxPage by remember { mutableStateOf(false) }
        val selectedTags = remember { mutableStateListOf<String>() }
        // Read tags reactively — updates when VaultTagScanner.refreshCache() runs
        val suggestedTags = VaultTagScanner.cachedTags

        LaunchedEffect(pageId) {
            val pageData = withContext(Dispatchers.IO) {
                appRepository.pageRepository.getById(pageId)
            }
            val inbox = pageData?.notebookId == null &&
                GlobalAppSettings.current.obsidianInboxPath.isNotBlank() ||
                pageData?.background == "inbox"
            isInboxPage = inbox
            editorState.isInboxPage = inbox
        }

        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                editorState.selectionState.applySelectionDisplace(page)
                if (bookId != null)
                    exportToLinkedFile(exportEngine, bookId, appRepository.bookRepository)
                page.disposeOldPage()
            }
        }

        // TODO put in editorSetting class
        LaunchedEffect(
            editorState.isToolbarOpen,
            editorState.pen,
            editorState.penSettings,
            editorState.mode,
            editorState.isToolbarOpen,
            editorState.eraser
        ) {
            log.i("EditorView: saving")
            editorSettingCacheManager.setEditorSettings(
                EditorSettingCacheManager.EditorSettings(
                    isToolbarOpen = editorState.isToolbarOpen,
                    mode = editorState.mode,
                    pen = editorState.pen,
                    eraser = editorState.eraser,
                    penSettings = editorState.penSettings
                )
            )
        }



        InkaTheme {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left-edge sidebar — physically outside the canvas SurfaceView
                // so finger taps always work even when Onyx SDK raw drawing is active
                EditorSidebar(exportEngine, goToLibrary, goToPages, goToBugReport, appRepository, editorState, editorControlTower)
                // Canvas area takes remaining space
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditorGestureReceiver(controlTower = editorControlTower)
                    EditorSurface(
                        appRepository = appRepository,
                        state = editorState,
                        page = page,
                        history = history
                    )
                    SelectedBitmap(
                        context = context,
                        controlTower = editorControlTower
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        ScrollIndicator(state = editorState)
                    }
                    if (isInboxPage) {
                        InboxToolbar(
                            selectedTags = selectedTags,
                            suggestedTags = suggestedTags,
                            isExpanded = editorState.isInboxTagsExpanded,
                            isToolbarOpen = editorState.isToolbarOpen,
                            onToggleExpanded = {
                                editorState.isInboxTagsExpanded = !editorState.isInboxTagsExpanded
                            },
                            onToggleToolbar = {
                                editorState.isToolbarOpen = !editorState.isToolbarOpen
                            },
                            onTagAdd = { tag ->
                                if (tag !in selectedTags) selectedTags.add(tag)
                            },
                            onTagRemove = { tag -> selectedTags.remove(tag) },
                            onSave = {
                                SyncState.launchSync(
                                    appRepository,
                                    pageId,
                                    selectedTags.toList(),
                                    context,
                                    exportEngine
                                )
                                goToLibrary(null)
                            },
                            onDiscard = { goToLibrary(null) }
                        )
                    }
                    HorizontalScrollIndicator(state = editorState)
                }
            }
        }
    }
}


