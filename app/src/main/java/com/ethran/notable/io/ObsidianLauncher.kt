package com.ethran.notable.io

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Launches the Obsidian Android app so the user can trigger vault sync. */
object ObsidianLauncher {

    const val PACKAGE_NAME = "md.obsidian"
    private const val MAIN_ACTIVITY = "$PACKAGE_NAME.MainActivity"

    /** Returns true when Obsidian was launched, false when it is not installed. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(PACKAGE_NAME, MAIN_ACTIVITY)
            }

        if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return false
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
