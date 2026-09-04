package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ObsidianApiClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun client(server: MockWebServer): ObsidianApiClient =
        ObsidianApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = OkHttpClient.Builder().build()
        )

    @Test
    fun signin_success() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody(
                        json.encodeToString(
                            SigninResponse(
                                token = "jwt-token-123",
                                email = "user@example.com",
                                name = "Test User"
                            )
                        )
                    )
            )
            val resp = client(server).signin("user@example.com", "secret")
            assertEquals("jwt-token-123", resp.token)
            assertEquals("user@example.com", resp.email)
            val recorded = server.takeRequest()
            assertEquals("/user/signin", recorded.path)
            assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("app://obsidian.md", recorded.getHeader("Origin"))
        }
    }

    @Test
    fun signin_withMfa() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(SigninResponse(token = "jwt-mfa-token", email = "user@example.com"))
                )
            )
            val resp = client(server).signin("user@example.com", "secret", "123456")
            assertEquals("jwt-mfa-token", resp.token)
        }
    }

    @Test
    fun signin_httpError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":"invalid credentials"}""")
            )
            try {
                client(server).signin("bad@example.com", "wrong")
                fail("expected ObsidianApiException")
            } catch (e: ObsidianApiException) {
                assertEquals(401, e.statusCode)
                assertEquals("invalid credentials", e.apiMessage)
                assertEquals("api: invalid credentials (HTTP 401)", e.message)
            }
        }
    }

    @Test
    fun signin_errorInBodyWithHttp200() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"error":"Login failed, please double check your email and password."}"""
                )
            )
            try {
                client(server).signin("bad@example.com", "wrong")
                fail("expected ObsidianApiException")
            } catch (e: ObsidianApiException) {
                assertEquals(200, e.statusCode)
                assertTrue(e.apiMessage.isNotBlank())
            }
        }
    }

    @Test
    fun listVaults_success() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        ListVaultsResponse(
                            vaults = listOf(
                                ObsidianVault(id = "v1", name = "Personal", salt = "salt1", encryptionVersion = 3)
                            ),
                            shared = listOf(
                                ObsidianVault(id = "v2", name = "Team", salt = "salt2", encryptionVersion = 3)
                            )
                        )
                    )
                )
            )
            val resp = client(server).listVaults("my-token")
            assertEquals(1, resp.vaults.size)
            assertEquals("v1", resp.vaults[0].id)
            assertEquals(1, resp.shared.size)
            assertEquals("v2", resp.shared[0].id)
        }
    }

    @Test
    fun vaultAccess_success() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(VaultAccessResponse(host = "sync-123.obsidian.md"))
                )
            )
            val host = client(server).vaultAccess(
                token = "my-token",
                vaultUid = "vault-123",
                keyHash = "keyhash-abc",
                fallbackHost = "sync-123.obsidian.md",
                encryptionVersion = 3
            )
            assertEquals("sync-123.obsidian.md", host)
        }
    }

    @Test
    fun vaultAccess_fallsBackToProvidedHost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(json.encodeToString(VaultAccessResponse(host = ""))))
            val host = client(server).vaultAccess(
                token = "my-token",
                vaultUid = "vault-123",
                keyHash = "keyhash-abc",
                fallbackHost = "sync-fallback.obsidian.md",
                encryptionVersion = 3
            )
            assertEquals("sync-fallback.obsidian.md", host)
        }
    }

    @Test
    fun resolveVault_byIdOrName() {
        val vaults = ListVaultsResponse(
            vaults = listOf(ObsidianVault(id = "abc", name = "My Vault"))
        )
        val api = ObsidianApiClient()
        assertNotNull(api.resolveVault(vaults, "abc"))
        assertNotNull(api.resolveVault(vaults, "My Vault"))
    }
}
