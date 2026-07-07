package com.ethran.notable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.data.datastore.VaultPathBootstrap
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.MIGRATION_16_17
import com.ethran.notable.data.db.MIGRATION_17_18
import com.ethran.notable.data.db.MIGRATION_22_23
import com.ethran.notable.data.db.MIGRATION_32_33
import com.ethran.notable.data.getDbDir
import com.ethran.notable.io.flipside.FlipSideLink
import com.ethran.notable.io.vault.BookshelfIndexStore
import com.ethran.notable.io.vault.listInboxNotesWithInkForVaults
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
    val thumbnailPath: String?
)

object WidgetDataLoader {
    private const val MAX_THUMB_PX = 220
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCaptures(context: Context, limit: Int = 5): List<WidgetCapture> =
        withContext(Dispatchers.IO) {
            val settings = loadSettings(context) ?: return@withContext emptyList()
            val vaultsToScan = settings.vaults.let { all ->
                if (settings.homeVaultFilterIds.isEmpty()) all
                else all.filter { it.id in settings.homeVaultFilterIds }
            }
            if (vaultsToScan.isEmpty()) return@withContext emptyList()

            val flipPageIds = loadFlipPageIds(context, vaultsToScan)
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
                    if (item.previewPageId != null) item
                    else item.copy(previewPageId = flipPageIds[item.captureKey])
                }
            val ordered = orderHomeBookshelfItems(
                items = raw,
                sortMode = settings.homeSortMode,
                vaultFilterIds = settings.homeVaultFilterIds
            )
            ordered.take(limit).map { item ->
                WidgetCapture(
                    vaultId = item.vaultId,
                    relativePath = item.note.relativePath,
                    title = item.note.name,
                    thumbnailPath = resolveThumbnailPath(context, item)
                )
            }
        }

    suspend fun activeVaultId(context: Context): String? =
        loadSettings(context)?.activeVault?.id

    fun decodeThumbnail(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = maxOf(1, largest / MAX_THUMB_PX)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    private fun resolveThumbnailPath(context: Context, item: HomeCaptureItem): String? {
        item.coverImagePath?.takeIf { File(it).isFile }?.let { return it }
        val pageId = item.previewPageId ?: return null
        val thumb = File(context.filesDir, "pages/previews/thumbs/$pageId")
        return thumb.takeIf { it.isFile }?.absolutePath
    }

    private suspend fun loadFlipPageIds(
        context: Context,
        vaults: List<VaultConfig>
    ): Map<String, String> {
        val db = openDatabase(context)
        return try {
            val kvDao = db.kvDao()
            buildMap {
                for ((vault, note) in listInboxNotesWithInkForVaults(vaults)) {
                    val key = "FLIP_PAGE:${vault.id}:${note.relativePath}"
                    val raw = kvDao.get(key)?.value ?: continue
                    val link = runCatching {
                        json.decodeFromString(FlipSideLink.serializer(), raw)
                    }.getOrNull() ?: continue
                    put(HomeCaptureKeys.vault(vault.id, note.relativePath), link.pageId)
                }
            }
        } finally {
            db.close()
        }
    }

    private suspend fun loadSettings(context: Context): AppSettings? {
        val bootstrap = VaultPathBootstrap.load(context) ?: return null
        GlobalAppSettings.update(
            GlobalAppSettings.current.copy(
                obsidianInboxPath = bootstrap.first,
                obsidianAttachmentPath = bootstrap.second
            )
        )
        val db = openDatabase(context)
        return try {
            val raw = db.kvDao().get(APP_SETTINGS_KEY)?.value ?: return null
            val settings = json.decodeFromString(AppSettings.serializer(), raw).normalizedVaults()
            GlobalAppSettings.update(settings)
            settings
        } catch (_: Exception) {
            null
        } finally {
            db.close()
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
