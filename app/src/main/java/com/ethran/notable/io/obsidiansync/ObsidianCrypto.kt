package com.ethran.notable.io.obsidiansync

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * scrypt key derivation and AES-256-GCM encryption for the Obsidian Sync protocol.
 * Ported from [obsync/internal/crypto](https://github.com/bpauli/obsync).
 */
object ObsidianCrypto {

    private const val SCRYPT_N = 32768
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val KEY_LEN = 32
    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128

    /** Derives a 32-byte key from password and salt (NFKC-normalized), matching Obsidian v3. */
    fun deriveKey(password: String, salt: String): ByteArray {
        val pw = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        val s = Normalizer.normalize(salt, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return SCrypt.generate(pw, s, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LEN)
    }

    /** Key hash for encryption version 0 (standard/managed). */
    fun keyHash(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).toHex()

    /** Key hash for encryption versions 2 and 3 (E2E). */
    fun keyHashV2(key: ByteArray, salt: String): String {
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        val info = "ObsidianKeyHash".toByteArray(Charsets.UTF_8)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(key, saltBytes, info))
        val derived = ByteArray(KEY_LEN)
        hkdf.generateBytes(derived, 0, KEY_LEN)
        return derived.toHex()
    }

    /** Returns the keyhash appropriate for [encryptionVersion]. */
    fun computeKeyHash(key: ByteArray, salt: String, encryptionVersion: Int): String =
        when (encryptionVersion) {
            0 -> keyHash(key)
            2, 3 -> keyHashV2(key, salt)
            else -> throw IllegalArgumentException("Unsupported encryption version: $encryptionVersion")
        }

    /** Encrypts [plaintext] with AES-256-GCM; 12-byte random nonce is prepended. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        return nonce + cipher.doFinal(plaintext)
    }

    /** Decrypts ciphertext produced by [encrypt]. */
    fun decrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= NONCE_SIZE) { "ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE)
        val ct = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        return cipher.doFinal(ct)
    }

    /** Encrypts a vault path; deterministic nonce. Returns base64(nonce||ciphertext). */
    fun encryptPath(key: ByteArray, path: String): String {
        val plaintext = path.toByteArray(Charsets.UTF_8)
        val nonce = pathNonce(plaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        val ct = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(nonce + ct)
    }

    /** Decrypts a base64 path from [encryptPath]. */
    fun decryptPath(key: ByteArray, encoded: String): String {
        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 encoding", e)
        }
        return String(decrypt(key, data), Charsets.UTF_8)
    }

    /** Encrypts a path with hex encoding (encryption version 0). */
    fun encryptPathHex(key: ByteArray, path: String): String {
        val plaintext = path.toByteArray(Charsets.UTF_8)
        val nonce = pathNonce(plaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        val ct = cipher.doFinal(plaintext)
        return (nonce + ct).toHex()
    }

    /** Decrypts a hex path from [encryptPathHex]. */
    fun decryptPathHex(key: ByteArray, encoded: String): String {
        val data = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(decrypt(key, data), Charsets.UTF_8)
    }

    /** Encrypts [path] using the format for [encryptionVersion]. */
    fun encodePath(key: ByteArray, path: String, encryptionVersion: Int): String =
        if (encryptionVersion == 0) encryptPathHex(key, path) else encryptPath(key, path)

    /** Decrypts [encoded] using the format for [encryptionVersion]. */
    fun decodePath(key: ByteArray, encoded: String, encryptionVersion: Int): String =
        if (encryptionVersion == 0) decryptPathHex(key, encoded) else decryptPath(key, encoded)

    private fun pathNonce(plaintext: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        return hash.copyOfRange(0, NONCE_SIZE)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
