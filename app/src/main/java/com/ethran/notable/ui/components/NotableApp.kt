package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.rememberNotableAppState
import com.ethran.notable.ui.SnackBar
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


@Composable
fun NotableApp(
    exportEngine: ExportEngine,
    editorSettingCacheManager: EditorSettingCacheManager,
    snackState: SnackState,
    appRepository: AppRepository,
    deepLinkRoute: StateFlow<String?> = MutableStateFlow(null),
    onDeepLinkConsumed: () -> Unit = {}
) {
    val appNavState = rememberNotableAppState()

    // Navigate to a notable:// deep link target (initial intent or onNewIntent).
    val pendingDeepLink by deepLinkRoute.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        pendingDeepLink?.let { route ->
            appNavState.navController.navigate(route)
            onDeepLinkConsumed()
        }
    }
    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickNav() }
    ) {
        NotableNavHost(
            exportEngine = exportEngine,
            editorSettingCacheManager = editorSettingCacheManager,
            appRepository = appRepository,
            appNavigator = appNavState
        )


        // overlays
        if (appNavState.isQuickNavOpen) {
            QuickNav(
                appRepository = appRepository,
                currentPageId = appNavState.currentPageId,
                quickNavSourcePageId = appNavState.quickNavSourcePageId,
                onClose = { appNavState.closeQuickNav() },
                goToPage = { pageId -> appNavState.goToPage(appRepository, pageId) },
                goToFolder = { folderId -> appNavState.goToLibrary(folderId) }
            )
        }

        if (appNavState.shouldAnchorBeVisible()) {
            Anchor(
                onClose = {
                    appNavState.goToAnchor(appRepository)
                    appNavState.closeQuickNav()
                }
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black)
    )
    SnackBar(state = snackState)
}