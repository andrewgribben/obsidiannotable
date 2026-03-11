package com.ethran.notable.io

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.utils.UriUtils.getDataColumn
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val fileUtilsLog = ShipBook.getLogger("FileUtilsLogger")


fun getLinkedFilesDir(): File {
    val documentsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val legacyLinkedDir = File(documentsDir, "notable/Linked")

    val inboxPath = GlobalAppSettings.current.obsidianInboxPath
    val attachmentPath = GlobalAppSettings.current.obsidianAttachmentPath
    val vaultBase = resolveVaultAttachmentDir(inboxPath, attachmentPath)

    val linkedDir = if (vaultBase != null) {
        val targetDir = File(File(vaultBase, ".singularity"), "Linked")
        if (!targetDir.exists() && legacyLinkedDir.exists()) {
            copyDirectoryRecursively(legacyLinkedDir, targetDir)
        }
        targetDir
    } else {
        legacyLinkedDir
    }

    if (!linkedDir.exists()) linkedDir.mkdirs()
    return linkedDir
}

/**
 * True when the vault attachment path is set to a valid directory (not blank and not "/").
 * Use this so we never try to write to filesystem root.
 */
fun isAttachmentPathSet(path: String): Boolean {
    val t = path.trim()
    return t.isNotBlank() && t != "/"
}

/**
 * Resolves the attachment directory for Save & Exit PDF (and exports).
 * - [attachmentPath] blank or "/" → inbox folder (PDF next to the .md).
 * - [attachmentPath] starting with "/" or "Documents" → resolved as absolute storage path.
 * - [attachmentPath] containing "/" but not starting with ".." → full storage path from the folder picker
 *   (e.g. "Notes/Vault/Attachments") → resolved as absolute storage path.
 * - [attachmentPath] single-segment ("Attachments") or ".."-relative ("../Attachments") → relative to inbox.
 * Returns null if [inboxPath] is blank.
 */
fun resolveVaultAttachmentDir(inboxPath: String, attachmentPath: String): File? {
    if (inboxPath.isBlank()) return null
    val inboxDir = resolveExternalStoragePath(inboxPath)
    val trimmed = attachmentPath.trim().trim('/')
    return when {
        trimmed.isEmpty() -> inboxDir
        trimmed.startsWith("/") || trimmed.startsWith("Documents") ->
            resolveExternalStoragePath(attachmentPath.trim())
        // Multi-segment path not starting with ".." = absolute path from file picker
        trimmed.contains('/') && !trimmed.startsWith("..") ->
            resolveExternalStoragePath(attachmentPath.trim())
        // Single name ("Attachments") or relative ("../Attachments") = relative to inbox
        else -> File(inboxDir, trimmed)
    }
}

/**
 * Returns the path of [attachmentFullPath] relative to [inboxPath], so that
 * File(resolveExternalStoragePath(inboxPath), result) equals resolveExternalStoragePath(attachmentFullPath).
 * Both paths use "/" (e.g. "Documents/V/inbox", "Documents/V/attachments").
 * Optional: for manual entry of a path relative to inbox instead of using the folder picker.
 */
fun pathRelativeToInbox(inboxPath: String, attachmentFullPath: String): String {
    val inboxSegments = inboxPath.trim().trim('/').split('/').filter { it.isNotBlank() }
    val attachmentSegments = attachmentFullPath.trim().trim('/').split('/').filter { it.isNotBlank() }
    var common = 0
    while (common < inboxSegments.size && common < attachmentSegments.size && inboxSegments[common] == attachmentSegments[common]) {
        common++
    }
    val ups = List(inboxSegments.size - common) { ".." }
    val rest = attachmentSegments.drop(common)
    return (ups + rest).joinToString("/")
}

/**
 * Converts a tree URI from [Intent.ACTION_OPEN_DOCUMENT_TREE] to the path string format
 * used in settings (e.g. "Documents/MyVault/inbox"). Returns null if the URI cannot be
 * resolved (e.g. non-primary storage or unsupported provider).
 */
fun pathFromTreeUri(context: Context, uri: Uri): String? {
    if (!DocumentsContract.isTreeUri(uri)) return null
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (docId.isBlank()) return null
    // docId format for external storage: "primary:Documents/MyVault/inbox" or "0000-0000:path"
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val volume = docId.substring(0, colon)
    val relative = docId.substring(colon + 1).trimStart('/').replace('\\', '/')
    if (relative.isEmpty()) return null
    return when {
        volume.equals("primary", ignoreCase = true) || volume.equals("home", ignoreCase = true) ->
            relative
        else -> {
            fileUtilsLog.w("pathFromTreeUri: non-primary volume '$volume' - path may not resolve; docId=$docId")
            relative
        }
    }
}

/**
 * Resolves a path string to a File for external storage.
 * - "/" or blank → do not use for attachment dir; call [isAttachmentPathSet] first.
 * - Absolute paths ("/storage/...") → File(path)
 * - Paths under "Documents" → public Documents directory + rest (matches getDefaultExportDirectoryUri)
 * - Other relative paths → getExternalStorageDirectory() + path
 * Use this so writes go to the same Documents tree that works for export on Android 10+.
 */
fun resolveExternalStoragePath(path: String): File {
    return when {
        path.trim() == "/" || path.isBlank() -> File(Environment.getExternalStorageDirectory(), "Documents")
        path.startsWith("/") -> File(path)
        path.startsWith("Documents") || path.startsWith("Documents/") -> {
            val documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val rest = path.removePrefix("Documents").trimStart('/', File.separatorChar)
            if (rest.isEmpty()) documentsDir else File(documentsDir, rest)
        }
        else -> File(Environment.getExternalStorageDirectory(), path)
    }
}

/** Copies contents of [source] directory into [target]. Creates [target] if needed. Uses overwrite = true. */
fun copyDirectoryRecursively(source: File, target: File) {
    if (!source.isDirectory) return
    target.mkdirs()
    source.listFiles()?.forEach { file ->
        val dest = File(target, file.name)
        if (file.isDirectory) copyDirectoryRecursively(file, dest) else file.copyTo(dest, overwrite = true)
    }
}

fun saveImageFromContentUri(context: Context, fileUri: Uri, outputDir: File): File {
    val fileName = getFileNameFromUri(context, fileUri)
    val destFile = File(outputDir, fileName)


    // Decide max allowed pixel dimensions
    val minDimension = 2048
    val allowedW = max(SCREEN_WIDTH * 2, minDimension)
    val allowedH = max(SCREEN_HEIGHT * 2, minDimension)

    try {
        // Use ImageDecoder so we can set target size during decoding (avoids decoding huge bitmap)
        val source = ImageDecoder.createSource(context.contentResolver, fileUri)
        val resizedBitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val origW = info.size.width
            val origH = info.size.height

            // compute scale to fit into allowedW x allowedH while preserving aspect ratio
            val scale = min(1.0f, min(allowedW.toFloat() / origW, allowedH.toFloat() / origH))
            val targetW = max(1, (origW * scale).toInt())
            val targetH = max(1, (origH * scale).toInt())

            // request decoder to produce target size (software allocator to be safe)
            decoder.setTargetSize(targetW, targetH)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val mime = context.contentResolver.getType(fileUri) ?: ""

        // Decide output format: preserve PNG if possible, otherwise JPEG
        val outputFormat = when {
            mime.equals("image/png", ignoreCase = true) || destFile.extension.equals(
                "png",
                true
            ) -> Bitmap.CompressFormat.PNG

            mime.equals("image/webp", ignoreCase = true) || destFile.extension.equals(
                "webp", true
            ) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            }

            else -> Bitmap.CompressFormat.JPEG
        }

        // Save resized bitmap to destFile
        destFile.outputStream().use { out ->
            val quality = if (outputFormat == Bitmap.CompressFormat.PNG) 100 else 90
            resizedBitmap.compress(outputFormat, quality, out)
            out.flush()
        }

        // Recycle to free memory
        if (!resizedBitmap.isRecycled) resizedBitmap.recycle()

        return destFile
    } catch (e: Throwable) {
        // If anything goes wrong, fallback to copying the original
        try {
            return createFileFromContentUri(context, fileUri, outputDir)
        } catch (_: Throwable) {
            // as last resort, rethrow the original detailed error
            throw e
        }
    }
}

fun isImageUri(context: Context, uri: Uri): Boolean {
    val mimeType = context.contentResolver.getType(uri)
    return mimeType?.startsWith("image/") == true
}


// adapted from:
// https://stackoverflow.com/questions/71241337/copy-image-from-uri-in-another-folder-with-another-name-in-kotlin-android
fun createFileFromContentUri(context: Context, fileUri: Uri, outputDir: File): File {
    val fileName = getFileNameFromUri(context, fileUri)
    val outputFile = File(outputDir, fileName)


    val iStream: InputStream = context.contentResolver.openInputStream(fileUri)!!

    // Copy the input stream to the output file
    copyStreamToFile(iStream, outputFile)
    iStream.close()
    return outputFile
}

fun getFileNameFromUri(
    context: Context,
    fileUri: Uri
): String {
    var fileName: String? = null

    // Try to get display name from content resolver
    context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            fileName = cursor.getString(nameIndex)
        }
    }

    // Fallback if provider did not supply a name
    if (fileName.isNullOrBlank()) {
        fileUtilsLog.e("getFileNameFromUri: no display name found for uri=$fileUri")
        val ext = when (context.contentResolver.getType(fileUri)?.lowercase(Locale.US)) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heic" -> ".heic"
            "image/jpg" -> ".jpg"
            "image/jpeg" -> ".jpg"
            else -> ""
        }
        fileName = "file_${System.currentTimeMillis()}${ext}"
    }

    // Sanitize filename
    fileName = sanitizeFileName(fileName)

    return fileName
}


fun sanitizeFileName(raw: String, maxLen: Int = 80): String {
    // Normalize accents → é → e, Ł → L, etc.
    var name = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "") // remove diacritics

    // Replace illegal filename characters with " "
    name = name.replace(Regex("""[\\/:*?"<>|]"""), " ")

    // Collapse multiple underscores & spaces into one
    name = name.replace(Regex("[ ]+"), " ").trim()
    name = name.replace(Regex("[_]+"), "_").trim()

    // Prevent names like ".hidden" by stripping leading dots
    name = name.trim('.')

    // Enforce max length and fallback name
    if (name.length > maxLen) {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1)
        // No usable extension found, fall back to simple truncation
            name = name.take(maxLen).trimEnd()
        else {
            val ext = name.substring(dot)
            val baseName = name.take(dot)
            name = baseName.take(maxLen - ext.length).trimEnd().trimEnd('.') + ext
        }
    }
    if (name.isBlank()) {
        name = "notable-export"
    }
    return name
}

fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
    inputStream.use { input ->
        FileOutputStream(outputFile).use { output ->
            val buffer = ByteArray(4 * 1024) // buffer size
            while (true) {
                val byteCount = input.read(buffer)
                if (byteCount < 0) break
                output.write(buffer, 0, byteCount)
            }
            output.flush()
        }
    }
}

fun getPdfPageCount(uri: String): Int {
    if (uri.isEmpty()) {
        fileUtilsLog.w("getPdfPageCount: Empty URI")
        return 0
    }
    val file = File(uri)
    if (!file.exists()) {
        fileUtilsLog.w("getPdfPageCount: File does not exist: $uri")
        return 0
    }

    return try {
        val fileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        if (fileDescriptor != null) {
            PdfRenderer(fileDescriptor).use { renderer ->
                renderer.pageCount
            }
        } else {
            fileUtilsLog.e("File descriptor is null for URI: $uri")
            0
        }
    } catch (e: Exception) {
        fileUtilsLog.e("Failed to open PDF: ${e.message}, for file $uri")
        logCallStack("getPdfPageCount")
        0
    }
}

suspend fun waitForFileAvailable(
    filePath: String,
    timeoutMs: Long = 5000
): Boolean {
    val file = File(filePath)
    val start = System.currentTimeMillis()
    var intervalMs: Long = 5
    var count = 1
    while (System.currentTimeMillis() - start < timeoutMs) {
        if (file.exists() && file.length() > 0) {
            return true
        }
        delay(intervalMs)
        intervalMs += count * count // Quadratic growth
        count++
    }
    return false
}

// Requires android.permission.READ_EXTERNAL_STORAGE (pre-Android 13) and file actually readable
fun getFilePathFromUri(context: Context, uri: Uri): String? {
    try {
        return when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrElse {
                        fileUtilsLog.e("getFilePathFromUri: getDocumentId failed for uri=$uri: ${it.message}", it)
                        return null
                    }

                when {
                    // MediaStore provider
                    uri.authority?.contains("media") == true -> {
                        val split = docId.split(":")
                        if (split.size < 2) {
                            fileUtilsLog.w("getFilePathFromUri: Unexpected docId for media: '$docId', uri=$uri")
                            return null
                        }
                        val type = split[0]
                        val id = split[1]
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        val selection = "_id=?"
                        val selectionArgs = arrayOf(id)
                        getDataColumn(context, contentUri, selection, selectionArgs).also {
                            if (it == null) {
                                fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for media id=$id, uri=$uri, contentUri=$contentUri, docId='$docId'")
                            }
                        }
                    }

                    // Downloads provider
                    uri.authority?.contains("downloads") == true -> {
                        val contentUri = runCatching {
                            ContentUris.withAppendedId(
                                "content://downloads/public_downloads".toUri(),
                                docId.toLong()
                            )
                        }.getOrElse {
                            fileUtilsLog.w("getFilePathFromUri: Bad downloads docId '$docId' for uri=$uri: ${it.message}")
                            return null
                        }
                        getDataColumn(context, contentUri, null, null).also {
                            if (it == null) {
                                fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for downloads contentUri=$contentUri (orig uri=$uri, docId='$docId')")
                            }
                        }
                    }

                    // External storage provider (primary/non-primary volumes)
                    uri.authority == "com.android.externalstorage.documents" -> {
                        // docId examples:
                        // - "primary:Download/file.pdf"
                        // - "home:Documents/file.pdf"
                        // - "0000-0000:Android/data/..."
                        val split = docId.split(":")
                        val type = split.getOrNull(0).orEmpty()
                        val relative = split.getOrNull(1).orEmpty()

                        val basePath: String? = when {
                            type.equals("primary", ignoreCase = true) -> {
                                Environment.getExternalStorageDirectory().absolutePath
                            }
                            type.equals("home", ignoreCase = true) -> {
                                // "home" generally maps under primary; treat like primary root
                                Environment.getExternalStorageDirectory().absolutePath
                            }
                            type.isNotEmpty() -> {
                                // Non-primary (SD card/USB) volume id; best-effort mount path
                                // Commonly /storage/<UUID>/...
                                "/storage/$type"
                            }
                            else -> null
                        }

                        if (basePath == null) {
                            fileUtilsLog.w("getFilePathFromUri: externalstorage: unknown volume for docId='$docId', uri=$uri")
                            null
                        } else {
                            val candidate = if (relative.isNotEmpty()) {
                                File(basePath, relative).absolutePath
                            } else {
                                basePath
                            }
                            val f = File(candidate)
                            if (f.exists()) {
                                candidate
                            } else {
                                fileUtilsLog.w("getFilePathFromUri: externalstorage resolved path does not exist: $candidate (uri=$uri, docId='$docId')")
                                null
                            }
                        }
                    }

                    else -> {
                        fileUtilsLog.w("getFilePathFromUri: Unhandled document authority='${uri.authority}' for uri=$uri, docId='$docId'")
                        null
                    }
                }
            }

            "content".equals(uri.scheme, ignoreCase = true) -> {
                getDataColumn(context, uri, null, null).also {
                    if (it == null) {
                        fileUtilsLog.w("getFilePathFromUri: getDataColumn returned null for content uri=$uri (provider=${uri.authority})")
                    }
                }
            }

            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path.also {
                    if (it.isNullOrEmpty()) {
                        fileUtilsLog.w("getFilePathFromUri: file scheme but empty path for uri=$uri")
                    }
                }
            }

            else -> {
                fileUtilsLog.w("getFilePathFromUri: Unsupported scheme='${uri.scheme}' authority='${uri.authority}' for uri=$uri")
                null
            }
        }
    } catch (se: SecurityException) {
        fileUtilsLog.e("getFilePathFromUri: SecurityException for uri=$uri: ${se.message}", se)
        return null
    } catch (e: Exception) {
        fileUtilsLog.e("getFilePathFromUri: Unexpected error for uri=$uri: ${e.message}", e)
        return null
    }
}

const val IN_IGNORED = 32768
fun fileObserverEventNames(event: Int): String {
    val names = mutableListOf<String>()
    if (event and FileObserver.ACCESS != 0) names += "ACCESS"
    if (event and FileObserver.ATTRIB != 0) names += "ATTRIB"
    if (event and FileObserver.CLOSE_NOWRITE != 0) names += "CLOSE_NOWRITE"
    if (event and FileObserver.CLOSE_WRITE != 0) names += "CLOSE_WRITE"
    if (event and FileObserver.CREATE != 0) names += "CREATE"
    if (event and FileObserver.DELETE != 0) names += "DELETE"
    if (event and FileObserver.DELETE_SELF != 0) names += "DELETE_SELF"
    if (event and FileObserver.MODIFY != 0) names += "MODIFY"
    if (event and FileObserver.MOVED_FROM != 0) names += "MOVED_FROM"
    if (event and FileObserver.MOVED_TO != 0) names += "MOVED_TO"
    if (event and FileObserver.MOVE_SELF != 0) names += "MOVE_SELF"
    if (event and FileObserver.OPEN != 0) names += "OPEN"
    if (event and FileObserver.ALL_EVENTS == event) names += "ALL_EVENTS"
    if (event and IN_IGNORED == event) names += "IN_IGNORED"
    if (names.isEmpty()) names += "Unknown: $event"
    return names.joinToString("|")
}