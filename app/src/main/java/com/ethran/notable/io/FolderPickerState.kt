package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri

/** Persists the last SAF document-tree URI so the folder picker reopens in the same place. */
object FolderPickerState {

    private const val PREFS_NAME = "folder_picker_state"
    private const val KEY_LAST_TREE_URI = "last_tree_uri"

    fun saveLastTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TREE_URI, uri.toString())
            .apply()
    }

    fun loadLastTreeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TREE_URI, null)
            ?: return null
        return raw.toUri()
    }
}
