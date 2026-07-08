package com.ethran.notable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.ethran.notable.MainActivity
import com.ethran.notable.R
import com.ethran.notable.navigation.WidgetActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class HomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgetsSync(context, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgetsSync(context, appWidgetManager, intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val MAX_PER_ROW = 5
        private const val MAX_ROWS = 2
        private const val MAX_SLOTS = MAX_PER_ROW * MAX_ROWS
        private const val PAD_DP = 8
        private const val GAP_DP = 4
        private const val ACTION_ROW_DP = 36
        private const val ACTIONS_ONLY_THRESHOLD_DP = 56
        /** Minimum 3:4 cover height before we accept a second note row. */
        private const val MIN_DECENT_PREVIEW_HEIGHT_DP = 72
        /** Space for 12sp title (+ optional 11sp vault) so bottom labels are not clipped. */
        private const val TITLE_RESERVE_DP = 20
        private const val VAULT_RESERVE_DP = 14

        private val noteContainerIds = intArrayOf(
            R.id.widget_note_0,
            R.id.widget_note_1,
            R.id.widget_note_2,
            R.id.widget_note_3,
            R.id.widget_note_4,
            R.id.widget_note_5,
            R.id.widget_note_6,
            R.id.widget_note_7,
            R.id.widget_note_8,
            R.id.widget_note_9
        )
        private val noteImageIds = intArrayOf(
            R.id.widget_note_0_image,
            R.id.widget_note_1_image,
            R.id.widget_note_2_image,
            R.id.widget_note_3_image,
            R.id.widget_note_4_image,
            R.id.widget_note_5_image,
            R.id.widget_note_6_image,
            R.id.widget_note_7_image,
            R.id.widget_note_8_image,
            R.id.widget_note_9_image
        )
        private val noteTitleIds = intArrayOf(
            R.id.widget_note_0_title,
            R.id.widget_note_1_title,
            R.id.widget_note_2_title,
            R.id.widget_note_3_title,
            R.id.widget_note_4_title,
            R.id.widget_note_5_title,
            R.id.widget_note_6_title,
            R.id.widget_note_7_title,
            R.id.widget_note_8_title,
            R.id.widget_note_9_title
        )
        private val noteVaultIds = intArrayOf(
            R.id.widget_note_0_vault,
            R.id.widget_note_1_vault,
            R.id.widget_note_2_vault,
            R.id.widget_note_3_vault,
            R.id.widget_note_4_vault,
            R.id.widget_note_5_vault,
            R.id.widget_note_6_vault,
            R.id.widget_note_7_vault,
            R.id.widget_note_8_vault,
            R.id.widget_note_9_vault
        )

        private data class WidgetGridSpec(
            val layoutId: Int,
            val columns: Int,
            val rows: Int,
            val slotCount: Int,
            val previewWidthPx: Int,
            val previewHeightPx: Int
        )

        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidgetsSync(context, appWidgetManager, appWidgetIds)
            }
        }

        suspend fun updateWidgetsSync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) = withContext(Dispatchers.IO) {
            val density = context.resources.displayMetrics.density
            val activeVaultId = WidgetDataLoader.activeVaultId(context)
            for (appWidgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val widthDp = widgetWidthDp(options)
                val heightDp = layoutHeightDp(options)
                val rows = rowCountForHeight(options, widthDp)
                val grid = gridSpec(widthDp, heightDp, rows, density)
                val data = if (grid.slotCount == 0) {
                    WidgetData(emptyList(), showVaultName = false)
                } else {
                    WidgetDataLoader.load(context, grid.slotCount)
                }
                val views = buildRemoteViews(
                    context = context,
                    grid = grid,
                    data = data,
                    density = density,
                    activeVaultId = activeVaultId
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun widgetWidthDp(options: Bundle): Int {
            val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            return if (max > 0) max else minW
        }

        /**
         * Boox reports a short minHeight and a tall maxHeight for the same cell.
         * Size covers from the max so thumbnails fill the widget instead of sitting
         * tiny under a large empty border.
         */
        private fun layoutHeightDp(options: Bundle): Int {
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            return if (maxH > 0) maxH else minH
        }

        /**
         * Prefer 2 note rows when the cell is tall enough for two decent 3:4 covers.
         * Android formula: minHeight ≈ 70*n − 30 → 2 cells≈110, 3 cells≈180.
         */
        private fun rowCountForHeight(options: Bundle, widthDp: Int): Int {
            val heightDp = layoutHeightDp(options)
            if (heightDp < ACTIONS_ONLY_THRESHOLD_DP) return 1
            val availableNotesDp =
                (heightDp - PAD_DP - ACTION_ROW_DP - GAP_DP - 8).coerceAtLeast(40)
            val titleReserveDp = TITLE_RESERVE_DP + VAULT_RESERVE_DP
            val previewHForTwo =
                (availableNotesDp - GAP_DP) / 2 - titleReserveDp
            return if (previewHForTwo >= MIN_DECENT_PREVIEW_HEIGHT_DP) MAX_ROWS else 1
        }

        private fun gridSpec(widthDp: Int, heightDp: Int, rows: Int, density: Float): WidgetGridSpec {
            if (heightDp < ACTIONS_ONLY_THRESHOLD_DP) {
                return WidgetGridSpec(
                    layoutId = R.layout.widget_home_actions_only,
                    columns = 0,
                    rows = 0,
                    slotCount = 0,
                    previewWidthPx = 0,
                    previewHeightPx = 0
                )
            }

            val rowsClamped = rows.coerceIn(1, MAX_ROWS)
            val innerWidth = (widthDp - PAD_DP).coerceAtLeast(48)
            val availableNotesDp =
                (heightDp - PAD_DP - ACTION_ROW_DP - GAP_DP - 8).coerceAtLeast(40)
            val titleReserveDp = TITLE_RESERVE_DP + VAULT_RESERVE_DP
            val perRowHeightDp = (availableNotesDp - GAP_DP * (rowsClamped - 1)) / rowsClamped
            val maxPreviewHeightDp = (perRowHeightDp - titleReserveDp).coerceAtLeast(36)

            // Fill vertical space first: cover height → 3:4 width → how many columns fit.
            // This matches in-app VaultCaptureCard aspectRatio(3f/4f) and avoids the old
            // path that width-packed many tiny covers then height-squashed them.
            val heightBasedWidthDp =
                (maxPreviewHeightDp * 3f / 4f).toInt().coerceAtLeast(48)
            val columnsFromHeight = ((innerWidth + GAP_DP) / (heightBasedWidthDp + GAP_DP))
                .coerceIn(1, MAX_PER_ROW)
            val columnsFromWidth = when {
                widthDp < 130 -> 1
                widthDp < 220 -> 2
                widthDp < 320 -> 3
                widthDp < 420 -> 4
                else -> MAX_PER_ROW
            }
            val columns = min(columnsFromHeight, columnsFromWidth)

            val columnWidthDp = ((innerWidth - GAP_DP * (columns - 1)).toFloat() / columns)
                .toInt()
                .coerceAtLeast(48)

            // Largest 3:4 that fits in both the column slot and the row height budget.
            val naturalHeightDp = columnWidthDp * 4f / 3f
            val previewWidthDp: Int
            val previewHeightDp: Int
            if (naturalHeightDp > maxPreviewHeightDp) {
                previewHeightDp = maxPreviewHeightDp.coerceAtLeast(1)
                previewWidthDp = (previewHeightDp * 3f / 4f).toInt().coerceAtLeast(1)
            } else {
                previewWidthDp = columnWidthDp
                previewHeightDp = naturalHeightDp.toInt().coerceAtLeast(1)
            }

            return WidgetGridSpec(
                layoutId = R.layout.widget_home,
                columns = columns,
                rows = rowsClamped,
                slotCount = min(MAX_SLOTS, columns * rowsClamped),
                previewWidthPx = (previewWidthDp * density).toInt().coerceAtLeast(1),
                previewHeightPx = (previewHeightDp * density).toInt().coerceAtLeast(1)
            )
        }

        private fun buildRemoteViews(
            context: Context,
            grid: WidgetGridSpec,
            data: WidgetData,
            density: Float,
            activeVaultId: String?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, grid.layoutId)

            views.setOnClickPendingIntent(
                R.id.widget_btn_new,
                activityPendingIntent(context, 100, WidgetActions.newCaptureUri(activeVaultId))
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_daily,
                activityPendingIntent(context, 101, WidgetActions.dailyNoteUri(activeVaultId))
            )

            if (grid.layoutId == R.layout.widget_home_actions_only) {
                return views
            }

            views.setViewVisibility(
                R.id.widget_row_2,
                if (grid.rows > 1) View.VISIBLE else View.GONE
            )

            for (viewIndex in 0 until MAX_SLOTS) {
                val containerId = noteContainerIds[viewIndex]
                val imageId = noteImageIds[viewIndex]
                val titleId = noteTitleIds[viewIndex]
                val vaultId = noteVaultIds[viewIndex]
                val row = viewIndex / MAX_PER_ROW
                val col = viewIndex % MAX_PER_ROW

                if (row >= grid.rows || col >= grid.columns) {
                    views.setViewVisibility(containerId, View.GONE)
                    continue
                }

                val captureIndex = row * grid.columns + col
                if (captureIndex >= grid.slotCount) {
                    views.setViewVisibility(containerId, View.GONE)
                    continue
                }

                views.setViewVisibility(containerId, View.VISIBLE)
                val capture = data.captures.getOrNull(captureIndex)
                val preview = WidgetCardRenderer.renderPreview(
                    context = context,
                    capture = capture,
                    widthPx = grid.previewWidthPx,
                    density = density,
                    heightPx = grid.previewHeightPx
                )
                // Lock the ImageView to the computed 3:4 size so weight columns cannot
                // stretch covers to a different aspect ratio than in-app VaultCaptureCard.
                views.setViewLayoutWidth(
                    imageId,
                    grid.previewWidthPx.toFloat(),
                    android.util.TypedValue.COMPLEX_UNIT_PX
                )
                views.setViewLayoutHeight(
                    imageId,
                    grid.previewHeightPx.toFloat(),
                    android.util.TypedValue.COMPLEX_UNIT_PX
                )
                views.setImageViewBitmap(imageId, preview)

                if (capture != null) {
                    views.setTextViewText(titleId, capture.title)
                    views.setViewVisibility(titleId, View.VISIBLE)
                    if (data.showVaultName && capture.vaultName != null) {
                        views.setTextViewText(vaultId, capture.vaultName)
                        views.setViewVisibility(vaultId, View.VISIBLE)
                    } else {
                        views.setViewVisibility(vaultId, View.GONE)
                    }
                    views.setOnClickPendingIntent(
                        containerId,
                        activityPendingIntent(
                            context,
                            300 + captureIndex,
                            WidgetActions.flipUri(capture.vaultId, capture.relativePath)
                        )
                    )
                } else {
                    views.setTextViewText(titleId, "")
                    views.setViewVisibility(vaultId, View.GONE)
                    views.setOnClickPendingIntent(
                        containerId,
                        openAppPendingIntent(context, 200 + captureIndex)
                    )
                }
            }
            return views
        }

        private fun activityPendingIntent(context: Context, requestCode: Int, uri: android.net.Uri): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
