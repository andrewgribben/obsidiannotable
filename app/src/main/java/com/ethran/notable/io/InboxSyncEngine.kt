package com.ethran.notable.io

import android.content.Context
import android.graphics.RectF
import com.ethran.notable.data.AppRepository
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.resolveVaultAttachmentDir
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.data.db.Annotation
import com.ethran.notable.data.db.AnnotationType
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.io.resolveExternalStoragePath
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = ShipBook.getLogger("InboxSyncEngine")

object InboxSyncEngine {

    /**
     * Sync an inbox page to Obsidian. Tags come from the UI (pill selection),
     * content is recognized from all strokes on the page via Onyx HWR (MyScript).
     * Annotation boxes mark regions to wrap in [[wiki links]] or #tags.
     */
    suspend fun syncInboxPage(
        appRepository: AppRepository,
        pageId: String,
        tags: List<String>,
        context: Context,
        exportEngine: ExportEngine
    ) {
        log.i("Starting inbox sync for page $pageId with tags: $tags")

        val pageWithStrokes = appRepository.pageRepository.getWithStrokeById(pageId)
        val page = pageWithStrokes.page
        val allStrokes = pageWithStrokes.strokes
        val annotations = appRepository.annotationRepository.getByPageId(pageId)

        if (allStrokes.isEmpty() && tags.isEmpty()) {
            log.i("No strokes and no tags on inbox page, skipping sync")
            return
        }

        val serviceReady = try {
            OnyxHWREngine.bindAndAwait(context)
        } catch (e: Exception) {
            log.e("OnyxHWR bind failed: ${e.message}")
            false
        }

        // 1. Segment strokes into paragraph groups by vertical gap, then recognize each group
        //    separately so that: within-paragraph line-wraps collapse to spaces, and intentional
        //    paragraph breaks (large vertical gaps) produce \n\n in the output.
        var fullText = if (serviceReady && allStrokes.isNotEmpty()) {
            val paragraphGroups = segmentIntoParagraphGroups(allStrokes)
            log.i("Recognizing ${allStrokes.size} strokes in ${paragraphGroups.size} paragraph group(s)")
            val parts = paragraphGroups.map { paraStrokes ->
                formatParagraph(recognizeStrokesSafe(paraStrokes).trim())
            }
            postProcessRecognition(parts.joinToString("\n\n"))
        } else ""

        log.i("Full recognized text: '${fullText.take(200)}'")

        // 2. Find annotation text by diffing full recognition vs non-annotation recognition.
        //    Falls back to per-annotation recognition if the diff produces a count mismatch
        //    (which happens when removing strokes changes HWR context enough to alter other words).
        if (serviceReady && annotations.isNotEmpty()) {
            val sortedAnnotations = annotations.sortedWith(compareBy({ it.y }, { it.x }))

            // Collect stroke IDs that fall inside any annotation box
            val annotationStrokeIds = mutableSetOf<String>()
            val annotationStrokeMap = mutableMapOf<String, List<Stroke>>()
            for (annotation in sortedAnnotations) {
                val annotRect = RectF(
                    annotation.x, annotation.y,
                    annotation.x + annotation.width,
                    annotation.y + annotation.height
                )
                val overlapping = findStrokesInRect(allStrokes, annotRect)
                annotationStrokeMap[annotation.id] = overlapping
                overlapping.forEach { annotationStrokeIds.add(it.id) }
            }

            // Try diff-based approach first (better accuracy when it works)
            var diffSucceeded = false
            val nonAnnotStrokes = allStrokes.filter { it.id !in annotationStrokeIds }
            if (nonAnnotStrokes.isNotEmpty()) {
                val baseText = postProcessRecognition(recognizeStrokesSafe(nonAnnotStrokes))
                log.i("Base text (without annotations): '${baseText.take(200)}'")

                val annotationTexts = diffWords(baseText, fullText)
                log.i("Diffed annotation texts: $annotationTexts")

                if (annotationTexts.size == sortedAnnotations.size) {
                    diffSucceeded = true
                    for ((i, annotation) in sortedAnnotations.withIndex()) {
                        val annotText = annotationTexts[i]
                        if (annotText.isBlank()) continue
                        log.i("Annotation ${annotation.type} (diff): '$annotText'")
                        // Strip trailing punctuation so it stays outside the markup
                        val cleaned = annotText.trimEnd('.', ',', ';', ':', '!', '?')
                        val trailing = annotText.removePrefix(cleaned)
                        val wrapped = wrapAnnotationText(annotation, cleaned) + trailing
                        fullText = fullText.replaceFirst(annotText, wrapped)
                    }
                } else {
                    log.w("Diff found ${annotationTexts.size} segments but have ${sortedAnnotations.size} annotations — falling back to per-annotation recognition")
                }
            }

            // Fallback: recognize each annotation's strokes individually
            if (!diffSucceeded) {
                for (annotation in sortedAnnotations) {
                    val overlapping = annotationStrokeMap[annotation.id] ?: continue
                    if (overlapping.isEmpty()) continue
                    val rawAnnotText = recognizeStrokesSafe(overlapping).trim()
                    if (rawAnnotText.isBlank()) continue
                    log.i("Annotation ${annotation.type} (fallback raw): '$rawAnnotText'")

                    // Per-annotation HWR can be noisy — find the best matching
                    // substring in fullText, trying the full text first, then
                    // individual words from longest to shortest
                    val matchText = findBestMatch(rawAnnotText, fullText)
                    if (matchText != null) {
                        log.i("Annotation ${annotation.type} (fallback matched): '$matchText'")
                        val cleaned = matchText.trimEnd('.', ',', ';', ':', '!', '?')
                        val trailing = matchText.removePrefix(cleaned)
                        val wrapped = wrapAnnotationText(annotation, cleaned) + trailing
                        fullText = fullText.replaceFirst(matchText, wrapped)
                    } else {
                        log.w("Annotation ${annotation.type}: could not find '$rawAnnotText' in full text")
                    }
                }
            }
        }

        val finalContent = fullText

        val createdDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(page.createdAt)
        val inboxPath = GlobalAppSettings.current.obsidianInboxPath
        val attachmentPath = GlobalAppSettings.current.obsidianAttachmentPath
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(page.createdAt)
        val inboxDir = resolveExternalStoragePath(inboxPath)
        inboxDir.mkdirs()

        // PDF goes to vault attachment folder (relative to inbox). Blank = same folder as .md.
        val attachmentDir = resolveVaultAttachmentDir(inboxPath, attachmentPath)
        val pdfRelativeLink = if (inboxPath.isNotBlank() && attachmentDir != null) {
            try {
                attachmentDir.mkdirs()
                log.i("Inbox sync: exporting page to PDF at ${attachmentDir.absolutePath}, file $timestamp.pdf")
                val result = exportEngine.export(
                    ExportTarget.Page(pageId),
                    ExportFormat.PDF,
                    ExportOptions(
                        copyToClipboard = false,
                        targetFolderUri = attachmentDir.toUri(),
                        fileName = timestamp,
                        overwrite = true
                    )
                )
                if (result.startsWith("Saved ") && !result.contains("(app storage)")) {
                    // Use filename only — Obsidian wikilinks resolve by unique filename,
                    // so no path prefix is needed.
                    val link = "$timestamp.pdf"
                    log.i("Inbox sync: PDF saved, link added: $link")
                    link
                } else if (result.contains("(app storage)")) {
                    log.w("Inbox sync: PDF saved to app storage (grant All files access for vault)")
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = "PDF saved to app storage. Grant All files access in Settings to save to vault.", duration = 6000))
                    null
                } else {
                    log.e("PDF export for inbox failed: $result")
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = "PDF export failed: $result", duration = 5000))
                    null
                }
            } catch (e: Exception) {
                log.e("PDF export for inbox failed: ${e.message}", e)
                SnackState.globalSnackFlow.tryEmit(SnackConf(text = "PDF export failed: ${e.message}", duration = 5000))
                null
            }
        } else {
            log.i("Inbox sync: no inbox or attachment dir, skipping PDF export")
            null
        }

        val markdown = generateMarkdown(createdDate, tags, finalContent, pdfRelativeLink)
        writeMarkdownFile(markdown, page.createdAt, inboxPath)

        log.i("Inbox sync complete for page $pageId")
    }

    /**
     * Find the best matching substring of [annotText] within [fullText].
     * Tries the full recognized text first, then individual words (longest first).
     * Returns the matching substring as it appears in fullText, or null.
     */
    private fun findBestMatch(annotText: String, fullText: String): String? {
        if (fullText.contains(annotText)) return annotText

        // Try individual words, longest first (longer words are more specific)
        val words = annotText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val sorted = words.sortedByDescending { it.length }
        for (word in sorted) {
            if (word.length >= 2 && fullText.contains(word)) return word
        }
        return null
    }

    private fun wrapAnnotationText(annotation: Annotation, text: String): String {
        return when (annotation.type) {
            AnnotationType.WIKILINK.name -> "[[${text}]]"
            AnnotationType.TAG.name -> "#${text.replace(" ", "-")}"
            else -> text
        }
    }

    private suspend fun recognizeStrokesSafe(strokes: List<Stroke>): String {
        return try {
            OnyxHWREngine.recognizeStrokes(strokes, viewWidth = 1404f, viewHeight = 1872f) ?: ""
        } catch (e: Exception) {
            log.e("OnyxHWR failed: ${e.message}")
            ""
        }
    }

    /**
     * Find contiguous word segments in [fullText] that are absent from [baseText].
     * Uses a simple word-level LCS diff. Returns segments in the order they appear
     * in fullText, with consecutive inserted words joined by spaces.
     *
     * Example: baseText="This is a document", fullText="This is a new document pkm"
     *   → ["new", "pkm"]
     */
    private fun diffWords(baseText: String, fullText: String): List<String> {
        val baseWords = baseText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val fullWords = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }

        // LCS to find which words in fullText are "matched" to baseText
        val m = baseWords.size
        val n = fullWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to find which fullText words are NOT in the LCS (= annotation words)
        val matched = BooleanArray(n)
        var i = m; var j = n
        while (i > 0 && j > 0) {
            if (baseWords[i - 1].equals(fullWords[j - 1], ignoreCase = true)) {
                matched[j - 1] = true
                i--; j--
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        // Group consecutive unmatched words into segments
        val segments = mutableListOf<String>()
        var current = mutableListOf<String>()
        for (k in fullWords.indices) {
            if (!matched[k]) {
                current.add(fullWords[k])
            } else if (current.isNotEmpty()) {
                segments.add(current.joinToString(" "))
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) segments.add(current.joinToString(" "))

        return segments
    }

    private fun findStrokesInRect(strokes: List<Stroke>, rect: RectF): List<Stroke> {
        return strokes.filter { stroke ->
            val strokeRect = RectF(stroke.left, stroke.top, stroke.right, stroke.bottom)
            RectF.intersects(strokeRect, rect)
        }
    }


    /**
     * Post-process recognition output:
     * - Normalize any bracket/paren wrapping to [[wiki links]]
     * - Collapse space between # and the following word into a proper #tag
     *
     * NOTE: paragraph/newline handling is done upstream in [formatParagraph] before this runs.
     */
    private fun postProcessRecognition(text: String): String {
        var result = text

        // Normalize bracket/paren wrapping to [[wiki links]]
        result = result.replace(Regex("""[(\[]{1,2}([^)\]\n]+?)[)\]]{1,2}""")) { match ->
            "[[${match.groupValues[1].trim()}]]"
        }

        // Collapse space between # and the word following it
        result = result.replace(Regex("""#\s+(\w+)""")) { match ->
            "#${match.groupValues[1]}"
        }

        return result
    }

    /**
     * Path of a file in [attachmentDir] (inside vault), relative to vault root.
     * Uses forward slashes for Obsidian wiki links.
     */
    private fun pathRelativeToVaultRoot(inboxPath: String, attachmentDir: File, fileName: String): String {
        val vaultRoot = resolveExternalStoragePath(inboxPath).parentFile?.absolutePath ?: return fileName
        val filePath = File(attachmentDir, fileName).absolutePath
        return if (filePath.startsWith(vaultRoot)) {
            filePath.removePrefix(vaultRoot).trimStart(File.separatorChar).replace(File.separatorChar, '/')
        } else {
            fileName
        }
    }

    private fun generateMarkdown(
        createdDate: String,
        tags: List<String>,
        content: String,
        pdfRelativeLink: String? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("created: \"[[$createdDate]]\"")
        if (tags.isNotEmpty()) {
            sb.appendLine("tags:")
            tags.forEach { sb.appendLine("  - $it") }
        }
        sb.appendLine("---")
        sb.appendLine()
        if (pdfRelativeLink != null) {
            sb.appendLine("PDF: [[$pdfRelativeLink]]")
            sb.appendLine()
        }
        sb.appendLine(content.trim())
        return sb.toString()
    }

    private fun writeMarkdownFile(markdown: String, createdAt: Date, inboxPath: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(createdAt)
        val fileName = "$timestamp.md"
        val dir = resolveExternalStoragePath(inboxPath)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(markdown)
        log.i("Written inbox note to ${file.absolutePath}")
    }

    /**
     * Cluster strokes into paragraph groups by detecting large vertical gaps.
     *
     * Strokes are sorted by their vertical midpoint. A gap between the bottom of the current
     * group and the top of the next stroke that exceeds 1.5× the median stroke height is treated
     * as an intentional paragraph break.
     */
    private fun segmentIntoParagraphGroups(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()

        val sorted = strokes.sortedBy { (it.top + it.bottom) / 2f }
        val heights = sorted.map { it.bottom - it.top }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(10f)
        val paragraphGapThreshold = medianHeight * 1.5f

        val groups = mutableListOf<MutableList<Stroke>>()
        var current = mutableListOf(sorted.first())
        var currentGroupBottom = sorted.first().bottom

        for (stroke in sorted.drop(1)) {
            val gap = stroke.top - currentGroupBottom
            if (gap > paragraphGapThreshold) {
                groups.add(current)
                current = mutableListOf(stroke)
                currentGroupBottom = stroke.bottom
            } else {
                current.add(stroke)
                if (stroke.bottom > currentGroupBottom) currentGroupBottom = stroke.bottom
            }
        }
        groups.add(current)
        return groups
    }

    /**
     * Format a paragraph's raw recognized text:
     * - Lines starting with a bullet marker (-, –, •) become markdown `- ` bullets.
     * - Lines starting with a number + `.` or `)` are kept as-is (numbered list).
     * - Consecutive non-list lines are joined with a space (collapse line-wrapping).
     */
    private fun formatParagraph(text: String): String {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return normalizeLine(lines[0])

        val output = mutableListOf<String>()
        val pendingWords = mutableListOf<String>()

        for (line in lines) {
            if (isBulletLine(line) || isNumberedLine(line)) {
                if (pendingWords.isNotEmpty()) {
                    output.add(pendingWords.joinToString(" "))
                    pendingWords.clear()
                }
                output.add(normalizeLine(line))
            } else {
                pendingWords.add(line)
            }
        }

        if (pendingWords.isNotEmpty()) {
            output.add(pendingWords.joinToString(" "))
        }

        return output.joinToString("\n")
    }

    /** True when [line] opens with a bullet marker followed by at least one non-space character. */
    private fun isBulletLine(line: String): Boolean =
        line.matches(Regex("""^[•\-–]\s+.+"""))

    /** True when [line] opens with a digit sequence followed by `.` or `)` and a space. */
    private fun isNumberedLine(line: String): Boolean =
        line.matches(Regex("""^\d+[.)]\s+.+"""))

    /**
     * Normalise a single line for markdown output:
     * - Em-dash and bullet-char markers (–, •) are converted to the standard `- ` prefix.
     */
    private fun normalizeLine(line: String): String =
        line.replace(Regex("""^[•–]\s+"""), "- ")
}
