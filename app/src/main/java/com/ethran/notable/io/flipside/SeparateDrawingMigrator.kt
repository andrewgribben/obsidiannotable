package com.ethran.notable.io.flipside

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.io.VaultFileStore
import com.ethran.notable.io.excalidraw.ExcalidrawSerializer
import com.ethran.notable.io.resolveExternalStoragePath
import com.ethran.notable.io.resolveVaultAttachmentDir
import com.ethran.notable.io.vault.FLIP_SIDE_SUFFIX
import com.ethran.notable.io.vault.VaultIndexRegistry
import com.ethran.notable.io.vault.availableDrawingFile
import com.ethran.notable.io.vault.vaultRootDir
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.builtins.serializer
import org.json.JSONObject
import java.io.File

object SeparateDrawingMigrator {
    const val VERSION = 1
    private const val VERSION_KEY = "SEPARATE_DRAWING_MIGRATION_VERSION"
    private val log = ShipBook.getLogger("SeparateDrawingMigrator")

    data class Result(val migrated: Int, val failures: List<String>)

    suspend fun migrateAll(appRepository: AppRepository): Result {
        val completed = appRepository.kvProxy.get(VERSION_KEY, Int.serializer()) ?: 0
        if (completed >= VERSION) return Result(0, emptyList())

        var migrated = 0
        val failures = mutableListOf<String>()
        for (vault in GlobalAppSettings.current.normalizedVaults().vaults) {
            val result = migrateVault(vault)
            migrated += result.migrated
            failures += result.failures
        }
        if (failures.isEmpty()) {
            appRepository.kvProxy.setKv(VERSION_KEY, VERSION, Int.serializer())
        }
        VaultIndexRegistry.invalidateAll()
        return Result(migrated, failures)
    }

    internal fun migrateVault(vault: VaultConfig): Result {
        val root = vaultRootDir(vault) ?: return Result(0, listOf("${vault.displayName}: no vault root"))
        val syncRoot = resolveExternalStoragePath(vault.inboxPath)
        val attachmentDir = resolveVaultAttachmentDir(vault.inboxPath, vault.attachmentPath)
            ?: return Result(0, listOf("${vault.displayName}: no attachment folder"))
        val attachmentRelativeToSync = runCatching { attachmentDir.relativeTo(syncRoot) }.getOrNull()
        if (attachmentRelativeToSync == null ||
            attachmentRelativeToSync.path == ".." ||
            attachmentRelativeToSync.path.startsWith("../")
        ) {
            return Result(
                0,
                listOf("${vault.displayName}: attachment folder is outside the synced vault")
            )
        }

        var migrated = 0
        val failures = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter {
                it.isFile &&
                    it.name.endsWith(".md", ignoreCase = true) &&
                    !it.name.endsWith(FLIP_SIDE_SUFFIX, ignoreCase = true)
            }
            .forEach { noteFile ->
                val read = VaultFileStore.read(noteFile) ?: return@forEach
                if (!ExcalidrawSerializer.hasDrawingSection(read.content)) return@forEach
                val json = ExcalidrawSerializer.extractDrawingJson(read.content)
                if (json == null || runCatching { JSONObject(json) }.isFailure) {
                    failures += "${noteFile.name}: drawing could not be decoded"
                    return@forEach
                }
                // Do not rewrite unrelated standalone Obsidian Excalidraw documents.
                // Singularity-authored ink carries full-fidelity customData under this key.
                if (!json.contains("\"singularity\"")) return@forEach
                val drawingFile = availableDrawingFile(noteFile, vault)
                if (drawingFile == null) {
                    failures += "${noteFile.name}: drawing path unavailable"
                    return@forEach
                }
                val drawingRelative = runCatching {
                    drawingFile.relativeTo(syncRoot).path.replace('\\', '/')
                }.getOrNull()
                if (drawingRelative == null || drawingRelative == ".." ||
                    drawingRelative.startsWith("../")
                ) {
                    failures += "${noteFile.name}: drawing is outside vault"
                    return@forEach
                }

                val backup = migrationBackupFile(attachmentDir, root, noteFile)
                val backupReady = runCatching {
                    backup.parentFile?.mkdirs()
                    if (!backup.exists()) backup.writeText(read.content)
                    backup.isFile
                }.getOrDefault(false)
                if (!backupReady) {
                    failures += "${noteFile.name}: backup could not be created"
                    return@forEach
                }

                val rawDrawing = JSONObject(json).toString(2) + "\n"
                val drawingWrite = VaultFileStore.write(drawingFile, rawDrawing)
                if (drawingWrite !is VaultFileStore.WriteResult.Success ||
                    ExcalidrawSerializer.extractDrawingJson(
                        VaultFileStore.read(drawingFile)?.content.orEmpty()
                    ) == null
                ) {
                    VaultFileStore.delete(drawingFile)
                    failures += "${noteFile.name}: drawing verification failed"
                    return@forEach
                }

                val textOnly = ExcalidrawSerializer.stripDrawingFromUnified(read.content)
                val linked = ExcalidrawSerializer.withDrawingLink(textOnly, drawingRelative)
                when (VaultFileStore.write(noteFile, linked, expectedHash = read.hash)) {
                    is VaultFileStore.WriteResult.Success -> migrated++
                    else -> {
                        VaultFileStore.delete(drawingFile)
                        failures += "${noteFile.name}: note changed during migration"
                    }
                }
            }
        log.i("Migrated $migrated unified drawings in ${vault.displayName}; failures=${failures.size}")
        return Result(migrated, failures)
    }

    private fun migrationBackupFile(
        attachmentDir: File,
        vaultRoot: File,
        noteFile: File
    ): File {
        val relative = noteFile.relativeTo(vaultRoot).path.replace('\\', '/')
        return File(attachmentDir, ".singularity/migration-backups/$relative.unified-backup")
    }
}
