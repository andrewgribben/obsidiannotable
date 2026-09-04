package com.ethran.notable.io.obsidiansync

class SyncWebSocketException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SyncWebSocketErrors {
    val fileDeleted = SyncWebSocketException("sync: file deleted on server")
    val fileTooLarge = SyncWebSocketException("sync: file size over limit")
}
