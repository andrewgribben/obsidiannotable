package com.ethran.notable.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.ui.views.NoteReaderDestination
import com.ethran.notable.ui.views.VaultBrowserDestination
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("DeepLinks")

/**
 * notable:// deep links: open app content from outside the app and launch a second
 * app window (Boox split screen) on a specific vault note.
 *
 * Supported URIs:
 * - `notable://vault/note?path=<encoded relative path>&vaultId=<optional vault config id>`
 * - `notable://vault/browse` — open the vault browser
 */
object DeepLinks {
    private const val SCHEME = "notable"
    private const val HOST_VAULT = "vault"

    fun vaultNoteUri(relativePath: String, vaultId: String? = null): Uri {
        val builder = Uri.parse("$SCHEME://$HOST_VAULT/note").buildUpon()
            .appendQueryParameter("path", relativePath)
        if (!vaultId.isNullOrBlank()) {
            builder.appendQueryParameter("vaultId", vaultId)
        }
        return builder.build()
    }

    /** Maps a notable:// URI to an in-app navigation route, or null when unrecognized. */
    fun routeFor(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.host != HOST_VAULT) return null
        return when (uri.path) {
            "/note" -> {
                val path = uri.getQueryParameter("path")?.takeIf { it.isNotBlank() } ?: return null
                val vaultId = uri.getQueryParameter("vaultId")?.takeIf { it.isNotBlank() }
                    ?: GlobalAppSettings.current.activeVault?.id
                    ?: return null
                NoteReaderDestination.createRoute(vaultId, path)
            }
            "/browse" -> VaultBrowserDestination.createRoute(null)
            else -> null
        }
    }

    /**
     * Opens [relativePath] in a new window of the app. In split screen the new
     * instance launches adjacent to the current one; otherwise it opens as a new
     * document task the user can place with the Boox split-screen switcher.
     */
    fun openNoteInNewWindow(context: Context, relativePath: String) {
        val intent = Intent(Intent.ACTION_VIEW, vaultNoteUri(relativePath)).apply {
            setPackage(context.packageName)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            log.e("Could not open new window for $relativePath: ${e.message}")
        }
    }
}
