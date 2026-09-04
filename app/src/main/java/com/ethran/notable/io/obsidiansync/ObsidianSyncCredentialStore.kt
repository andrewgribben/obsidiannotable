package com.ethran.notable.io.obsidiansync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Obsidian Sync account password and per-vault E2E passwords.
 * One account can sync multiple remote vaults.
 */
@Singleton
class ObsidianSyncCredentialStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccount(email: String, password: String, mfa: String = "") {
        prefs.edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_MFA, mfa)
            .apply()
    }

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }

    fun getMfa(): String = prefs.getString(KEY_MFA, "").orEmpty()

    fun hasAccount(): Boolean = getEmail() != null && getPassword() != null

    fun saveE2ePassword(vaultConfigId: String, password: String) {
        prefs.edit().putString(e2eKey(vaultConfigId), password).apply()
    }

    fun getE2ePassword(vaultConfigId: String): String? =
        prefs.getString(e2eKey(vaultConfigId), null)

    fun clearE2ePassword(vaultConfigId: String) {
        prefs.edit().remove(e2eKey(vaultConfigId)).apply()
    }

    fun clearAccount() {
        val editor = prefs.edit()
        editor.remove(KEY_EMAIL)
        editor.remove(KEY_PASSWORD)
        editor.remove(KEY_MFA)
        prefs.all.keys.filter { it.startsWith(E2E_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun e2eKey(vaultConfigId: String): String = "$E2E_PREFIX$vaultConfigId"

    companion object {
        private const val PREFS_NAME = "obsidian_sync_credentials"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_MFA = "mfa"
        private const val E2E_PREFIX = "e2e_"
    }
}
