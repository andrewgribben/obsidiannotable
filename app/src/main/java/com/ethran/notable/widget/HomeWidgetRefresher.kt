package com.ethran.notable.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object HomeWidgetRefresher {
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, HomeWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        HomeWidgetProvider.updateWidgets(context, manager, ids)
    }
}
