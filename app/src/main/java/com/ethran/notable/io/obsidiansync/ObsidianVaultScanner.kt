package com.ethran.notable.io.obsidiansync

import com.ethran.notable.io.VaultFileStore
import java.io.File

/**
 * Walks a vault directory and returns relative paths to SHA-256 content hashes.
 * Matches [obsync scanLocalFiles](https://github.com/bpauli/obsync/blob/main/internal/cmd/push.go).
 */
object ObsidianVaultScanner {

    fun scan(vaultRoot: File): Map<String, String> {
        require(vaultRoot.isDirectory) { "vault root must be a directory: ${vaultRoot.absolutePath}" }
        val files = linkedMapOf<String, String>()
        walk(vaultRoot, vaultRoot, files)
        return files
    }

    private fun walk(root: File, dir: File, out: MutableMap<String, String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (name == ObsidianSyncStateStore.STATE_FILE_NAME) continue
            if (name.startsWith('.') && name != ".obsidian") continue
            if (child.isDirectory) {
                walk(root, child, out)
                continue
            }
            val rel = child.relativeTo(root).path.replace(File.separatorChar, '/')
            out[rel] = VaultFileStore.hashOf(child.readBytes())
        }
    }
}
