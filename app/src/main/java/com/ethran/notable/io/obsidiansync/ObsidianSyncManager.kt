package com.ethran.notable.io.obsidiansync

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.ObsidianRemoteVaultRef
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.ObsidianLauncher
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.vault.VaultIndexRegistry
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
    private val activeSyncOperations = AtomicInteger(0)

    private val _uiState = MutableStateFlow(ObsidianSyncUiState())
    val uiState: StateFlow<ObsidianSyncUiState> = _uiState.asStateFlow()

    init {
        VaultFileStore.addWriteListener(::onVaultFileWritten)
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
                onComplete(Result.success(result))
            } catch (e: Exception) {
                log.e("Obsidian sign-in failed: ${e.message}")
                onComplete(Result.failure(e))
            }
        }
    }

    fun signOut(onComplete: (() -> Unit)? = null) {
        scope.launch {
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
                vaultRootDir(vault)?.isDirectory == true
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

    fun schedulePushForVault(vaultConfigId: String) {
        if (!GlobalAppSettings.current.obsidianSyncSignedIn) return
        val vault = GlobalAppSettings.current.normalizedVaults().vaults
            .find { it.id == vaultConfigId } ?: return
        if (!vault.syncEnabled || vault.obsidianVaultId.isBlank()) return

        debouncedPushJobs.remove(vaultConfigId)?.cancel()
        debouncedPushJobs[vaultConfigId] = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            pushVault(vault)
        }
    }

    private fun onVaultFileWritten(file: File) {
        val settings = GlobalAppSettings.current.normalizedVaults()
        val vaultId = vaultIdForFile(file, settings) ?: return
        schedulePushForVault(vaultId)
    }

    private suspend fun syncVaultLocked(vault: VaultConfig): VaultSyncResult {
        val mutex = vaultMutexes.getOrPut(vault.id) { Mutex() }
        return mutex.withLock { syncVault(vault) }
    }

    private suspend fun syncVault(vault: VaultConfig): VaultSyncResult {
        val root = vaultRootDir(vault)
        if (root == null || !root.isDirectory) {
            return VaultSyncResult(
                vaultId = vault.id,
                vaultName = vault.displayName,
                pulled = 0,
                pushed = 0,
                deleted = 0,
                version = 0,
                error = "Vault root not configured"
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
                val pull = orchestrator.pull(root, creds)
                val push = orchestrator.push(root, creds)
                VaultSyncResult(
                    vaultId = vault.id,
                    vaultName = vault.displayName,
                    pulled = pull.filesSynced,
                    pushed = push.filesPushed,
                    deleted = pull.filesDeleted + push.filesDeleted,
                    version = pull.version
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

    private suspend fun pushVault(vault: VaultConfig) {
        val root = vaultRootDir(vault) ?: return
        val creds = orchestratorCredentials(vault) ?: return
        beginSyncIndicator("Uploading ${vault.displayName}…", vault.id)
        try {
            withFullSync { orchestrator.push(root, creds) }
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

    companion object {
        private const val PUSH_DEBOUNCE_MS = 3_000L

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
