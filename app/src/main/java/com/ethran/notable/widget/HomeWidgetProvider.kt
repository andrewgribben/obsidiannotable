package com.ethran.notable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ethran.notable.MainActivity
import com.ethran.notable.R
import com.ethran.notable.navigation.WidgetActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        newOptions: android.os.Bundle
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
        private const val NOTE_SLOT_COUNT = 5

        private val noteContainerIds = intArrayOf(
            R.id.widget_note_0,
            R.id.widget_note_1,
            R.id.widget_note_2,
            R.id.widget_note_3,
            R.id.widget_note_4
        )
        private val noteImageIds = intArrayOf(
            R.id.widget_note_0_image,
            R.id.widget_note_1_image,
            R.id.widget_note_2_image,
            R.id.widget_note_3_image,
            R.id.widget_note_4_image
        )
        private val noteTitleIds = intArrayOf(
            R.id.widget_note_0_title,
            R.id.widget_note_1_title,
            R.id.widget_note_2_title,
            R.id.widget_note_3_title,
            R.id.widget_note_4_title
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
            val captures = WidgetDataLoader.loadCaptures(context, NOTE_SLOT_COUNT)
            val activeVaultId = WidgetDataLoader.activeVaultId(context)
            for (appWidgetId in appWidgetIds) {
                val minWidth = appWidgetManager.getAppWidgetOptions(appWidgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val visibleCount = visibleNoteCount(minWidth)
                val views = buildRemoteViews(
                    context = context,
                    captures = captures,
                    visibleCount = visibleCount,
                    activeVaultId = activeVaultId
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun visibleNoteCount(minWidthDp: Int): Int = when {
            minWidthDp < 180 -> 2
            minWidthDp < 280 -> 3
            minWidthDp < 380 -> 4
            else -> NOTE_SLOT_COUNT
        }

        private fun buildRemoteViews(
            context: Context,
            captures: List<WidgetCapture>,
            visibleCount: Int,
            activeVaultId: String?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_home)

            views.setOnClickPendingIntent(
                R.id.widget_btn_new,
                activityPendingIntent(
                    context,
                    requestCode = 100,
                    uri = WidgetActions.newCaptureUri(activeVaultId)
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_daily,
                activityPendingIntent(
                    context,
                    requestCode = 101,
                    uri = WidgetActions.dailyNoteUri(activeVaultId)
                )
            )

            for (index in 0 until NOTE_SLOT_COUNT) {
                val containerId = noteContainerIds[index]
                if (index >= visibleCount) {
                    views.setViewVisibility(containerId, View.GONE)
                    continue
                }
                views.setViewVisibility(containerId, View.VISIBLE)
                val capture = captures.getOrNull(index)
                if (capture == null) {
                    views.setImageViewResource(noteImageIds[index], R.drawable.pencil)
                    views.setTextViewText(noteTitleIds[index], "")
                    views.setOnClickPendingIntent(containerId, openAppPendingIntent(context, 200 + index))
                    continue
                }
                val bitmap = WidgetDataLoader.decodeThumbnail(capture.thumbnailPath)
                if (bitmap != null) {
                    views.setImageViewBitmap(noteImageIds[index], bitmap)
                } else {
                    views.setImageViewResource(noteImageIds[index], R.drawable.pencil)
                }
                views.setTextViewText(noteTitleIds[index], truncateTitle(capture.title))
                views.setOnClickPendingIntent(
                    containerId,
                    activityPendingIntent(
                        context,
                        requestCode = 300 + index,
                        uri = WidgetActions.flipUri(capture.vaultId, capture.relativePath)
                    )
                )
            }
            return views
        }

        private fun truncateTitle(title: String, maxLen: Int = 14): String {
            val trimmed = title.trim()
            if (trimmed.length <= maxLen) return trimmed
            return trimmed.take(maxLen - 1) + "…"
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
