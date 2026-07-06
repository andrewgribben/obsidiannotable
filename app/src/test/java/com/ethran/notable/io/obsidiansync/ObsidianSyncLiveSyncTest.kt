package com.ethran.notable.io.obsidiansync

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional live push/pull against Obsidian Sync. Skipped unless env vars are set.
 *
 * Requires [ObsidianSyncSafety.mode] = FullSync for the duration of the test.
 * Never commit credentials.
 *
 * ```
 * OBSIDIAN_SYNC_TEST_EMAIL=you@example.com \
 * OBSIDIAN_SYNC_TEST_PASSWORD=... \
 * OBSIDIAN_SYNC_TEST_VAULT="My Vault" \
 * OBSIDIAN_SYNC_TEST_E2E_PASSWORD=... \
 * OBSIDIAN_SYNC_TEST_VAULT_ROOT=/path/to/vault/root \
 * ./gradlew testDebugUnitTest --tests '*.ObsidianSyncLiveSyncTest'
 * ```
 */
class ObsidianSyncLiveSyncTest {

    @Test
    fun livePushThenPull() {
        val email = System.getenv(ENV_EMAIL)?.trim().orEmpty()
        val password = System.getenv(ENV_PASSWORD).orEmpty()
        val vaultName = System.getenv(ENV_VAULT)?.trim().orEmpty()
        val e2ePassword = System.getenv(ENV_E2E).orEmpty()
        val vaultRootPath = System.getenv(ENV_VAULT_ROOT)?.trim().orEmpty()

        assumeTrue(
            "Set $ENV_EMAIL, $ENV_PASSWORD, $ENV_VAULT, $ENV_E2E, $ENV_VAULT_ROOT",
            email.isNotBlank() && password.isNotBlank() && vaultName.isNotBlank() &&
                e2ePassword.isNotBlank() && vaultRootPath.isNotBlank()
        )

        val vaultRoot = File(vaultRootPath)
        assumeTrue("Vault root must exist: $vaultRootPath", vaultRoot.isDirectory)

        val previousMode = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            val credentials = ObsidianSyncOrchestrator.Credentials(
                email = email,
                password = password,
                mfa = System.getenv(ENV_MFA).orEmpty(),
                vaultIdOrName = vaultName,
                e2ePassword = e2ePassword,
                device = "Singularity-live-test"
            )
            val orchestrator = ObsidianSyncOrchestrator()
            val push = orchestrator.push(vaultRoot, credentials)
            val pull = orchestrator.pull(vaultRoot, credentials)
            require(push.filesPushed >= 0)
            require(pull.version >= 0)
        } finally {
            ObsidianSyncSafety.mode = previousMode
        }
    }

    companion object {
        const val ENV_EMAIL = ObsidianSyncLiveProbeTest.ENV_EMAIL
        const val ENV_PASSWORD = ObsidianSyncLiveProbeTest.ENV_PASSWORD
        const val ENV_MFA = ObsidianSyncLiveProbeTest.ENV_MFA
        const val ENV_VAULT = ObsidianSyncLiveProbeTest.ENV_VAULT
        const val ENV_E2E = ObsidianSyncLiveProbeTest.ENV_E2E
        const val ENV_VAULT_ROOT = "OBSIDIAN_SYNC_TEST_VAULT_ROOT"
    }
}
