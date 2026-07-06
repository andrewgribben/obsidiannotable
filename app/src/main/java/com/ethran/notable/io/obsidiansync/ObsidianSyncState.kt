package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local sync metadata persisted as `.obsync-state.json` in the vault root.
 * Ported from [obsync/internal/sync/state.go](https://github.com/bpauli/obsync).
 */
@Serializable
data class ObsidianSyncFileState(
    val hash: String = "",
    @SerialName("sync_hash")
    val syncHash: String = "",
    val mtime: Long = 0,
    val ctime: Long = 0,
    val size: Long = 0
)

@Serializable
data class ObsidianSyncState(
    @SerialName("vault_uid")
    var vaultUid: String = "",
    var version: Long = 0,
    val files: MutableMap<String, ObsidianSyncFileState> = mutableMapOf()
)

@Serializable
private data class ObsidianSyncStateJson(
    @SerialName("vault_uid")
    val vaultUid: String = "",
    val version: Long = 0,
    val files: Map<String, ObsidianSyncFileState>? = null
)

object ObsidianSyncStateStore {

    const val STATE_FILE_NAME = ".obsync-state.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    fun statePath(vaultPath: File): File = File(vaultPath, STATE_FILE_NAME)

    fun load(vaultPath: File): ObsidianSyncState {
        val file = statePath(vaultPath)
        if (!file.exists()) {
            return ObsidianSyncState(files = mutableMapOf())
        }
        val text = file.readText(Charsets.UTF_8)
        val dto = json.decodeFromString<ObsidianSyncStateJson>(text)
        return ObsidianSyncState(
            vaultUid = dto.vaultUid,
            version = dto.version,
            files = dto.files?.toMutableMap() ?: mutableMapOf()
        )
    }

    fun save(vaultPath: File, state: ObsidianSyncState) {
        vaultPath.mkdirs()
        val target = statePath(vaultPath)
        val dto = ObsidianSyncStateJson(
            vaultUid = state.vaultUid,
            version = state.version,
            files = state.files
        )
        val payload = json.encodeToString(dto) + "\n"
        val tmp = File(vaultPath, "$STATE_FILE_NAME.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IllegalStateException("failed to replace ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("failed to commit ${target.absolutePath}")
        }
    }
}
