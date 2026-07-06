package com.ethran.notable.io.obsidiansync

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional live probe against Obsidian Sync. Skipped unless env vars are set.
 *
 * Read-only: sign-in, list vaults, optional vault-access validation.
 * Does not connect WebSocket or touch vault files.
 *
 * Run locally (never commit credentials):
 * ```
 * OBSIDIAN_SYNC_TEST_EMAIL=you@example.com \
 * OBSIDIAN_SYNC_TEST_PASSWORD=... \
 * OBSIDIAN_SYNC_TEST_VAULT="My Vault" \
 * OBSIDIAN_SYNC_TEST_E2E_PASSWORD=... \
 * ./gradlew testDebugUnitTest
 * ```
 */
class ObsidianSyncLiveProbeTest {

    @Test
    fun liveProbe_readOnly() {
        val email = System.getenv(ENV_EMAIL)?.trim().orEmpty()
        val password = System.getenv(ENV_PASSWORD).orEmpty()
        assumeTrue("Set $ENV_EMAIL and $ENV_PASSWORD to run live probe", email.isNotBlank() && password.isNotBlank())

        val mfa = System.getenv(ENV_MFA).orEmpty()
        val validateVault = System.getenv(ENV_VAULT)?.trim()?.takeIf { it.isNotBlank() }
        val e2ePassword = System.getenv(ENV_E2E)?.takeIf { it.isNotBlank() }

        val result = ObsidianSyncProbe().probe(
            ObsidianSyncProbe.ProbeCredentials(
                email = email,
                password = password,
                mfa = mfa,
                validateVault = validateVault,
                e2ePassword = e2ePassword
            )
        )

        require(result.email.isNotBlank()) { "sign-in returned empty email" }
        require(result.vaults.isNotEmpty()) { "account has no vaults" }
        if (validateVault != null) {
            require(result.validatedVault != null) { "vault validation failed" }
            require(!result.syncHost.isNullOrBlank()) { "sync host missing after validation" }
        }
    }

    companion object {
        const val ENV_EMAIL = "OBSIDIAN_SYNC_TEST_EMAIL"
        const val ENV_PASSWORD = "OBSIDIAN_SYNC_TEST_PASSWORD"
        const val ENV_MFA = "OBSIDIAN_SYNC_TEST_MFA"
        const val ENV_VAULT = "OBSIDIAN_SYNC_TEST_VAULT"
        const val ENV_E2E = "OBSIDIAN_SYNC_TEST_E2E_PASSWORD"
    }
}
