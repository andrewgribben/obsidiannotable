package com.ethran.notable.io.obsidiansync

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.ObsidianRemoteVaultRef
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.ObsidianLauncher
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.obsidianSyncVaultRoot
import com.ethran.notable.io.vault.vaultRootDir
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

@Singleton
class ObsidianSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: ObsidianSyncCredentialStore,
    private val orchestrator: ObsidianSyncOrchestrator,
    private val probe: ObsidianSyncProbe,
) {
    private val log = ShipBook.getLogger("ObsidianSyncManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vaultMutexes = ConcurrentHashMap<String, Mutex>()
    private val debouncedPushJobs = ConcurrentHashMap<String, Job>()
    private val pendingPushPaths = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingFullPushes = ConcurrentHashMap.newKeySet<String>()
    private val activeSyncOperations = AtomicInteger(0)
    @Volatile
    private var lastBackgroundPullAt = 0L
    private var periodicPullJob: Job? = null

    private val _uiState = MutableStateFlow(ObsidianSyncUiState())
    val uiState: StateFlow<ObsidianSyncUiState> = _uiState.asStateFlow()

    init {
        VaultFileStore.addWriteListener(::onVaultFileWritten)
        if (credentials.hasAccount()) {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            ensurePeriodicPullRunning()
        }
    }

    data class ObsidianSyncUiState(
        val syncing: Boolean = false,
        val vaultId: String? = null,
        val statusMessage: String? = null,
    )

    data class VaultSyncResult(
        val vaultId: String,
        val vaultName: String,
        val pulled: Int,
        val pushed: Int,
        val deleted: Int,
        val version: Long,
        val error: String? = null,
    )

    data class MultiVaultSyncResult(
        val results: List<VaultSyncResult>,
        val warning: String? = null,
    ) {
        val succeeded: Boolean get() = results.isNotEmpty() && results.all { it.error == null }
    }

    fun signIn(
        email: String,
        password: String,
        mfa: String = "",
        onComplete: (Result<ObsidianSyncProbe.ProbeResult>) -> Unit
    ) {
        scope.launch {
            try {
                val result = probe.probe(
                    ObsidianSyncProbe.ProbeCredentials(
                        email = email,
                        password = password,
                        mfa = mfa
                    )
                )
                credentials.saveAccount(email, password, mfa)
                ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
                ensurePeriodicPullRunning()
                onComplete(Result.success(result))
            } catch (e: Exception) {
                log.e("Obsidian sign-in failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    fun signOut(onComplete: (() -> Unit)? = null) {
        scope.launch {
            periodicPullJob?.cancel()
            periodicPullJob = null
            credentials.clearAccount()
            onComplete?.invoke()
        }
    }

    fun saveE2ePassword(vaultConfigId: String, password: String) {
        credentials.saveE2ePassword(vaultConfigId, password)
    }

    fun settingsAfterSignIn(
        settings: AppSettings,
        probeResult: ObsidianSyncProbe.ProbeResult
    ): AppSettings = settings.copy(
        obsidianSyncEmail = probeResult.email,
        obsidianSyncSignedIn = true,
        obsidianRemoteVaults = probeResult.vaults.map {
            ObsidianRemoteVaultRef(
                id = it.id,
                name = it.name,
                encryptionVersion = it.encryptionVersion
            )
        }
    )

    fun settingsAfterSignOut(settings: AppSettings): AppSettings = settings.copy(
        obsidianSyncEmail = "",
        obsidianSyncSignedIn = false,
        obsidianRemoteVaults = emptyList(),
        vaults = settings.vaults.map {
            it.copy(syncEnabled = false, obsidianVaultId = "", obsidianVaultName = "")
        }
    )

    fun syncEnabledVaults(settings: AppSettings): List<VaultConfig> =
        settings.normalizedVaults().vaults.filter { vault ->
            vault.syncEnabled &&
                vault.obsidianVaultId.isNotBlank() &&
                vault.inboxPath.isNotBlank() &&
                obsidianSyncVaultRoot(vault)?.isDirectory == true
        }

    fun syncAll(
        settings: AppSettings,
        onComplete: (MultiVaultSyncResult) -> Unit
    ) {
        scope.launch {
            val targets = syncEnabledVaults(settings)
            if (targets.isEmpty()) {
                onComplete(
                    MultiVaultSyncResult(
                        results = emptyList(),
                        warning = "No vaults configured for Obsidian Sync"
                    )
                )
                return@launch
            }
            if (!credentials.hasAccount()) {
                onComplete(
                    MultiVaultSyncResult(
                        results = emptyList(),
                        warning = "Sign in to Obsidian Sync in Settings first"
                    )
                )
                return@launch
            }

            beginSyncIndicator("Syncing…")
            val warning = dualClientWarning()
            val results = mutableListOf<VaultSyncResult>()
            try {
                for (vault in targets) {
                    results += syncVaultLocked(vault)
                }
                VaultIndexRegistry.invalidateAll()
            } finally {
                endSyncIndicator(formatSyncSummary(results))
            }
            onComplete(MultiVaultSyncResult(results = results, warning = warning))
        }
    }

    /** Pull-only sync for one vault (no push). Used before opening notes and background refresh. */
    suspend fun pullVault(vault: VaultConfig, showIndicator: Boolean = false): VaultSyncResult {
        if (!GlobalAppSettings.current.obsidianSyncSignedIn) {
            return skippedPullResult(vault)
        }
        if (!vault.syncEnabled || vault.obsidianVaultId.isBlank()) {
            return skippedPullResult(vault)
        }
        if (!credentials.hasAccount()) {
            return skippedPullResult(vault, error = "Missing Obsidian credentials")
        }
        val mutex = vaultMutexes.getOrPut(vault.id) { Mutex() }
        return mutex.withLock { executePullVault(vault, showIndicator) }
    }

    /** Pull when vault has sync enabled; returns null when sync is not configured. */
    suspend fun pullVaultIfEnabled(vault: VaultConfig): VaultSyncResult? {
        if (!GlobalAppSettings.current.obsidianSyncSignedIn || !vault.syncEnabled) return null
        return pullVault(vault, showIndicator = false)
    }

    /** Pull all sync-enabled vaults (no push). */
    suspend fun pullAllEnabledVaults(showIndicator: Boolean = false) {
        if (!GlobalAppSettings.current.obsidianSyncSignedIn || !credentials.hasAccount()) return
        val targets = syncEnabledVaults(GlobalAppSettings.current)
        for (vault in targets) {
            pullVault(vault, showIndicator = showIndicator)
        }
        VaultIndexRegistry.invalidateAll()
    }

    /** Called on app foreground; debounced to avoid hammering the server. */
    fun onAppForeground() {
        if (!credentials.hasAccount() || !GlobalAppSettings.current.obsidianSyncSignedIn) return
        val now = System.currentTimeMillis()
        if (now - lastBackgroundPullAt < RESUME_PULL_DEBOUNCE_MS) return
        scope.launch {
            pullAllEnabledVaults(showIndicator = false)
            lastBackgroundPullAt = System.currentTimeMillis()
        }
    }

    fun ensurePeriodicPullRunning() {
        if (periodicPullJob?.isActive == true) return
        if (!credentials.hasAccount()) return
        periodicPullJob = scope.launch {
            while (true) {
                delay(PERIODIC_PULL_MS)
                if (!credentials.hasAccount() || !GlobalAppSettings.current.obsidianSyncSignedIn) {
                    continue
                }
                pullAllEnabledVaults(showIndicator = false)
                lastBackgroundPullAt = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    fun schedulePushForVault(vaultConfigId: String, onlyPaths: Set<String>? = null) {
        if (!GlobalAppSettings.current.obsidianSyncSignedIn) return
        val vault = GlobalAppSettings.current.normalizedVaults().vaults
            .find { it.id == vaultConfigId } ?: return
        if (!vault.syncEnabled || vault.obsidianVaultId.isBlank()) return

        if (onlyPaths == null) {
            pendingFullPushes.add(vaultConfigId)
            pendingPushPaths.remove(vaultConfigId)
        } else if (!pendingFullPushes.contains(vaultConfigId)) {
            pendingPushPaths.getOrPut(vaultConfigId) { mutableSetOf() }.addAll(onlyPaths)
        }
        if (debouncedPushJobs[vaultConfigId]?.isActive == true) return
        debouncedPushJobs[vaultConfigId] = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            while (true) {
                val batch = synchronized(this@ObsidianSyncManager) {
                    when {
                        pendingFullPushes.remove(vaultConfigId) -> {
                            pendingPushPaths.remove(vaultConfigId)
                            true to null
                        }
                        pendingPushPaths.containsKey(vaultConfigId) -> {
                            true to pendingPushPaths.remove(vaultConfigId)?.toSet()
                        }
                        else -> {
                            debouncedPushJobs.remove(vaultConfigId)
                            false to null
                        }
                    }
                }
                if (!batch.first) break
                pushVault(vault, batch.second)
            }
        }
    }

    private fun onVaultFileWritten(file: File) {
        val settings = GlobalAppSettings.current.normalizedVaults()
        val vaultId = vaultIdForFile(file, settings) ?: return
        val vault = settings.normalizedVaults().vaults.find { it.id == vaultId } ?: return
        val root = obsidianSyncVaultRoot(vault) ?: return
        val relativePath = runCatching {
            file.relativeTo(root).path.replace(File.separatorChar, '/')
        }.getOrNull() ?: return
        if (relativePath == ".." || relativePath.startsWith("../")) return
        schedulePushForVault(vaultId, setOf(relativePath))
    }

    private suspend fun syncVaultLocked(vault: VaultConfig): VaultSyncResult {
        val mutex = vaultMutexes.getOrPut(vault.id) { Mutex() }
        return mutex.withLock { syncVault(vault) }
    }

    private suspend fun executePullVault(
        vault: VaultConfig,
        showIndicator: Boolean
    ): VaultSyncResult {
        val root = obsidianSyncVaultRoot(vault)
        if (root == null || !root.isDirectory) {
            return VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = "Vault sync root not configured"
            )
        }
        val creds = orchestratorCredentials(vault)
            ?: return VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = "Missing Obsidian credentials"
            )

        if (showIndicator) {
            _uiState.value = ObsidianSyncUiState(
                syncing = true,
                vaultId = vault.id,
                statusMessage = "Syncing ${vault.displayName}…"
            )
        }

        return try {
            withFullSync {
                ObsidianSyncStateStore.prepareSyncRootState(vault, root)
                val pull = orchestrator.pull(root, creds)
                log.i(
                    "Pull ${vault.displayName}: synced=${pull.filesSynced} " +
                        "deleted=${pull.filesDeleted} version=${pull.version}"
                )
                VaultIndexRegistry.invalidateAll()
                VaultSyncResult(
                    vaultId = vault.id,
                    vaultName = vault.displayName,
                    pulled = pull.filesSynced,
                    pushed = 0,
                    deleted = pull.filesDeleted,
                    version = pull.version,
                    error = null
                )
            }
        } catch (e: Exception) {
            log.e("Pull failed for ${vault.displayName}: ${e.message}")
            VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = e.message ?: "pull failed"
            )
        }
    }

    private suspend fun syncVault(vault: VaultConfig): VaultSyncResult {
        val root = obsidianSyncVaultRoot(vault)
        if (root == null || !root.isDirectory) {
            return VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = "Vault sync root not configured"
            )
        }
        val creds = orchestratorCredentials(vault)
            ?: return VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = "Missing Obsidian credentials"
            )

        _uiState.value = ObsidianSyncUiState(
            syncing = true,
            vaultId = vault.id,
            statusMessage = "Syncing ${vault.displayName}…"
        )

        return try {
            withFullSync {
                ObsidianSyncStateStore.prepareSyncRootState(vault, root)

                var pulled = 0
                var pullDeleted = 0
                var version = 0L
                var pullError: String? = null
                try {
                    val pull = orchestrator.pull(root, creds)
                    pulled = pull.filesSynced
                    pullDeleted = pull.filesDeleted
                    version = pull.version
                    log.i(
                        "Pull ${vault.displayName}: synced=$pulled " +
                            "deleted=$pullDeleted version=$version"
                    )
                } catch (e: Exception) {
                    pullError = e.message ?: "pull failed"
                    log.e("Pull failed for ${vault.displayName}: $pullError")
                }

                var pushed = 0
                var pushDeleted = 0
                var pushError: String? = null
                try {
                    val push = orchestrator.push(root, creds)
                    pushed = push.filesPushed
                    pushDeleted = push.filesDeleted
                    log.i(
                        "Push ${vault.displayName}: pushed=$pushed deleted=$pushDeleted"
                    )
                } catch (e: BootstrapRequiredException) {
                    val state = ObsidianSyncStateStore.load(root)
                    val localFiles = ObsidianVaultScanner.scan(root)
                    val newPaths = localFiles.keys.filter { it !in state.files }.toSet()
                    if (newPaths.isEmpty()) {
                        pushError = e.message
                    } else {
                        log.w(
                            "Full push blocked for ${vault.displayName}; " +
                                "uploading ${newPaths.size} new file(s) only"
                        )
                        val push = orchestrator.push(root, creds, newPaths)
                        pushed = push.filesPushed
                        pushDeleted = push.filesDeleted
                    }
                } catch (e: Exception) {
                    pushError = e.message ?: "push failed"
                    log.e("Push failed for ${vault.displayName}: $pushError")
                }

                val error = listOfNotNull(pullError, pushError).joinToString("; ").ifBlank { null }
                VaultSyncResult(
                    vaultId = vault.id,
                    vaultName = vault.displayName,
                    pulled = pulled,
                    pushed = pushed,
                    deleted = pullDeleted + pushDeleted,
                    version = version,
                    error = error
                )
            }
        } catch (e: Exception) {
            log.e("Sync failed for ${vault.displayName}: ${e.message}")
            VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = e.message ?: "sync failed"
            )
        }
    }

    private suspend fun pushVault(vault: VaultConfig, onlyPaths: Set<String>? = null) {
        val root = obsidianSyncVaultRoot(vault) ?: return
        val creds = orchestratorCredentials(vault) ?: return
        val state = ObsidianSyncStateStore.load(root)
        if (ObsidianSyncStateStore.needsBootstrap(state) && onlyPaths == null) {
            log.w(
                "Skipping background push for ${vault.displayName}: " +
                    "vault not bootstrapped (run manual sync first)"
            )
            return
        }
        beginSyncIndicator("Uploading ${vault.displayName}…", vault.id)
        try {
            withFullSync {
                val result = orchestrator.push(root, creds, onlyPaths)
                log.i(
                    "Background push ${vault.displayName}: pushed=${result.filesPushed} " +
                        "paths=${onlyPaths?.size ?: "all"}"
                )
            }
        } catch (e: Exception) {
            log.e("Background push failed for ${vault.displayName}: ${e.message}")
        } finally {
            endSyncIndicator()
        }
    }

    private fun beginSyncIndicator(message: String, vaultId: String? = null) {
        if (activeSyncOperations.incrementAndGet() == 1) {
            _uiState.value = ObsidianSyncUiState(
                syncing = true,
                vaultId = vaultId,
                statusMessage = message
            )
        }
    }

    private fun endSyncIndicator(statusMessage: String? = null) {
        val remaining = activeSyncOperations.decrementAndGet()
        if (remaining <= 0) {
            activeSyncOperations.set(0)
            _uiState.value = ObsidianSyncUiState(
                syncing = false,
                statusMessage = statusMessage ?: _uiState.value.statusMessage
            )
        }
    }

    private fun orchestratorCredentials(vault: VaultConfig): ObsidianSyncOrchestrator.Credentials? {
        val email = credentials.getEmail() ?: return null
        val password = credentials.getPassword() ?: return null
        return ObsidianSyncOrchestrator.Credentials(
            email = email,
            password = password,
            mfa = credentials.getMfa(),
            vaultIdOrName = vault.obsidianVaultId,
            e2ePassword = credentials.getE2ePassword(vault.id).orEmpty(),
            device = "Singularity"
        )
    }

    private fun dualClientWarning(): String? {
        if (!ObsidianLauncher.isInstalled(context)) return null
        return "Obsidian app is installed. Avoid syncing the same vault in both " +
            "Singularity and the Obsidian app to prevent conflicts."
    }

    private fun formatSyncSummary(results: List<VaultSyncResult>): String? {
        if (results.isEmpty()) return null
        val ok = results.count { it.error == null }
        val pulled = results.sumOf { it.pulled }
        val pushed = results.sumOf { it.pushed }
        return if (ok == results.size) {
            "Synced $ok vault(s): pulled $pulled, pushed $pushed"
        } else {
            "Sync finished with errors in ${results.size - ok} vault(s)"
        }
    }

    private inline fun <T> withFullSync(block: () -> T): T {
        val previous = ObsidianSyncSafety.mode
        return try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            block()
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    private fun skippedPullResult(vault: VaultConfig, error: String? = null) = VaultSyncResult(
        vaultId = vault.id,
        vaultName = vault.displayName,
        pulled = 0,
        pushed = 0,
        deleted = 0,
        version = 0,
        error = error
    )

    companion object {
        private const val PUSH_DEBOUNCE_MS = 3_000L
        private const val PERIODIC_PULL_MS = 180_000L
        private const val RESUME_PULL_DEBOUNCE_MS = 30_000L

        fun vaultIdForFile(file: File, settings: AppSettings): String? {
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
            return settings.normalizedVaults().vaults.firstOrNull { vault ->
                val root = vaultRootDir(vault) ?: return@firstOrNull false
                val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
                canonical == rootPath || canonical.startsWith("$rootPath/")
            }?.id
        }
    }
}
