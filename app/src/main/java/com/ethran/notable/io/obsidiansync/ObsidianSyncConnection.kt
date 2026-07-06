package com.ethran.notable.io.obsidiansync

/**
 * Active Obsidian Sync WebSocket session used by [ObsidianSyncOrchestrator].
 */
interface ObsidianSyncConnection {
    fun receivePush(): SyncPushMessage
    fun pullFile(uid: Long): ByteArray
    fun pushFile(
        path: String,
        data: ByteArray,
        hash: String,
        size: Long,
        ctime: Long,
        mtime: Long,
        folder: Boolean
    )
    fun pushDelete(path: String)
    fun close()
}
