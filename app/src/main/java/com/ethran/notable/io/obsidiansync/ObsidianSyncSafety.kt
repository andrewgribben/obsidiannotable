package com.ethran.notable.io.obsidiansync

/**
 * Guards against destructive sync while the client is under development.
 *
 * Default is [Mode.ProbeOnly]: only read-only REST calls (sign-in, list vaults,
 * validate vault access). No WebSocket connect, push, pull, or delete.
 */
object ObsidianSyncSafety {

    enum class Mode {
        /** Sign-in, list vaults, validate keyhash/host only. No vault file I/O. */
        ProbeOnly,

        /** Full bidirectional sync (not enabled until explicitly reviewed). */
        FullSync
    }

    /** Active safety mode. Defaults to probe-only. */
    @Volatile
    var mode: Mode = Mode.ProbeOnly

    val isProbeOnly: Boolean
        get() = mode == Mode.ProbeOnly

    /** Throws when a mutating sync operation is attempted in probe-only mode. */
    fun requireMutatingSyncAllowed(operation: String) {
        if (isProbeOnly) {
            throw IllegalStateException(
                "Obsidian Sync $operation is disabled while PROBE_ONLY is active. " +
                    "Only sign-in, list vaults, and vault-access validation are allowed."
            )
        }
    }
}
