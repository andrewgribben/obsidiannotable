package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SigninRequest(
    val email: String,
    val password: String,
    val mfa: String = ""
)

@Serializable
data class SigninResponse(
    val token: String = "",
    val email: String = "",
    val name: String = ""
)

@Serializable
internal data class ListVaultsRequest(
    val token: String,
    @SerialName("supported_encryption_version")
    val supportedEncryptionVersion: Int = 3
)

@Serializable
data class ObsidianVault(
    val id: String = "",
    val name: String = "",
    val password: String = "",
    val salt: String = "",
    val host: String = "",
    @SerialName("encryption_version")
    val encryptionVersion: Int = 0
)

@Serializable
data class ListVaultsResponse(
    val vaults: List<ObsidianVault> = emptyList(),
    val shared: List<ObsidianVault> = emptyList()
) {
    fun allVaults(): List<ObsidianVault> = vaults + shared
}

@Serializable
internal data class VaultAccessRequest(
    val token: String,
    @SerialName("vault_uid")
    val vaultUid: String,
    val keyhash: String,
    val host: String,
    @SerialName("encryption_version")
    val encryptionVersion: Int
)

@Serializable
internal data class VaultAccessResponse(
    val host: String = "",
    val allowed: Boolean = false
)

@Serializable
internal data class ApiErrorBody(
    val error: String = ""
)
