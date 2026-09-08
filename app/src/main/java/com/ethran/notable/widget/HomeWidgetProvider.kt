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
        /**
         * Prefer covers at least this tall. Below this, shrink column count (reflow)
         * rather than packing many tiny tiles.
         */
        private const val MIN_DECENT_PREVIEW_HEIGHT_DP = 80
        /** Space for 12sp title (+ optional 11sp vault) so bottom labels are not clipped. */
        private const val TITLE_RESERVE_DP = 20
        private const val VAULT_RESERVE_DP = 14
        private const val COMPACT_TITLE_RESERVE_DP = 16

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
            val previewHeightPx: Int,
            val compactTitles: Boolean = false
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
                val widthDp = widgetWidthDp(context, options)
                val heightDp = layoutHeightDp(context, options, widthDp)
                val grid = gridSpec(widthDp, heightDp, density)
                android.util.Log.i(
                    "HomeWidget",
                    "id=$appWidgetId size=${widthDp}x${heightDp}dp " +
                        "grid=${grid.columns}x${grid.rows} " +
                        "preview=${grid.previewWidthPx}x${grid.previewHeightPx}px " +
                        "opts=${options.keySet().joinToString { k ->
                            "$k=${options.get(k)}"
                        }}"
                )
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

        /**
         * Boox's AppWidgetHost often returns 0 for all OPTION_APPWIDGET_* sizes even when
         * the widget spans most of the home screen. Fall back to a screen-based estimate
         * so we don't collapse to the 180×110dp provider minimum (2 tiny columns).
         */
        private fun widgetWidthDp(context: Context, options: Bundle): Int {
            val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            if (max > 0) return max
            if (minW > 0) return minW
            val metrics = context.resources.displayMetrics
            val screenDp = metrics.widthPixels / metrics.density
            // Typical Boox large home widget ≈ full content width minus launcher margins.
            return (screenDp * 0.90f).toInt().coerceAtLeast(280)
        }

        private fun layoutHeightDp(context: Context, options: Bundle, widthDp: Int): Int {
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val reported = when {
                maxH > 0 -> maxH
                minH > 0 -> minH
                else -> {
                    val metrics = context.resources.displayMetrics
                    val screenDp = metrics.heightPixels / metrics.density
                    // Roughly a third of the home screen — enough for 2 rows of 3:4 covers.
                    return (screenDp * 0.30f).toInt().coerceIn(160, 340)
                }
            }
            return inflateUnderreportedHeight(context, widthDp, reported)
        }

        /**
         * Boox sometimes reports a 1-cell height (e.g. 75dp) while the host frame is still
         * visually tall enough for usable covers. When the reported height cannot fit even
         * one decent 3:4 cover, inflate to a 1-row layout height derived from width.
         */
        private fun inflateUnderreportedHeight(
            context: Context,
            widthDp: Int,
            reportedHeightDp: Int
        ): Int {
            val chromeDp = PAD_DP + ACTION_ROW_DP + GAP_DP + 8
            val titleReserveDp = COMPACT_TITLE_RESERVE_DP
            val minUsable =
                chromeDp + titleReserveDp + MIN_DECENT_PREVIEW_HEIGHT_DP
            if (reportedHeightDp >= minUsable) return reportedHeightDp

            val preferCols = widthColumnsCap(widthDp).coerceIn(1, 4)
            val innerWidth = (widthDp - PAD_DP).coerceAtLeast(48)
            val colW = ((innerWidth - GAP_DP * (preferCols - 1)).toFloat() / preferCols)
                .toInt()
                .coerceAtLeast(48)
            val idealOneRow =
                chromeDp + titleReserveDp + (colW * 4f / 3f).toInt()
            val metrics = context.resources.displayMetrics
            val screenCap = ((metrics.heightPixels / metrics.density) * 0.35f).toInt()
            return maxOf(reportedHeightDp, idealOneRow.coerceAtMost(screenCap))
        }

        private fun widthColumnsCap(widthDp: Int): Int = when {
            widthDp < 130 -> 1
            widthDp < 200 -> 2
            widthDp < 280 -> 3
            widthDp < 360 -> 4
            else -> MAX_PER_ROW
        }

        /**
         * Reflow covers to fill available height at 3:4, then take as many columns as fit
         * at that size. Short widgets get fewer, larger tiles; tall ones get a second row.
         */
        private fun gridSpec(widthDp: Int, heightDp: Int, density: Float): WidgetGridSpec {
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

            val chromeDp = PAD_DP + ACTION_ROW_DP + GAP_DP + 8
            val availableNotesDp = (heightDp - chromeDp).coerceAtLeast(40)
            val innerWidth = (widthDp - PAD_DP).coerceAtLeast(48)
            val maxCols = widthColumnsCap(widthDp)

            // Prefer 2 rows only when each row can still hold a decent 3:4 cover.
            val titleReserveTwo = TITLE_RESERVE_DP + VAULT_RESERVE_DP
            val previewHForTwo =
                (availableNotesDp - GAP_DP) / 2 - titleReserveTwo
            val rows = if (previewHForTwo >= MIN_DECENT_PREVIEW_HEIGHT_DP) MAX_ROWS else 1

            val titleReserveDp = if (rows == 1) {
                COMPACT_TITLE_RESERVE_DP
            } else {
                TITLE_RESERVE_DP + VAULT_RESERVE_DP
            }
            val perRowHeightDp = (availableNotesDp - GAP_DP * (rows - 1)) / rows
            val maxPreviewHeightDp = (perRowHeightDp - titleReserveDp).coerceAtLeast(36)

            // Fill the row height with 3:4 covers, then see how many such tiles fit in width.
            val heightFillWidthDp =
                (maxPreviewHeightDp * 3f / 4f).toInt().coerceAtLeast(1)
            val columnsFromHeight = ((innerWidth + GAP_DP) / (heightFillWidthDp + GAP_DP))
                .coerceIn(1, maxCols)

            // Also allow packing more columns if full column-width 3:4 still clears min size
            // (wide + short enough for one row of width-equal tiles).
            var columns = columnsFromHeight
            for (c in (columnsFromHeight + 1)..maxCols) {
                val colW = ((innerWidth - GAP_DP * (c - 1)).toFloat() / c).toInt()
                val natH = colW * 4f / 3f
                if (natH <= maxPreviewHeightDp && natH >= MIN_DECENT_PREVIEW_HEIGHT_DP) {
                    columns = c
                } else {
                    break
                }
            }

            val columnWidthDp = ((innerWidth - GAP_DP * (columns - 1)).toFloat() / columns)
                .toInt()
                .coerceAtLeast(48)

            val naturalHeightDp = columnWidthDp * 4f / 3f
            val previewWidthDp: Int
            val previewHeightDp: Int
            if (naturalHeightDp > maxPreviewHeightDp) {
                // Height-fill: use full row height and the matching 3:4 width.
                previewHeightDp = maxPreviewHeightDp.coerceAtLeast(1)
                previewWidthDp = (previewHeightDp * 3f / 4f).toInt().coerceAtLeast(1)
            } else {
                previewWidthDp = columnWidthDp
                previewHeightDp = naturalHeightDp.toInt().coerceAtLeast(1)
            }

            return WidgetGridSpec(
                layoutId = R.layout.widget_home,
                columns = columns,
                rows = rows,
                slotCount = min(MAX_SLOTS, columns * rows),
                previewWidthPx = (previewWidthDp * density).toInt().coerceAtLeast(1),
                previewHeightPx = (previewHeightDp * density).toInt().coerceAtLeast(1),
                compactTitles = rows == 1
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
                if (grid.rows > 1 && data.captures.size > grid.columns) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
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
                if (captureIndex >= grid.slotCount ||
                    data.captures.getOrNull(captureIndex) == null
                ) {
                    // Keep empty first-row cells as invisible spacers so real cards retain
                    // their configured width; an entirely empty second row is hidden above.
                    views.setViewVisibility(containerId, View.INVISIBLE)
                    continue
                }

                val capture = data.captures[captureIndex]
                views.setViewVisibility(containerId, View.VISIBLE)
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

                views.setTextViewText(titleId, capture.title)
                views.setViewVisibility(titleId, View.VISIBLE)
                if (!grid.compactTitles && data.showVaultName && capture.vaultName != null) {
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
                        if (capture.hasInk) {
                            WidgetActions.flipUri(capture.vaultId, capture.relativePath)
                        } else {
                            WidgetActions.noteUri(capture.vaultId, capture.relativePath)
                        }
                    )
                )
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

    }
}
