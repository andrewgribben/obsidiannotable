package com.ethran.notable.io.obsidiansync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ObsidianCryptoTest {

    private fun testKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun deriveKey_isDeterministic() {
        val key1 = ObsidianCrypto.deriveKey("password123", "salt456")
        val key2 = ObsidianCrypto.deriveKey("password123", "salt456")
        assertEquals(32, key1.size)
        assertArrayEquals(key1, key2)

        val key3 = ObsidianCrypto.deriveKey("password123", "different_salt")
        assertFalse(key1.contentEquals(key3))
    }

    @Test
    fun deriveKey_nfkcNormalization() {
        val key1 = ObsidianCrypto.deriveKey("\u2126", "salt")
        val key2 = ObsidianCrypto.deriveKey("\u03A9", "salt")
        assertArrayEquals(key1, key2)
    }

    @Test
    fun keyHash_isDeterministic() {
        val key = testKey()
        assertEquals(ObsidianCrypto.keyHash(key), ObsidianCrypto.keyHash(key))
    }

    @Test
    fun encryptDecrypt_roundTrip() {
        val key = testKey()
        val plaintext = "hello, obsidian sync!".toByteArray(Charsets.UTF_8)
        val ciphertext = ObsidianCrypto.encrypt(key, plaintext)
        assertFalse(plaintext.contentEquals(ciphertext))
        assertArrayEquals(plaintext, ObsidianCrypto.decrypt(key, ciphertext))
    }

    @Test
    fun encrypt_usesRandomNonce() {
        val key = testKey()
        val plaintext = "same data".toByteArray(Charsets.UTF_8)
        val ct1 = ObsidianCrypto.encrypt(key, plaintext)
        val ct2 = ObsidianCrypto.encrypt(key, plaintext)
        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun encryptDecrypt_emptyPlaintext() {
        val key = testKey()
        val ciphertext = ObsidianCrypto.encrypt(key, ByteArray(0))
        assertEquals(0, ObsidianCrypto.decrypt(key, ciphertext).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_tooShort() {
        ObsidianCrypto.decrypt(testKey(), byteArrayOf(1, 2, 3))
    }

    @Test
    fun encryptDecryptPath_roundTrip() {
        val key = testKey()
        val path = "notes/daily/2024-03-04.md"
        val encrypted = ObsidianCrypto.encryptPath(key, path)
        assertNotEquals(path, encrypted)
        assertEquals(path, ObsidianCrypto.decryptPath(key, encrypted))
    }

    @Test
    fun encryptPath_isDeterministic() {
        val key = testKey()
        val path = "notes/test.md"
        assertEquals(
            ObsidianCrypto.encryptPath(key, path),
            ObsidianCrypto.encryptPath(key, path)
        )
    }

    @Test
    fun encryptPath_differentPaths() {
        val key = testKey()
        val enc1 = ObsidianCrypto.encryptPath(key, "path/a.md")
        val enc2 = ObsidianCrypto.encryptPath(key, "path/b.md")
        assertNotEquals(enc1, enc2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decryptPath_invalidBase64() {
        ObsidianCrypto.decryptPath(testKey(), "not-valid-base64!!!")
    }

    @Test
    fun encryptDecrypt_largeData() {
        val key = testKey()
        val plaintext = ByteArray(2 * 1024 * 1024).also { SecureRandom().nextBytes(it) }
        val ciphertext = ObsidianCrypto.encrypt(key, plaintext)
        assertArrayEquals(plaintext, ObsidianCrypto.decrypt(key, ciphertext))
    }

    @Test
    fun computeKeyHash_version0() {
        val key = testKey()
        assertEquals(ObsidianCrypto.keyHash(key), ObsidianCrypto.computeKeyHash(key, "salt", 0))
    }

    @Test
    fun computeKeyHash_version3() {
        val key = testKey()
        assertEquals(
            ObsidianCrypto.keyHashV2(key, "mysalt"),
            ObsidianCrypto.computeKeyHash(key, "mysalt", 3)
        )
    }
}
