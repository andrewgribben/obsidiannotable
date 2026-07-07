package com.ethran.notable.io.obsidiansync

import com.ethran.notable.io.VaultFileStore
import java.io.File

/**
 * One-shot push/pull against Obsidian Sync for a local vault directory.
 * Ported from [obsync/internal/cmd/push.go](https://github.com/bpauli/obsync) and pull.go.
 */
class ObsidianSyncOrchestrator(
    private val api: ObsidianApiClient = ObsidianApiClient(),
    private val connect: (SyncConnectParams) -> ObsidianSyncConnection = { SyncWebSocketClient.connect(it) }
) {

    data class Credentials(
        val email: String,
        val password: String,
        val mfa: String = "",
        val vaultIdOrName: String,
        val e2ePassword: String,
        val device: String = "Singularity"
    )

    data class PushResult(
        val filesPushed: Int,
        val filesDeleted: Int
    )

    data class PullResult(
        val filesSynced: Int,
        val filesDeleted: Int,
        val version: Long
    )

    private data class Session(
        val token: String,
        val vault: ObsidianVault,
        val key: ByteArray,
        val keyHash: String,
        val syncHost: String
    )

    fun push(
        vaultRoot: File,
        credentials: Credentials,
        onlyPaths: Set<String>? = null
    ): PushResult {
        ObsidianSyncSafety.requireMutatingSyncAllowed("push")
        require(vaultRoot.isDirectory) { "vault root must be a directory" }

        val state = ObsidianSyncStateStore.load(vaultRoot)
        val localFiles = ObsidianVaultScanner.scan(vaultRoot)
        if (onlyPaths == null && ObsidianSyncStateStore.needsBootstrap(state) && localFiles.isNotEmpty()) {
            throw BootstrapRequiredException(
                "Vault has ${localFiles.size} local files but no sync state; pull before push"
            )
        }

        val session = openSession(credentials)
        state.vaultUid = session.vault.id
        var pushPaths = localFiles.filter { (path, hash) ->
            val existing = state.files[path]
            existing == null || existing.syncHash != hash
        }.keys
        if (onlyPaths != null) {
            pushPaths = pushPaths.intersect(onlyPaths)
        }
        val sortedPushPaths = pushPaths.sorted()
        val deletePaths = if (onlyPaths == null) {
            state.files.keys.filter { it !in localFiles }.sorted()
        } else {
            emptyList()
        }

        if (sortedPushPaths.isEmpty() && deletePaths.isEmpty()) {
            if (state.version == 0L) {
                refreshServerVersion(vaultRoot, session, state, credentials)
            }
            return PushResult(filesPushed = 0, filesDeleted = 0)
        }

        val client = openSyncClient(session, state, credentials)
        try {
            val version = drainUntilReady(client)

            var pushed = 0
            for (path in sortedPushPaths) {
                val file = File(vaultRoot, path)
                val data = file.readBytes()
                val hash = localFiles.getValue(path)
                val mtime = file.lastModified()
                val now = System.currentTimeMillis()
                client.pushFile(
                    path = path,
                    data = data,
                    hash = hash,
                    size = data.size.toLong(),
                    ctime = now,
                    mtime = mtime,
                    folder = false
                )
                state.files[path] = ObsidianSyncFileState(
                    hash = hash,
                    syncHash = hash,
                    mtime = mtime,
                    ctime = now,
                    size = data.size.toLong()
                )
                pushed++
                ObsidianSyncStateStore.saveIfNeeded(vaultRoot, state, pushed)
            }

            var deleted = 0
            for (path in deletePaths) {
                client.pushDelete(path)
                state.files.remove(path)
                deleted++
                ObsidianSyncStateStore.saveIfNeeded(vaultRoot, state, pushed + deleted)
            }

            state.version = version
            ObsidianSyncStateStore.save(vaultRoot, state)
            return PushResult(filesPushed = pushed, filesDeleted = deleted)
        } catch (e: Exception) {
            if (state.files.isNotEmpty()) {
                runCatching { ObsidianSyncStateStore.save(vaultRoot, state) }
            }
            throw e
        } finally {
            client.close()
        }
    }

    fun pull(vaultRoot: File, credentials: Credentials): PullResult {
        ObsidianSyncSafety.requireMutatingSyncAllowed("pull")
        vaultRoot.mkdirs()

        val session = openSession(credentials)
        val state = ObsidianSyncStateStore.load(vaultRoot)
        state.vaultUid = session.vault.id

        val initial = state.version == 0L
        if (initial) {
            state.files.clear()
        }
        val client = openSyncClient(session, state, credentials, initial = initial)
        try {
            val pushes = mutableListOf<SyncPushMessage>()
            while (true) {
                val msg = client.receivePush()
                if (msg.op == "ready") {
                    state.version = msg.uid
                    break
                }
                if (msg.op == "push") {
                    pushes.add(msg)
                }
            }

            var synced = 0
            var deleted = 0
            val encVer = session.vault.encryptionVersion
            for (msg in pushes) {
                if (msg.path.isBlank()) continue
                val plainPath = ObsidianCrypto.decodePathLenient(session.key, msg.path, encVer)
                if (msg.deleted) {
                    val local = File(vaultRoot, plainPath)
                    if (local.exists() && !local.delete()) {
                        throw IllegalStateException("failed to delete ${local.absolutePath}")
                    }
                    state.files.remove(plainPath)
                    deleted++
                    continue
                }
                if (msg.folder) {
                    File(vaultRoot, plainPath).mkdirs()
                    continue
                }

                val content = try {
                    client.pullFile(msg.uid)
                } catch (e: SyncWebSocketException) {
                    if (e === SyncWebSocketErrors.fileDeleted) continue
                    throw e
                }

                val local = File(vaultRoot, plainPath)
                local.parentFile?.mkdirs()
                local.writeBytes(content)
                if (msg.mtime > 0) {
                    local.setLastModified(msg.mtime)
                }

                val contentHash = VaultFileStore.hashOf(content)

                state.files[plainPath] = ObsidianSyncFileState(
                    hash = contentHash,
                    syncHash = contentHash,
                    mtime = msg.mtime,
                    ctime = msg.ctime,
                    size = msg.size
                )
                synced++
                ObsidianSyncStateStore.saveIfNeeded(vaultRoot, state, synced + deleted)
            }

            ObsidianSyncStateStore.reconcileHashesFromDisk(vaultRoot, state)
            ObsidianSyncStateStore.save(vaultRoot, state)
            return PullResult(filesSynced = synced, filesDeleted = deleted, version = state.version)
        } catch (e: Exception) {
            if (state.files.isNotEmpty() || state.version > 0L) {
                runCatching { ObsidianSyncStateStore.save(vaultRoot, state) }
            }
            throw e
        } finally {
            client.close()
        }
    }

    private fun openSession(credentials: Credentials): Session {
        require(credentials.email.isNotBlank()) { "email required" }
        require(credentials.password.isNotBlank()) { "password required" }
        require(credentials.vaultIdOrName.isNotBlank()) { "vault required" }

        val signin = ObsidianApiRetry.withRetry {
            api.signin(credentials.email, credentials.password, credentials.mfa)
        }
        val listed = ObsidianApiRetry.withRetry { api.listVaults(signin.token) }
        val vault = api.resolveVault(listed, credentials.vaultIdOrName)
            ?: throw IllegalArgumentException("Vault not found: ${credentials.vaultIdOrName}")

        val passwordForKey = credentials.e2ePassword.ifBlank {
            if (vault.encryptionVersion in 2..3) {
                throw IllegalArgumentException("E2E password required for vault '${vault.name}'")
            }
            vault.password
        }
        val key = ObsidianCrypto.deriveKey(passwordForKey, vault.salt)
        val keyHash = ObsidianCrypto.computeKeyHash(key, vault.salt, vault.encryptionVersion)
        val syncHost = ObsidianApiRetry.withRetry {
            api.vaultAccess(
            token = signin.token,
            vaultUid = vault.id,
            keyHash = keyHash,
            fallbackHost = vault.host,
            encryptionVersion = vault.encryptionVersion
            )
        }
        return Session(
            token = signin.token,
            vault = vault,
            key = key,
            keyHash = keyHash,
            syncHost = syncHost
        )
    }

    private fun refreshServerVersion(
        vaultRoot: File,
        session: Session,
        state: ObsidianSyncState,
        credentials: Credentials
    ) {
        val client = openSyncClient(session, state, credentials)
        try {
            state.version = drainUntilReady(client)
            ObsidianSyncStateStore.save(vaultRoot, state)
        } finally {
            client.close()
        }
    }

    private fun openSyncClient(
        session: Session,
        state: ObsidianSyncState,
        credentials: Credentials,
        initial: Boolean = false
    ): ObsidianSyncConnection = connect(
        SyncConnectParams(
            host = session.syncHost,
            token = session.token,
            vaultUid = session.vault.id,
            keyHash = session.keyHash,
            version = state.version,
            initial = initial,
            device = credentials.device,
            encryptionVersion = session.vault.encryptionVersion,
            key = session.key
        )
    )

    private fun drainUntilReady(client: ObsidianSyncConnection): Long {
        while (true) {
            val msg = client.receivePush()
            if (msg.op == "ready") return msg.uid
        }
    }
}
