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
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SyncWebSocketClientTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val httpClient = OkHttpClient.Builder().build()

    private fun testKey(): ByteArray = ByteArray(32) { it.toByte() }

    private fun testParams(
        token: String = "tok",
        vaultUid: String = "vault-1",
        key: ByteArray = testKey()
    ) = SyncConnectParams(
        host = "sync-1.obsidian.md",
        token = token,
        vaultUid = vaultUid,
        keyHash = "",
        version = 0,
        initial = false,
        device = "",
        encryptionVersion = 3,
        key = key
    )

    private fun <T> withFullSync(block: () -> T): T {
        val previous = ObsidianSyncSafety.mode
        return try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            block()
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    private fun connectTest(
        server: MockWebServer,
        params: SyncConnectParams = testParams()
    ): SyncWebSocketClient {
        val wsUrl = server.url("/").toString()
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        return SyncWebSocketClient.connectForTest(wsUrl = wsUrl, params = params, httpClient = httpClient)
    }

    private class TrackedWebSocketListener(
        private val delegate: WebSocketListener
    ) : WebSocketListener() {
        private val socketRef = AtomicReference<WebSocket>()

        fun serverSocket(): WebSocket? = socketRef.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socketRef.set(webSocket)
            delegate.onOpen(webSocket, response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) =
            delegate.onMessage(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
            delegate.onMessage(webSocket, bytes)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
            delegate.onClosing(webSocket, code, reason)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            delegate.onClosed(webSocket, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            delegate.onFailure(webSocket, t, response)
    }

    private fun <T> withWebSocketServer(
        listener: WebSocketListener,
        params: SyncConnectParams = testParams(),
        block: (SyncWebSocketClient) -> T
    ): T {
        val tracked = TrackedWebSocketListener(listener)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(tracked))
        server.start()
        var client: SyncWebSocketClient? = null
        return try {
            client = connectTest(server, params)
            block(client)
        } finally {
            client?.close()
            tracked.serverSocket()?.close(1000, "test done")
            server.shutdown()
        }
    }

    @Test
    fun connect_rejectsInvalidHost() {
        assertThrows(IllegalArgumentException::class.java) {
            withFullSync {
                SyncWebSocketClient.connect(
                    params = testParams().copy(host = "evil.example.com")
                )
            }
        }
    }

    @Test
    fun connect_blockedInProbeOnly() {
        val previous = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.ProbeOnly
            assertThrows(IllegalStateException::class.java) {
                SyncWebSocketClient.connect(params = testParams())
            }
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    @Test
    fun connect_success() {
        withFullSync {
            val initLatch = CountDownLatch(1)
            var capturedInit: String? = null

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (capturedInit == null) {
                            capturedInit = text
                            initLatch.countDown()
                            webSocket.send(
                                json.encodeToString(
                                    SyncServerResponse(res = "ok", perFileMax = 5_242_880)
                                )
                            )
                        }
                    }
                },
                params = testParams(token = "tok123", vaultUid = "vault-1").copy(
                    version = 42,
                    initial = true,
                    device = "boox"
                )
            ) { client ->
                assertTrue(initLatch.await(5, TimeUnit.SECONDS))
                assertNotNull(capturedInit)
                assertTrue(capturedInit!!.contains("\"op\":\"init\""))
                assertTrue(capturedInit!!.contains("\"token\":\"tok123\""))
                assertTrue(capturedInit!!.contains("\"id\":\"vault-1\""))
                assertEquals(5_242_880L, client.perFileMax)
            }
        }
    }

    @Test
    fun connect_serverError() {
        withFullSync {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            webSocket.send(
                                json.encodeToString(SyncServerResponse(error = "vault not found"))
                            )
                        }
                    }
                )
            )
            server.start()
            try {
                val ex = assertThrows(SyncWebSocketException::class.java) {
                    connectTest(server)
                }
                assertTrue(ex.message!!.contains("vault not found"))
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun receivePush_andReady() {
        withFullSync {
            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"op\":\"init\"")) {
                            webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            webSocket.send(
                                json.encodeToString(
                                    SyncPushMessage(
                                        op = "push",
                                        path = "encrypted-path",
                                        hash = "encrypted-hash",
                                        size = 1234,
                                        ctime = 1_709_553_600_000,
                                        mtime = 1_709_553_600_000,
                                        uid = 42,
                                        device = "server1",
                                        pieces = 1
                                    )
                                )
                            )
                            webSocket.send(
                                buildJsonObject {
                                    put("op", "ready")
                                    put("version", 100)
                                }.toString()
                            )
                        }
                    }
                }
            ) { client ->
                val push = client.receivePush()
                assertEquals("push", push.op)
                assertEquals(42, push.uid)
                assertEquals(1234, push.size)

                val ready = client.receivePush()
                assertEquals("ready", ready.op)
                assertEquals(100, ready.uid)
            }
        }
    }

    @Test
    fun receivePush_skipsPong() {
        withFullSync {
            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"op\":\"init\"")) {
                            webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            webSocket.send(buildJsonObject { put("op", "pong") }.toString())
                            webSocket.send(
                                json.encodeToString(
                                    SyncPushMessage(op = "push", uid = 1, path = "p")
                                )
                            )
                        }
                    }
                }
            ) { client ->
                val push = client.receivePush()
                assertEquals("push", push.op)
                assertEquals(1, push.uid)
            }
        }
    }

    @Test
    fun pullFile_singleChunk() {
        withFullSync {
            val key = testKey()
            val plaintext = "hello, obsidian vault!".toByteArray()
            val encrypted = ObsidianCrypto.encrypt(key, plaintext)

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"op\":\"init\"") -> {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                            text.contains("\"op\":\"pull\"") -> {
                                webSocket.send(
                                    buildJsonObject {
                                        put("op", "size")
                                        put("size", encrypted.size)
                                        put("pieces", 1)
                                    }.toString()
                                )
                                webSocket.send(ByteString.of(*encrypted))
                            }
                        }
                    }
                },
                params = testParams(key = key)
            ) { client ->
                val result = client.pullFile(42)
                assertEquals(String(plaintext), String(result))
            }
        }
    }

    @Test
    fun pullFile_multipleChunks() {
        withFullSync {
            val key = testKey()
            val plaintext = ByteArray(100) { (it % 256).toByte() }
            val encrypted = ObsidianCrypto.encrypt(key, plaintext)
            val mid = encrypted.size / 2
            val chunk1 = encrypted.copyOfRange(0, mid)
            val chunk2 = encrypted.copyOfRange(mid, encrypted.size)

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"op\":\"init\"") -> {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                            text.contains("\"op\":\"pull\"") -> {
                                webSocket.send(
                                    buildJsonObject {
                                        put("op", "size")
                                        put("size", encrypted.size)
                                        put("pieces", 2)
                                    }.toString()
                                )
                                webSocket.send(ByteString.of(*chunk1))
                                webSocket.send(ByteString.of(*chunk2))
                            }
                        }
                    }
                },
                params = testParams(key = key)
            ) { client ->
                val result = client.pullFile(1)
                assertEquals(plaintext.size, result.size)
                assertTrue(plaintext.contentEquals(result))
            }
        }
    }

    @Test
    fun pushFile() {
        withFullSync {
            val key = testKey()
            val plaintext = "push me to the vault".toByteArray()
            val received = mutableListOf<ByteArray>()
            var piecesSeen = 0

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"op\":\"init\"") -> {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                            text.contains("\"op\":\"push\"") && !text.contains("\"deleted\":true") -> {
                                val meta = json.decodeFromString<SyncPushMetadata>(text)
                                assertTrue(meta.pieces >= 1)
                                assertEquals(false, meta.folder)
                                piecesSeen = meta.pieces
                                webSocket.send("{}")
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        received.add(bytes.toByteArray())
                        webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                    }
                },
                params = testParams(key = key)
            ) { client ->
                client.pushFile(
                    path = "notes/test.md",
                    data = plaintext,
                    hash = "abc123",
                    size = plaintext.size.toLong(),
                    ctime = 1000,
                    mtime = 2000
                )

                val encrypted = received.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                assertEquals(piecesSeen, received.size)
                assertEquals(String(plaintext), String(ObsidianCrypto.decrypt(key, encrypted)))
            }
        }
    }

    @Test
    fun pushDelete() {
        withFullSync {
            val key = testKey()

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"op\":\"init\"") -> {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                            text.contains("\"deleted\":true") -> {
                                val meta = json.decodeFromString<SyncPushMetadata>(text)
                                assertEquals("push", meta.op)
                                assertTrue(meta.deleted)
                                assertTrue(meta.path.isNotBlank())
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                        }
                    }
                },
                params = testParams(key = key)
            ) { client ->
                client.pushDelete("notes/deleted.md")
            }
        }
    }

    @Test
    fun ping_sendsPingOp() {
        withFullSync {
            var sawPing = false

            withWebSocketServer(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"op\":\"init\"") -> {
                                webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                            }
                            text.contains("\"op\":\"ping\"") -> sawPing = true
                        }
                    }
                }
            ) { client ->
                client.ping()
                Thread.sleep(200)
                assertTrue(sawPing)
            }
        }
    }

    @Test
    fun pull_blockedInProbeOnly() {
        val previous = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.ProbeOnly
            val server = MockWebServer()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            webSocket.send(json.encodeToString(SyncServerResponse(res = "ok")))
                        }
                    }
                )
            )
            server.start()
            try {
                assertThrows(IllegalStateException::class.java) {
                    connectTest(server)
                }
            } finally {
                server.shutdown()
            }
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }
}
