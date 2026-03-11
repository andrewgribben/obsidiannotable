package com.ethran.notable.data.datastore

import android.content.Context

private const val PREFS_NAME = "vault_path_bootstrap"
private const val KEY_INBOX = "obsidian_inbox_path"
private const val KEY_ATTACHMENT = "obsidian_attachment_path"

/**
 * Stores vault paths in app-private storage so we can resolve [getDbDir][com.ethran.notable.data.getDbDir]
 * (and thus open the correct database) before loading full [AppSettings] from the DB.
 * Without this, we would always open the DB at the default path because settings are read after the DB is opened.
 */
object VaultPathBootstrap {

    /**
     * Returns saved (inboxPath, attachmentPath) or null if not set.
     */
    fun load(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inbox = prefs.getString(KEY_INBOX, null) ?: return null
        val attachment = prefs.getString(KEY_ATTACHMENT, null) ?: return null
        return inbox to attachment
    }

    fun save(context: Context, inboxPath: String, attachmentPath: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INBOX, inboxPath)
            .putString(KEY_ATTACHMENT, attachmentPath)
            .apply()
    }
}
