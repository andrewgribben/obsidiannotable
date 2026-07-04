package com.ethran.notable.io

import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val log = ShipBook.getLogger("VaultFileStore")

/**
 * Safe read/write pipeline for files inside Obsidian vaults.
 *
 * Guarantees:
 * - Reads return the content together with a content hash.
 * - Writes are atomic (temp file + rename) so Obsidian Sync never sees partial files.
 * - Conditional writes verify the on-disk hash still matches what was read; a mismatch
 *   (e.g. Obsidian Sync updated the file underneath us) surfaces as [WriteResult.Conflict]
 *   instead of silently overwriting.
 * - Per-path mutexes serialize concurrent jobs touching the same file.
 */
object VaultFileStore {

    /** Hash value representing "the file does not exist". */
    const val HASH_MISSING = "missing"

    data class ReadResult(val content: String, val hash: String)

    sealed class WriteResult {
        object Success : WriteResult()

        /** The file changed on disk since it was read. Nothing was written. */
        data class Conflict(val currentContent: String?, val currentHash: String) : WriteResult()

        data class Error(val message: String) : WriteResult()
    }

    private val pathLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(file: File): Mutex =
        pathLocks.getOrPut(file.absolutePath) { Mutex() }

    /** Runs [block] holding the per-path lock for [file]. */
    suspend fun <T> withFileLock(file: File, block: suspend () -> T): T =
        lockFor(file).withLock { block() }

    fun hashOf(content: String): String = hashOf(content.toByteArray(Charsets.UTF_8))

    fun hashOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Reads [file], returning content + hash, or null when the file doesn't exist / can't be read. */
    fun read(file: File): ReadResult? {
        return try {
            if (!file.exists()) return null
            val bytes = file.readBytes()
            ReadResult(String(bytes, Charsets.UTF_8), hashOf(bytes))
        } catch (e: Exception) {
            log.e("Failed to read ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /** Current hash of [file] on disk, or [HASH_MISSING]. */
    fun currentHash(file: File): String {
        return try {
            if (!file.exists()) HASH_MISSING else hashOf(file.readBytes())
        } catch (e: Exception) {
            log.e("Failed to hash ${file.absolutePath}: ${e.message}")
            HASH_MISSING
        }
    }

    /**
     * Atomically writes [content] to [file].
     *
     * When [expectedHash] is non-null the write only happens if the on-disk state still
     * matches: pass the hash from [read], or [HASH_MISSING] when the file is expected
     * not to exist yet. Pass null for an unconditional write.
     */
    fun write(file: File, content: String, expectedHash: String? = null): WriteResult {
        try {
            file.parentFile?.mkdirs()

            if (expectedHash != null) {
                val onDisk = currentHash(file)
                if (onDisk != expectedHash) {
                    log.w("Write conflict for ${file.absolutePath}: expected $expectedHash, on disk $onDisk")
                    return WriteResult.Conflict(
                        currentContent = read(file)?.content,
                        currentHash = onDisk
                    )
                }
            }

            val tempFile = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
            tempFile.writeText(content, Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                // Rename can fail across some filesystems; fall back to copy + delete.
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            log.i("Wrote ${content.length} chars to ${file.absolutePath}")
            return WriteResult.Success
        } catch (e: Exception) {
            log.e("Failed to write ${file.absolutePath}: ${e.message}")
            return WriteResult.Error(e.message ?: "unknown error")
        }
    }

    /**
     * Writes [content] to a sibling conflict-copy of [file]
     * (e.g. `Note (conflict 2026-07-04 093000).md`). Returns the file or null on failure.
     */
    fun writeConflictCopy(file: File, content: String): File? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isBlank()) "" else ".$it" }
        val conflictFile = File(file.parentFile, "$base (conflict $timestamp)$ext")
        return when (write(conflictFile, content)) {
            is WriteResult.Success -> conflictFile
            else -> null
        }
    }
}

/**
 * Background queue for vault save/sync work. Jobs for the same file are serialized by
 * [VaultFileStore.withFileLock]; [pendingCount] drives the pending-work badge so
 * navigation never blocks on saves.
 */
object VaultWriteQueue {
    private val log = ShipBook.getLogger("VaultWriteQueue")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /**
     * Enqueues [job] for [file]. Runs on IO, serialized per file path.
     */
    fun enqueue(file: File, description: String = "", job: suspend () -> Unit) {
        _pendingCount.value += 1
        scope.launch {
            try {
                VaultFileStore.withFileLock(file) { job() }
            } catch (e: Exception) {
                log.e("Queued vault job failed ($description, ${file.name}): ${e.message}")
            } finally {
                _pendingCount.value -= 1
            }
        }
    }
}
