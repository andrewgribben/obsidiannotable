package com.ethran.notable.io.vault

import java.io.File

/** Resolves image paths referenced from vault markdown notes. */
object VaultImageResolver {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

    fun resolve(vaultRoot: File, noteRelativePath: String, destination: String): File? {
        val dest = destination.trim()
        if (dest.isBlank()) return null
        if (dest.startsWith("http://") || dest.startsWith("https://")) return null

        val normalized = dest.replace('\\', '/').trimStart('/')
        val noteDir = noteRelativePath.substringBeforeLast('/', "")

        val candidates = buildList {
            if (noteDir.isNotEmpty()) add(File(vaultRoot, "$noteDir/$normalized"))
            add(File(vaultRoot, normalized))
        }

        return candidates.firstOrNull { it.isFile && it.exists() && isImageFile(it) }
    }

    fun isImageFile(file: File): Boolean =
        file.extension.lowercase() in IMAGE_EXTENSIONS
}
