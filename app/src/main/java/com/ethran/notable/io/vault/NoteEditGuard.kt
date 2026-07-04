package com.ethran.notable.io.vault

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process advisory lock ensuring only one editing surface (annotation mode, flip
 * side, handwriting insert) mutates a given vault note at a time. Split-screen
 * windows of the app share the process, so a process-wide registry covers the
 * multi-window case; on-disk safety is separately guaranteed by VaultFileStore's
 * hash-checked atomic writes.
 */
object NoteEditGuard {
    private val owners = ConcurrentHashMap<String, String>()

    fun noteKey(vaultId: String, relativePath: String) = "$vaultId:$relativePath"

    /** Acquires the note for [owner]. Re-acquiring by the same owner succeeds. */
    fun tryAcquire(noteKey: String, owner: String): Boolean =
        owners.compute(noteKey) { _, existing ->
            if (existing == null || existing == owner) owner else existing
        } == owner

    /** Releases the note if (and only if) [owner] holds it. */
    fun release(noteKey: String, owner: String) {
        owners.remove(noteKey, owner)
    }
}
