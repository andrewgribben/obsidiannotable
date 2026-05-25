package com.ethran.notable.io

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.io.ExportEngine
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("SyncState")

object SyncState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val syncingPageIds = mutableStateListOf<String>()

    fun launchSync(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>,
        context: Context,
        exportEngine: ExportEngine,
        appEventBus: AppEventBus? = null
    ) {
        if (pageId in syncingPageIds) return
        syncingPageIds.add(pageId)

        scope.launch {
            try {
                InboxSyncEngine.syncInboxPage(appRepository, pageId, tags, context, exportEngine, appEventBus)
                log.i("Background sync complete for page $pageId")
            } catch (e: Exception) {
                log.e("Background sync failed for page $pageId: ${e.message}", e)
                appEventBus?.tryEmit(AppEvent.ActionHint("Sync failed: ${e.message}", 4000))
            } finally {
                syncingPageIds.remove(pageId)
            }
        }
    }
}
