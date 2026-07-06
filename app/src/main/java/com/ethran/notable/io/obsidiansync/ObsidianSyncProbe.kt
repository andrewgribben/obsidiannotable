package com.ethran.notable.io.obsidiansync

/**
 * Read-only connectivity check against Obsidian Sync.
 *
 * Never opens a WebSocket and never reads or writes vault files on disk.
 * Safe to run with real credentials during development.
 */
class ObsidianSyncProbe(
    private val api: ObsidianApiClient = ObsidianApiClient()
) {

    data class VaultSummary(
        val id: String,
        val name: String,
        val encryptionVersion: Int,
        val host: String
    )

    data class ProbeResult(
        val email: String,
        val displayName: String,
        val vaults: List<VaultSummary>,
        /** Set when [ProbeCredentials.validateVault] matched and vault-access succeeded. */
        val validatedVault: VaultSummary? = null,
        val syncHost: String? = null
    )

    data class ProbeCredentials(
        val email: String,
        val password: String,
        val mfa: String = "",
        /** Optional vault id or name to validate E2E keyhash via /vault/access. */
        val validateVault: String? = null,
        /** Required when [validateVault] points at an E2E vault (encryption v2/v3). */
        val e2ePassword: String? = null
    )

    /**
     * Signs in and lists vaults. Optionally validates vault access (still no file sync).
     */
    fun probe(credentials: ProbeCredentials): ProbeResult {
        require(credentials.email.isNotBlank()) { "email required" }
        require(credentials.password.isNotBlank()) { "password required" }

        val signin = api.signin(credentials.email, credentials.password, credentials.mfa)
        val listed = api.listVaults(signin.token)
        val summaries = listed.allVaults().map { vault ->
            VaultSummary(
                id = vault.id,
                name = vault.name,
                encryptionVersion = vault.encryptionVersion,
                host = vault.host
            )
        }

        val validateTarget = credentials.validateVault?.trim().orEmpty()
        if (validateTarget.isBlank()) {
            return ProbeResult(
                email = signin.email,
                displayName = signin.name,
                vaults = summaries
            )
        }

        val vault = api.resolveVault(listed, validateTarget)
            ?: throw IllegalArgumentException("Vault not found: $validateTarget")

        val e2ePassword = credentials.e2ePassword.orEmpty()
        if (vault.encryptionVersion in 2..3 && e2ePassword.isBlank()) {
            throw IllegalArgumentException(
                "E2E password required to validate vault '${vault.name}'"
            )
        }

        val passwordForKey = e2ePassword.ifBlank { vault.password }
        val key = ObsidianCrypto.deriveKey(passwordForKey, vault.salt)
        val keyHash = ObsidianCrypto.computeKeyHash(key, vault.salt, vault.encryptionVersion)
        val syncHost = api.vaultAccess(
            token = signin.token,
            vaultUid = vault.id,
            keyHash = keyHash,
            fallbackHost = vault.host,
            encryptionVersion = vault.encryptionVersion
        )

        val validated = VaultSummary(
            id = vault.id,
            name = vault.name,
            encryptionVersion = vault.encryptionVersion,
            host = syncHost
        )
        return ProbeResult(
            email = signin.email,
            displayName = signin.name,
            vaults = summaries,
            validatedVault = validated,
            syncHost = syncHost
        )
    }
}
