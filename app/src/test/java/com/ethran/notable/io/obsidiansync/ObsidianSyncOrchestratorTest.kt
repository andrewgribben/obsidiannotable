package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

class ObsidianSyncOrchestratorTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> withFullSync(block: () -> T): T {
        val previous = ObsidianSyncSafety.mode
        return try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            block()
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    private fun apiClient(server: MockWebServer): ObsidianApiClient =
        ObsidianApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            httpClient = OkHttpClient.Builder().build()
        )

    private fun enqueueAuth(server: MockWebServer) {
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
                                id = "vault-1",
                                name = "Test Vault",
                                password = "vault-password",
                                salt = "vault-salt",
                                host = "sync-1.obsidian.md",
                                encryptionVersion = 0
                            )
                        )
                    )
                )
            )
        )
        server.enqueue(
            MockResponse().setBody("""{"host":"sync-1.obsidian.md","allowed":true}""")
        )
    }

    @Test
    fun push_blockedInProbeOnly() {
        val previous = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.ProbeOnly
            assertThrows(IllegalStateException::class.java) {
                ObsidianSyncOrchestrator().push(
                    vaultRoot = createTempDirectory().toFile(),
                    credentials = testCredentials()
                )
            }
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    @Test
    fun push_unbootstrapped_throwsBootstrapRequired() {
        withFullSync {
            val vault = createTempDirectory().toFile()
            File(vault, "notes/a.md").apply {
                parentFile.mkdirs()
                writeText("hello")
            }
            assertThrows(BootstrapRequiredException::class.java) {
                ObsidianSyncOrchestrator().push(
                    vaultRoot = vault,
                    credentials = testCredentials()
                )
            }
        }
    }

    @Test
    fun push_noChanges_returnsZero() {
        withFullSync {
            val vault = createTempDirectory().toFile()
            val note = File(vault, "notes/a.md").apply {
                parentFile.mkdirs()
                writeText("hello")
            }
            val hash = com.ethran.notable.io.VaultFileStore.hashOf(note.readBytes())
            ObsidianSyncStateStore.save(
                vault,
                ObsidianSyncState(
                    vaultUid = "vault-1",
                    version = 5,
                    files = mutableMapOf(
                        "notes/a.md" to ObsidianSyncFileState(
                            hash = hash,
                            syncHash = hash,
                            mtime = note.lastModified(),
                            ctime = note.lastModified(),
                            size = note.length()
                        )
                    )
                )
            )

            MockWebServer().use { server ->
                enqueueAuth(server)
                val result = ObsidianSyncOrchestrator(api = apiClient(server)).push(
                    vaultRoot = vault,
                    credentials = testCredentials(e2ePassword = "vault-password")
                )
                assertEquals(0, result.filesPushed)
                assertEquals(0, result.filesDeleted)
            }
        }
    }

    @Test
    fun push_uploadsChangedFile() {
        withFullSync {
            val vault = createTempDirectory().toFile()
            val note = File(vault, "notes/a.md").apply {
                parentFile.mkdirs()
                writeText("push me")
            }

            val serverSocket = AtomicReference<WebSocket>()
            val server = MockWebServer()
            server.start()
            try {
                enqueueAuth(server)
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket.set(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                when {
                                    text.contains("\"op\":\"init\"") -> {
                                        webSocket.send(
                                            json.encodeToString(SyncServerResponse(res = "ok"))
                                        )
                                        webSocket.send(
                                            buildJsonObject {
                                                put("op", "ready")
                                                put("version", 5)
                                            }.toString()
                                        )
                                    }
                                    text.contains("\"op\":\"push\"") -> {
                                        webSocket.send("{}")
                                    }
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                        }
                    )
                )

                val wsUrl = server.url("/").toString()
                    .replace("http://", "ws://")
                val orchestrator = ObsidianSyncOrchestrator(
                    api = apiClient(server),
                    connect = { params ->
                        SyncWebSocketClient.connectForTest(wsUrl, params, OkHttpClient.Builder().build())
                    }
                )

                val result = orchestrator.push(
                    vaultRoot = vault,
                    credentials = testCredentials(e2ePassword = "vault-password"),
                    onlyPaths = setOf("notes/a.md")
                )
                assertEquals(0, result.filesDeleted)

                val state = ObsidianSyncStateStore.load(vault)
                assertEquals("vault-1", state.vaultUid)
                assertEquals(5, state.version)
                assertTrue(state.files.containsKey("notes/a.md"))
                assertEquals(
                    com.ethran.notable.io.VaultFileStore.hashOf(note.readBytes()),
                    state.files["notes/a.md"]!!.syncHash
                )
            } finally {
                serverSocket.get()?.close(1000, "done")
                server.shutdown()
            }
        }
    }

    @Test
    fun pull_writesRemoteFile() {
        withFullSync {
            val vault = createTempDirectory().toFile()
            val key = ObsidianCrypto.deriveKey("e2e-secret", "vault-salt")
            val plainPath = "notes/remote.md"
            val plainHash = com.ethran.notable.io.VaultFileStore.hashOf("remote content")
            val encryptedPath = ObsidianCrypto.encodePath(key, plainPath, encryptionVersion = 3)
            val encryptedHash = ObsidianCrypto.encodePath(key, plainHash, encryptionVersion = 3)
            val encryptedContent = ObsidianCrypto.encrypt(key, "remote content".toByteArray())

            val serverSocket = AtomicReference<WebSocket>()
            val server = MockWebServer()
            server.start()
            try {
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
                                        id = "vault-1",
                                        name = "Test Vault",
                                        password = "",
                                        salt = "vault-salt",
                                        host = "sync-1.obsidian.md",
                                        encryptionVersion = 3
                                    )
                                )
                            )
                        )
                    )
                )
                server.enqueue(
                    MockResponse().setBody("""{"host":"sync-1.obsidian.md","allowed":true}""")
                )
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket.set(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                when {
                                    text.contains("\"op\":\"init\"") -> {
                                        webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                                        webSocket.send(
                                            json.encodeToString(
                                                SyncPushMessage(
                                                    op = "push",
                                                    path = encryptedPath,
                                                    hash = encryptedHash,
                                                    size = "remote content".length.toLong(),
                                                    ctime = 1000,
                                                    mtime = 2000,
                                                    uid = 99,
                                                    pieces = 1
                                                )
                                            )
                                        )
                                        webSocket.send(
                                            buildJsonObject {
                                                put("op", "ready")
                                                put("version", 7)
                                            }.toString()
                                        )
                                    }
                                    text.contains("\"op\":\"pull\"") -> {
                                        webSocket.send(
                                            """{"op":"size","size":${encryptedContent.size},"pieces":1}"""
                                        )
                                        webSocket.send(ByteString.of(*encryptedContent))
                                    }
                                }
                            }
                        }
                    )
                )

                val wsUrl = server.url("/").toString().replace("http://", "ws://")
                val orchestrator = ObsidianSyncOrchestrator(
                    api = apiClient(server),
                    connect = { params ->
                        SyncWebSocketClient.connectForTest(wsUrl, params, OkHttpClient.Builder().build())
                    }
                )

                val result = orchestrator.pull(
                    vaultRoot = vault,
                    credentials = testCredentials(e2ePassword = "e2e-secret")
                )

                assertEquals(1, result.filesSynced)
                assertEquals(0, result.filesDeleted)
                assertEquals(7, result.version)

                val local = File(vault, plainPath)
                assertTrue(local.exists())
                assertEquals("remote content", local.readText())
                val fileState = ObsidianSyncStateStore.load(vault).files[plainPath]
                assertNotNull(fileState)
                assertEquals(plainHash, fileState!!.syncHash)
            } finally {
                serverSocket.get()?.close(1000, "done")
                server.shutdown()
            }
        }
    }

    @Test
    fun push_noChanges_withZeroVersion_refreshesServerVersion() {
        withFullSync {
            val vault = createTempDirectory().toFile()
            val note = File(vault, "notes/a.md").apply {
                parentFile.mkdirs()
                writeText("hello")
            }
            val hash = com.ethran.notable.io.VaultFileStore.hashOf(note.readBytes())
            ObsidianSyncStateStore.save(
                vault,
                ObsidianSyncState(
                    vaultUid = "vault-1",
                    version = 0,
                    files = mutableMapOf(
                        "notes/a.md" to ObsidianSyncFileState(
                            hash = hash,
                            syncHash = hash,
                            mtime = note.lastModified(),
                            ctime = note.lastModified(),
                            size = note.length()
                        )
                    )
                )
            )

            val serverSocket = AtomicReference<WebSocket>()
            val server = MockWebServer()
            server.start()
            try {
                enqueueAuth(server)
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket.set(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("\"op\":\"init\"")) {
                                    webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                                    webSocket.send(
                                        buildJsonObject {
                                            put("op", "ready")
                                            put("version", 42)
                                        }.toString()
                                    )
                                }
                            }
                        }
                    )
                )

                val wsUrl = server.url("/").toString().replace("http://", "ws://")
                val orchestrator = ObsidianSyncOrchestrator(
                    api = apiClient(server),
                    connect = { params ->
                        SyncWebSocketClient.connectForTest(wsUrl, params, OkHttpClient.Builder().build())
                    }
                )

                val result = orchestrator.push(
                    vaultRoot = vault,
                    credentials = testCredentials(e2ePassword = "vault-password")
                )

                assertEquals(0, result.filesPushed)
                assertEquals(0, result.filesDeleted)
                assertEquals(42, ObsidianSyncStateStore.load(vault).version)
            } finally {
                serverSocket.get()?.close(1000, "done")
                server.shutdown()
            }
        }
    }

    private fun testCredentials(e2ePassword: String = "e2e") =
        ObsidianSyncOrchestrator.Credentials(
            email = "u@example.com",
            password = "password",
            vaultIdOrName = "Test Vault",
            e2ePassword = e2ePassword
        )
}
