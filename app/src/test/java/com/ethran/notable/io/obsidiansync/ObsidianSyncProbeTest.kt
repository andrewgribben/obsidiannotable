package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObsidianSyncProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun probe_listsVaultsWithoutValidation() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        SigninResponse(token = "tok", email = "u@example.com", name = "User")
                    )
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        ListVaultsResponse(
                            vaults = listOf(
                                ObsidianVault(
                                    id = "v1",
                                    name = "Notes",
                                    salt = "s1",
                                    host = "sync-1.obsidian.md",
                                    encryptionVersion = 3
                                )
                            )
                        )
                    )
                )
            )

            val api = ObsidianApiClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                httpClient = OkHttpClient.Builder().build()
            )
            val result = ObsidianSyncProbe(api).probe(
                ObsidianSyncProbe.ProbeCredentials(
                    email = "u@example.com",
                    password = "secret"
                )
            )

            assertEquals("u@example.com", result.email)
            assertEquals(1, result.vaults.size)
            assertEquals("Notes", result.vaults[0].name)
            assertNull(result.validatedVault)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun probe_validatesVaultAccessWithoutMutatingSync() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(SigninResponse(token = "tok", email = "u@example.com"))
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        ListVaultsResponse(
                            vaults = listOf(
                                ObsidianVault(
                                    id = "v1",
                                    name = "Notes",
                                    salt = "testsalt",
                                    host = "sync-1.obsidian.md",
                                    encryptionVersion = 3
                                )
                            )
                        )
                    )
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(VaultAccessResponse(host = "sync-1.obsidian.md"))
                )
            )

            val api = ObsidianApiClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                httpClient = OkHttpClient.Builder().build()
            )
            val result = ObsidianSyncProbe(api).probe(
                ObsidianSyncProbe.ProbeCredentials(
                    email = "u@example.com",
                    password = "secret",
                    validateVault = "Notes",
                    e2ePassword = "e2e-pass"
                )
            )

            assertNotNull(result.validatedVault)
            assertEquals("sync-1.obsidian.md", result.syncHost)
            assertEquals(3, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            assertEquals("/vault/access", server.takeRequest().path)
        }
    }
}
