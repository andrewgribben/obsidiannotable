package com.ethran.notable.navigation

import android.net.Uri

/**
 * notable://widget deep links for the home-screen widget.
 *
 * - `notable://widget/flip?vaultId=...&path=...` — open a capture in the editor
 * - `notable://widget/note?vaultId=...&path=...` — open a text-only note in the reader
 * - `notable://widget/new?vaultId=...` — create a new capture (vault optional)
 * - `notable://widget/daily?vaultId=...` — open or create today's daily note
 */
object WidgetActions {
    private const val SCHEME = "notable"
    private const val HOST = "widget"

    sealed class Action {
        data class OpenFlip(val vaultId: String, val relativePath: String) : Action()
        data class OpenNote(val vaultId: String, val relativePath: String) : Action()
        data class NewCapture(val vaultId: String?) : Action()
        data class DailyNote(val vaultId: String?) : Action()
    }

    fun flipUri(vaultId: String, relativePath: String): Uri =
        noteTargetUri("flip", vaultId, relativePath)

    fun noteUri(vaultId: String, relativePath: String): Uri =
        noteTargetUri("note", vaultId, relativePath)

    private fun noteTargetUri(target: String, vaultId: String, relativePath: String): Uri =
        Uri.parse(
            "$SCHEME://$HOST/$target?vaultId=${Uri.encode(vaultId)}&path=${Uri.encode(relativePath)}"
        )

    fun newCaptureUri(vaultId: String? = null): Uri {
        val base = "$SCHEME://$HOST/new"
        return if (vaultId.isNullOrBlank()) {
            Uri.parse(base)
        } else {
            Uri.parse("$base?vaultId=${Uri.encode(vaultId)}")
        }
    }

    fun dailyNoteUri(vaultId: String? = null): Uri {
        val base = "$SCHEME://$HOST/daily"
        return if (vaultId.isNullOrBlank()) {
            Uri.parse(base)
        } else {
            Uri.parse("$base?vaultId=${Uri.encode(vaultId)}")
        }
    }

    fun parse(uri: Uri): Action? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        return when (uri.path) {
            "/flip", "/note" -> {
                val vaultId = uri.getQueryParameter("vaultId")?.takeIf { it.isNotBlank() }
                    ?: return null
                val path = uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }
                    ?: return null
                if (uri.path == "/flip") {
                    Action.OpenFlip(vaultId, path)
                } else {
                    Action.OpenNote(vaultId, path)
                }
            }
            "/new" -> Action.NewCapture(uri.getQueryParameter("vaultId")?.takeIf { it.isNotBlank() })
            "/daily" -> Action.DailyNote(uri.getQueryParameter("vaultId")?.takeIf { it.isNotBlank() })
            else -> null
        }
    }
}
