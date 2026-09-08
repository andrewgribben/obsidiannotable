package com.ethran.notable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultPathBootstrap
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.MIGRATION_16_17
import com.ethran.notable.data.db.MIGRATION_17_18
import com.ethran.notable.data.db.MIGRATION_22_23
import com.ethran.notable.data.db.MIGRATION_32_33
import com.ethran.notable.data.getDbDir
import com.ethran.notable.io.flipside.FlipSideLink
import com.ethran.notable.io.vault.BookshelfIndexStore
import com.ethran.notable.ui.viewmodels.HomeBookshelfBuilder
import com.ethran.notable.ui.viewmodels.HomeCaptureItem
import com.ethran.notable.ui.viewmodels.HomeCaptureKeys
import com.ethran.notable.ui.viewmodels.orderHomeBookshelfItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class WidgetCapture(
    val vaultId: String,
    val relativePath: String,
    val title: String,
    val thumbnailPath: String?,
    val isCover: Boolean,
    val isPinned: Boolean,
    val hasInk: Boolean,
    val vaultName: String?
)

data class WidgetData(
    val captures: List<WidgetCapture>,
    val showVaultName: Boolean
)

object WidgetDataLoader {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(context: Context, limit: Int = 5): WidgetData =
        withContext(Dispatchers.IO) {
            val bootstrap = VaultPathBootstrap.load(context)
                ?: return@withContext WidgetData(emptyList(), showVaultName = false)
            GlobalAppSettings.update(
                GlobalAppSettings.current.copy(
                    obsidianInboxPath = bootstrap.first,
                    obsidianAttachmentPath = bootstrap.second
                )
            )
            val db = openDatabase(context)
            try {
                val settings = loadSettings(db)
                    ?: return@withContext WidgetData(emptyList(), showVaultName = false)
                val vaultsToScan = settings.vaults.let { all ->
                    if (settings.homeVaultFilterIds.isEmpty()) all
                    else all.filter { it.id in settings.homeVaultFilterIds }
                }
                if (vaultsToScan.isEmpty()) {
                    return@withContext WidgetData(emptyList(), showVaultName = false)
                }

                val flipPageIds = loadFlipPageIds(db)
                val inputs = vaultsToScan.map { vault ->
                    val index = BookshelfIndexStore.loadWithPinMigration(vault, settings)
                    HomeBookshelfBuilder.BuildInput(
                        vault = vault,
                        index = index,
                        bookshelfDir = "",
                        coverImages = settings.homeCaptureCoverImages,
                        previewPageIds = flipPageIds
                    )
                }
                val raw = HomeBookshelfBuilder.buildRootItems(inputs)
                    .filter { !it.isFolder }
                    .map { item ->
                        val pageId = item.previewPageId ?: flipPageIds[item.captureKey]
                        if (pageId != null && item.previewPageId == null) {
                            item.copy(previewPageId = pageId)
                        } else {
                            item
                        }
                    }
                val ordered = orderHomeBookshelfItems(
                    items = raw,
                    sortMode = settings.homeSortMode,
                    vaultFilterIds = settings.homeVaultFilterIds
                )
                val showVaultName = vaultsToScan.size > 1
                val captures = ordered.take(limit).map { item ->
                    toWidgetCapture(context, item, showVaultName)
                }
                WidgetData(captures, showVaultName)
            } finally {
                db.close()
            }
        }

    suspend fun activeVaultId(context: Context): String? {
        val bootstrap = VaultPathBootstrap.load(context) ?: return null
        GlobalAppSettings.update(
            GlobalAppSettings.current.copy(
                obsidianInboxPath = bootstrap.first,
                obsidianAttachmentPath = bootstrap.second
            )
        )
        val db = openDatabase(context)
        return try {
            loadSettings(db)?.activeVault?.id
        } finally {
            db.close()
        }
    }

    private fun toWidgetCapture(
        context: Context,
        item: HomeCaptureItem,
        showVaultName: Boolean
    ): WidgetCapture {
        val coverPath = item.coverImagePath?.takeIf { File(it).isFile }
        return WidgetCapture(
            vaultId = item.vaultId,
            relativePath = item.note.relativePath,
            title = item.note.name,
            thumbnailPath = coverPath ?: resolveInkPreviewPath(context, item.previewPageId),
            isCover = coverPath != null,
            isPinned = item.isPinned,
            hasInk = item.note.hasInk,
            vaultName = if (showVaultName) item.vaultName else null
        )
    }

    private fun resolveInkPreviewPath(context: Context, pageId: String?): String? {
        if (pageId.isNullOrBlank()) return null
        val filesDir = context.filesDir
        val thumb = File(filesDir, "pages/previews/thumbs/$pageId")
        if (thumb.isFile) return thumb.absolutePath
        val full = File(filesDir, "pages/previews/full/$pageId")
        if (full.isFile) return full.absolutePath
        return null
    }

    private suspend fun loadFlipPageIds(db: AppDatabase): Map<String, String> {
        return buildMap {
            for (kv in db.kvDao().getAllFlipPageLinks()) {
                val link = runCatching {
                    json.decodeFromString(FlipSideLink.serializer(), kv.value)
                }.getOrNull() ?: continue
                put(
                    HomeCaptureKeys.vault(link.vaultId, link.relativePath),
                    link.pageId
                )
            }
        }
    }

    private suspend fun loadSettings(db: AppDatabase): AppSettings? {
        return try {
            val raw = db.kvDao().get(APP_SETTINGS_KEY)?.value ?: return null
            val settings = json.decodeFromString(AppSettings.serializer(), raw).normalizedVaults()
            GlobalAppSettings.update(settings)
            settings
        } catch (_: Exception) {
            null
        }
    }

    private fun openDatabase(context: Context): AppDatabase {
        val dbFile = File(getDbDir(), "app_database")
        return Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_22_23,
                MIGRATION_32_33
            )
            .build()
    }
}
