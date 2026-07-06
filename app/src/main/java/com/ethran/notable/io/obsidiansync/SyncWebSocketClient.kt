package com.ethran.notable.io.obsidiansync

import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for Obsidian Sync.
 * Ported from [obsync/internal/sync](https://github.com/bpauli/obsync).
 */
class SyncWebSocketClient private constructor(
    private val webSocket: WebSocket,
    private val key: ByteArray,
    private val encVer: Int,
    val perFileMax: Long,
    private val pushQueue: LinkedBlockingQueue<String>,
    private val responseQueue: LinkedBlockingQueue<String>,
    private val binaryQueue: LinkedBlockingQueue<ByteArray>,
    private val closed: AtomicBoolean,
    private val stopHeartbeat: AtomicBoolean
) {

  companion object {
    const val CHUNK_SIZE = 2 * 1024 * 1024
    private const val PING_INTERVAL_MS = 20_000L
    private const val WRITE_TIMEOUT_MS = 30_000L
    private const val READ_TIMEOUT_MS = 60_000L

    fun connect(
        params: SyncConnectParams,
        httpClient: OkHttpClient = ObsidianApiClient.defaultHttpClient()
    ): SyncWebSocketClient {
      require(params.host.endsWith(".obsidian.md")) {
        "sync: invalid host '${params.host}': must end with .obsidian.md"
      }
      return connectToUrl("wss://${params.host}", params, httpClient)
    }

    /** Test entry point — bypasses host suffix validation. */
    internal fun connectForTest(
        wsUrl: String,
        params: SyncConnectParams,
        httpClient: OkHttpClient = ObsidianApiClient.defaultHttpClient()
    ): SyncWebSocketClient = connectToUrl(wsUrl, params, httpClient)

    private fun connectToUrl(
        wsUrl: String,
        params: SyncConnectParams,
        httpClient: OkHttpClient
    ): SyncWebSocketClient {
      ObsidianSyncSafety.requireMutatingSyncAllowed("connect")
      val pushQueue = LinkedBlockingQueue<String>()
      val responseQueue = LinkedBlockingQueue<String>()
      val binaryQueue = LinkedBlockingQueue<ByteArray>()
      val closed = AtomicBoolean(false)
      val stopHeartbeat = AtomicBoolean(false)
      val handshakeLatch = CountDownLatch(1)
      var handshakeError: Exception? = null
      var perFileMax = 0L
      val handshakeDone = AtomicBoolean(false)

      val request = Request.Builder().url(wsUrl).build()
      val json = SyncJson.instance

      val webSocket = httpClient.newWebSocket(
          request,
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              val init = SyncInitMessage(
                  token = params.token,
                  id = params.vaultUid,
                  keyhash = params.keyHash,
                  encryptionVersion = params.encryptionVersion,
                  version = params.version,
                  initial = params.initial,
                  device = params.device
              )
              webSocket.send(json.encodeToString(init))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              if (!handshakeDone.get()) {
                try {
                  val resp = json.decodeFromString<SyncServerResponse>(text)
                  val err = resp.errorMessage()
                  if (err.isNotBlank()) {
                    handshakeError = SyncWebSocketException("sync: server error: $err")
                  } else if (resp.res != "ok") {
                    handshakeError = SyncWebSocketException(
                        "sync: unexpected init response: res=${resp.res} error=$err"
                    )
                  } else {
                    perFileMax = resp.perFileMax
                  }
                } catch (e: Exception) {
                  handshakeError = SyncWebSocketException("sync: read init response: ${e.message}", e)
                } finally {
                  handshakeDone.set(true)
                  handshakeLatch.countDown()
                }
                return
              }
              dispatchText(text, pushQueue, responseQueue)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
              if (!handshakeDone.get()) return
              binaryQueue.offer(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              signalClosed(pushQueue, responseQueue, binaryQueue, closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              if (!handshakeDone.get()) {
                handshakeError = SyncWebSocketException("sync: connect: ${t.message}", t)
                handshakeDone.set(true)
                handshakeLatch.countDown()
              }
              signalClosed(pushQueue, responseQueue, binaryQueue, closed)
            }
          }
      )

      if (!handshakeLatch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        webSocket.cancel()
        throw SyncWebSocketException("sync: init handshake timed out")
      }
      handshakeError?.let { throw it }

      return SyncWebSocketClient(
          webSocket = webSocket,
          key = params.key,
          encVer = params.encryptionVersion,
          perFileMax = perFileMax,
          pushQueue = pushQueue,
          responseQueue = responseQueue,
          binaryQueue = binaryQueue,
          closed = closed,
          stopHeartbeat = stopHeartbeat
      )
    }

    private fun dispatchText(
        text: String,
        pushQueue: LinkedBlockingQueue<String>,
        responseQueue: LinkedBlockingQueue<String>
    ) {
      when (SyncJson.parseOp(text)) {
        "pong" -> Unit
        "push", "ready" -> pushQueue.offer(text)
        else -> responseQueue.offer(text)
      }
    }

    private fun signalClosed(
        pushQueue: LinkedBlockingQueue<String>,
        responseQueue: LinkedBlockingQueue<String>,
        binaryQueue: LinkedBlockingQueue<ByteArray>,
        closed: AtomicBoolean
    ) {
      if (closed.compareAndSet(false, true)) {
        pushQueue.clear()
        responseQueue.clear()
        binaryQueue.clear()
      }
    }
  }

  /** Reads the next push or ready notification from the server. */
  fun receivePush(): SyncPushMessage {
    val text = pollPush() ?: throw SyncWebSocketException("sync: connection closed")
    val json = SyncJson.instance
    val envelope = json.decodeFromString<SyncServerResponse>(text)
    if (envelope.op == "ready") {
      return SyncPushMessage(op = "ready", uid = envelope.version)
    }
    return json.decodeFromString(text)
  }

  fun pullFile(uid: Long): ByteArray {
    ObsidianSyncSafety.requireMutatingSyncAllowed("pull")
    val json = SyncJson.instance
    webSocket.send(json.encodeToString(SyncPullRequest(uid = uid)))

    val sizeText = pollResponse() ?: throw SyncWebSocketException("sync: connection closed")
    val sizeResp = json.decodeFromString<SyncPullSizeResponse>(sizeText)
    if (sizeResp.error.isNotBlank()) {
      throw SyncWebSocketException("sync: pull error: ${sizeResp.error}")
    }
    if (sizeResp.deleted) throw SyncWebSocketErrors.fileDeleted
    if (sizeResp.size == 0L && sizeResp.pieces == 0) return ByteArray(0)

    var pieces = sizeResp.pieces
    if (pieces == 0) pieces = 1

    val encrypted = ByteArray(sizeResp.size.toInt())
    var offset = 0
    repeat(pieces) { i ->
      val chunk = pollBinary()
        ?: throw SyncWebSocketException("sync: read pull chunk $i: connection closed")
      chunk.copyInto(encrypted, offset)
      offset += chunk.size
    }
    return ObsidianCrypto.decrypt(key, encrypted)
  }

  fun pushFile(
      path: String,
      data: ByteArray,
      hash: String,
      size: Long,
      ctime: Long,
      mtime: Long,
      folder: Boolean = false
  ) {
    ObsidianSyncSafety.requireMutatingSyncAllowed("push")
    val json = SyncJson.instance
    val encrypted = ObsidianCrypto.encrypt(key, data)
    val encryptedPath = ObsidianCrypto.encodePath(key, path, encVer)
    val encryptedHash = ObsidianCrypto.encodePath(key, hash, encVer)
    var pieces = (encrypted.size + CHUNK_SIZE - 1) / CHUNK_SIZE
    if (pieces == 0) pieces = 1

    val meta = SyncPushMetadata(
        path = encryptedPath,
        extension = path.fileExtension(),
        hash = encryptedHash,
        size = encrypted.size.toLong(),
        ctime = ctime,
        mtime = mtime,
        folder = folder,
        pieces = pieces
    )
    webSocket.send(json.encodeToString(meta))

    val metaText = pollResponse() ?: throw SyncWebSocketException("sync: connection closed")
    val metaResp = json.decodeFromString<SyncServerResponse>(metaText)
    val err = metaResp.errorMessage()
    if (err.isNotBlank()) {
      if (err.lowercase().contains("size over limit")) throw SyncWebSocketErrors.fileTooLarge
      throw SyncWebSocketException("sync: push error: $err")
    }
    if (metaResp.res == "ok" || metaResp.op == "ok") return

    repeat(pieces) { i ->
      val start = i * CHUNK_SIZE
      val end = minOf(start + CHUNK_SIZE, encrypted.size)
      val chunk = encrypted.copyOfRange(start, end)
      if (!webSocket.send(ByteString.of(*chunk))) {
        throw SyncWebSocketException("sync: send push chunk $i failed")
      }
      val chunkText = pollResponse()
        ?: throw SyncWebSocketException("sync: read chunk $i response: connection closed")
      val chunkResp = json.decodeFromString<SyncServerResponse>(chunkText)
      val chunkErr = chunkResp.errorMessage()
      if (chunkErr.isNotBlank()) {
        throw SyncWebSocketException("sync: push chunk $i error: $chunkErr")
      }
    }
  }

  fun pushDelete(path: String) {
    ObsidianSyncSafety.requireMutatingSyncAllowed("delete")
    val json = SyncJson.instance
    val encryptedPath = ObsidianCrypto.encodePath(key, path, encVer)
    val meta = SyncPushMetadata(
        path = encryptedPath,
        extension = path.fileExtension(),
        deleted = true
    )
    webSocket.send(json.encodeToString(meta))
    val respText = pollResponse() ?: throw SyncWebSocketException("sync: connection closed")
    val resp = json.decodeFromString<SyncServerResponse>(respText)
    val err = resp.errorMessage()
    if (err.isNotBlank()) throw SyncWebSocketException("sync: delete error: $err")
  }

  fun ping() {
    webSocket.send(SyncJson.instance.encodeToString(SyncPingMessage()))
  }

  fun startHeartbeat() {
    Thread {
      while (!stopHeartbeat.get() && !closed.get()) {
        try {
          Thread.sleep(PING_INTERVAL_MS)
          if (stopHeartbeat.get() || closed.get()) break
          ping()
        } catch (_: InterruptedException) {
          break
        } catch (_: Exception) {
          break
        }
      }
    }.apply {
      isDaemon = true
      name = "obsidian-sync-heartbeat"
      start()
    }
  }

  fun close() {
    stopHeartbeat.set(true)
    webSocket.close(1000, "normal closure")
    signalClosed(pushQueue, responseQueue, binaryQueue, closed)
  }

  private fun pollPush(): String? =
      pushQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)

  private fun pollResponse(): String? =
      responseQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)

  private fun pollBinary(): ByteArray? =
      binaryQueue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)

  private fun String.fileExtension(): String {
    val name = substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot >= 0) name.substring(dot + 1) else ""
  }
}
