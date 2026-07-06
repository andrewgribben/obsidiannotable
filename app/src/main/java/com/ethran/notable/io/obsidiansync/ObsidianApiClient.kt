package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Obsidian Sync REST API.
 * Ported from [obsync/internal/api](https://github.com/bpauli/obsync).
 */
class ObsidianApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json".toMediaType()

    /** Authenticates with email, password, and optional MFA code. */
    fun signin(email: String, password: String, mfa: String = ""): SigninResponse {
        val body = SigninRequest(email = email, password = password, mfa = mfa)
        return post("/user/signin", body)
    }

    /** Lists own and shared vaults for [token]. */
    fun listVaults(token: String): ListVaultsResponse {
        val body = ListVaultsRequest(token = token)
        return post("/vault/list", body)
    }

    /**
     * Validates keyhash and returns the sync WebSocket host.
     * For unencrypted vaults, returns [fallbackHost] when the response host is empty.
     */
    fun vaultAccess(
        token: String,
        vaultUid: String,
        keyHash: String,
        fallbackHost: String,
        encryptionVersion: Int
    ): String {
        val body = VaultAccessRequest(
            token = token,
            vaultUid = vaultUid,
            keyhash = keyHash,
            host = fallbackHost,
            encryptionVersion = encryptionVersion
        )
        val resp: VaultAccessResponse = post("/vault/access", body)
        return resp.host.ifBlank { fallbackHost }
    }

    /** Finds a vault by exact id or name (case-sensitive). */
    fun resolveVault(vaults: ListVaultsResponse, nameOrId: String): ObsidianVault? =
        vaults.allVaults().find { it.id == nameOrId || it.name == nameOrId }

    private inline fun <reified Req, reified Resp> post(path: String, body: Req): Resp {
        val requestBody = json.encodeToString(body).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Origin", "app://obsidian.md")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()

            if (response.code >= 400) {
                val apiMessage = runCatching {
                    json.decodeFromString<ApiErrorBody>(responseBody).error
                }.getOrDefault("")
                throw ObsidianApiException(response.code, apiMessage)
            }

            val errorInBody = runCatching {
                json.decodeFromString<ApiErrorBody>(responseBody).error
            }.getOrDefault("")
            if (errorInBody.isNotBlank()) {
                throw ObsidianApiException(response.code, errorInBody)
            }

            return json.decodeFromString<Resp>(responseBody)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.obsidian.md"

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
