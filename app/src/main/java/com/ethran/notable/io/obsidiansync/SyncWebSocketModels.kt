package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class SyncInitMessage(
    val op: String = "init",
    val token: String,
    val id: String,
    val keyhash: String,
    @SerialName("encryption_version")
    val encryptionVersion: Int,
    val version: Long = 0,
    val initial: Boolean = false,
    val device: String = ""
)

@Serializable
internal data class SyncPullRequest(
    val op: String = "pull",
    val uid: Long
)

@Serializable
internal data class SyncPingMessage(
    val op: String = "ping"
)

@Serializable
internal data class SyncPushMetadata(
    val op: String = "push",
    val path: String,
    val extension: String = "",
    val hash: String = "",
    val size: Long = 0,
    val ctime: Long = 0,
    val mtime: Long = 0,
    val folder: Boolean = false,
    val deleted: Boolean = false,
    val pieces: Int = 0
)

@Serializable
data class SyncPushMessage(
    val op: String = "",
    val path: String = "",
    val hash: String = "",
    val size: Long = 0,
    val ctime: Long = 0,
    val mtime: Long = 0,
    val folder: Boolean = false,
    val deleted: Boolean = false,
    val uid: Long = 0,
    val device: String = "",
    val pieces: Int = 0
)

@Serializable
internal data class SyncServerResponse(
    val res: String = "",
    val op: String = "",
    @SerialName("perFileMax")
    val perFileMax: Long = 0,
    val version: Long = 0,
    val error: String = "",
    val err: String = ""
) {
    fun errorMessage(): String = error.ifBlank { err }
}

@Serializable
internal data class SyncPullSizeResponse(
    val op: String = "",
    val size: Long = 0,
    val pieces: Int = 0,
    val deleted: Boolean = false,
    val error: String = "",
    val res: String = ""
)

data class SyncConnectParams(
    val host: String,
    val token: String,
    val vaultUid: String,
    val keyHash: String,
    val version: Long = 0,
    val initial: Boolean = false,
    val device: String = "",
    val encryptionVersion: Int = 3,
    val key: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SyncConnectParams
        return host == other.host && token == other.token && vaultUid == other.vaultUid &&
            keyHash == other.keyHash && version == other.version && initial == other.initial &&
            device == other.device && encryptionVersion == other.encryptionVersion &&
            key.contentEquals(other.key)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + vaultUid.hashCode()
        result = 31 * result + keyHash.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + initial.hashCode()
        result = 31 * result + device.hashCode()
        result = 31 * result + encryptionVersion
        result = 31 * result + key.contentHashCode()
        return result
    }
}

internal object SyncJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parseOp(text: String): String {
        return runCatching {
            val obj = instance.decodeFromString<JsonObject>(text)
            obj["op"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }
}
