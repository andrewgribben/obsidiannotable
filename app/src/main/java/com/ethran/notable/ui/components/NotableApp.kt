package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.obsidiansync.ObsidianSyncManager
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.WidgetActions
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
    obsidianSyncManager: ObsidianSyncManager,
    deepLinkRoute: StateFlow<String?> = MutableStateFlow(null),
    onDeepLinkConsumed: () -> Unit = {},
    widgetAction: StateFlow<WidgetActions.Action?> = MutableStateFlow(null),
    onWidgetActionConsumed: () -> Unit = {}
) {
    val appNavState = rememberNotableAppState(obsidianSyncManager = obsidianSyncManager)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, obsidianSyncManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                obsidianSyncManager.onAppForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(obsidianSyncManager) {
        obsidianSyncManager.ensurePeriodicPullRunning()
    }

    // Navigate to a notable:// deep link target (initial intent or onNewIntent).
    val pendingDeepLink by deepLinkRoute.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        pendingDeepLink?.let { route ->
            appNavState.navController.navigate(route)
            onDeepLinkConsumed()
        }
    }

    val pendingWidget by widgetAction.collectAsState()
    LaunchedEffect(pendingWidget) {
        when (val action = pendingWidget) {
            is WidgetActions.Action.OpenFlip ->
                appNavState.goToFlipSide(appRepository, action.vaultId, action.relativePath)
            is WidgetActions.Action.NewCapture -> {
                val vaultId = action.vaultId ?: GlobalAppSettings.current.activeVault?.id
                if (vaultId != null) {
                    appNavState.onCreateNewCapture(appRepository, vaultId)
                }
            }
            is WidgetActions.Action.DailyNote -> {
                val vaultId = action.vaultId ?: GlobalAppSettings.current.activeVault?.id
                if (vaultId != null) {
                    appNavState.onOpenDailyNote(appRepository, vaultId)
                }
            }
            null -> Unit
        }
        if (pendingWidget != null) {
            onWidgetActionConsumed()
        }
    }
    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickSwitcher() }
    ) {
        val settings = GlobalAppSettings.current
        val activeVault = settings.activeVault
        val vaultIndex = activeVault?.let { VaultIndexRegistry.forVault(it) }

        NotableNavHost(
            exportEngine = exportEngine,
            editorSettingCacheManager = editorSettingCacheManager,
            appRepository = appRepository,
            obsidianSyncManager = obsidianSyncManager,
            appNavigator = appNavState
        )


        if (appNavState.isQuickSwitcherOpen && vaultIndex != null && activeVault != null) {
            QuickSwitcher(
                index = vaultIndex,
                recentPaths = settings.recentNotesByVault[activeVault.id].orEmpty(),
                onSelect = { note ->
                    appNavState.closeQuickSwitcher()
                    if (note.hasInk) {
                        appNavState.goToFlipSide(appRepository, activeVault.id, note.relativePath)
                    } else {
                        appNavState.goToVaultNote(activeVault.id, note.relativePath)
                    }
                },
                onDismiss = { appNavState.closeQuickSwitcher() }
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