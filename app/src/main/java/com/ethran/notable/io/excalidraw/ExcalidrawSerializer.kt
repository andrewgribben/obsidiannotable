package com.ethran.notable.io.excalidraw

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import blazing.chain.LZSEncoding
import io.shipbook.shipbooksdk.ShipBook
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val log = ShipBook.getLogger("ExcalidrawSerializer")

/**
 * Serializes app strokes to Obsidian-Excalidraw-plugin-compatible `.excalidraw.md`
 * files, and parses them back.
 *
 * Strokes become Excalidraw `freedraw` elements (with `pressures`) so flip sides are
 * viewable and editable in Obsidian on any platform. Full-fidelity native stroke data
 * (pen, size, color, tilt, timing) rides along in each element's `customData` so
 * round-trips on the Boox stay pixel-perfect. Elements created in Excalidraw itself
 * (no customData) are converted to ballpoint strokes on import.
 */
object ExcalidrawSerializer {

    private const val CUSTOM_DATA_KEY = "singularity"
    private const val IMPORTED_ID_SEPARATOR = "|excalidraw|"
    const val DRAWING_PROPERTY = "singularity-drawing"

    private const val EXCALIDRAW_PLUGIN_LINE = "excalidraw-plugin: parsed"
    private const val DRAWING_WARNING_LINE =
        "==⚠  Switch to EXCALIDRAW VIEW in the MORE OPTIONS menu of this document. ⚠== " +
            "You can decompress Drawing data with the command palette: " +
            "'Decompress current Excalidraw file'. For more info check in plugin settings under 'Saving'"

    private val FLIP_SIDE_FRONTMATTER_REGEX =
        Regex("""^flip-side:\s*.+$""", RegexOption.MULTILINE)
    private val PDF_FRONTMATTER_REGEX =
        Regex("""^pdf:\s*.+$""", RegexOption.MULTILINE)
    private val EXCALIDRAW_TAG_REGEX =
        Regex("""^\s*-\s*excalidraw\s*$""", RegexOption.MULTILINE)
    private val EXCALIDRAW_OPEN_MD_REGEX =
        Regex("""^excalidraw-open-md:\s*.+$""", RegexOption.MULTILINE)
    private val EXCALIDRAW_PLUGIN_FM_REGEX =
        Regex("""^excalidraw-plugin:\s*.+$""", RegexOption.MULTILINE)
    private val TAGS_EXCALIDRAW_INLINE_REGEX =
        Regex("""^tags:\s*\[excalidraw\]\s*$""", RegexOption.MULTILINE)
    private val DRAWING_PROPERTY_REGEX =
        Regex("""^singularity-drawing:\s*["']?\[\[([^\]]+)]]["']?\s*$""", RegexOption.MULTILINE)

    // Excalidraw freedraw thickness ≈ strokeWidth in scene px; our stroke size is the
    // brush diameter in page px. Scale down so drawings look similar in Obsidian.
    private const val EXCALIDRAW_WIDTH_SCALE = 0.5f

    /** True when [content] has Obsidian Excalidraw frontmatter or tag. */
    fun isExcalidrawNote(content: String): Boolean {
        if (!content.startsWith("---")) return false
        val end = content.indexOf("\n---", 3)
        if (end < 0) return false
        val fm = content.substring(0, end)
        return fm.contains("excalidraw-plugin:") ||
            fm.contains("excalidraw") ||
            EXCALIDRAW_TAG_REGEX.containsMatchIn(fm)
    }

    /** True when [content] contains a drawing block fence (no decompression). */
    fun hasDrawingSection(content: String): Boolean =
        drawingSectionStartIndex(content) >= 0 || content.trimStart().startsWith("{")

    /** True when [content] contains a parseable drawing (decompresses compressed-json). */
    fun hasEmbeddedDrawing(content: String): Boolean =
        extractDrawingJson(content) != null

    /**
     * Fast ink check for vault listing: avoids stroke conversion and full-file reads when
     * [file] is large (drawing lives in the tail for unified / Obsidian notes).
     */
    fun hasNonemptyInkForListing(file: java.io.File): Boolean {
        val length = file.length()
        if (length <= LISTING_TAIL_SCAN_BYTES) {
            return hasNonemptyInk(runCatching { file.readText() }.getOrNull().orEmpty())
        }
        scanFileTailForInk(file)?.let { return it }
        return false
    }

    /** True when [file] has a drawing block without reading the whole file when it is large. */
    fun hasDrawingSectionForListing(file: java.io.File): Boolean {
        if (file.length() <= LISTING_TAIL_SCAN_BYTES) {
            val content = runCatching { file.readText() }.getOrNull() ?: return false
            return hasDrawingSection(content)
        }
        val tail = readFileTail(file, LISTING_TAIL_SCAN_BYTES) ?: return false
        return drawingSectionStartIndex(tail) >= 0 || tail.contains("%%")
    }

    /**
     * True when the note contains at least one non-deleted freedraw element.
     * Cheaper than [parse] — used for vault / home listing only.
     */
    fun hasNonemptyInk(content: String): Boolean {
        if (!hasDrawingSection(content)) return false
        return hasNonemptyInkInChunk(content)
    }

    internal fun hasNonemptyInkInChunk(chunk: String): Boolean {
        extractFencedBlock(chunk, "json", searchFrom = 0)?.let { block ->
            if (EMPTY_ELEMENTS_JSON_REGEX.containsMatchIn(block)) return false
            return block.contains("\"freedraw\"")
        }
        extractFencedBlock(chunk, "compressed-json", searchFrom = 0)?.let { compressed ->
            if (compressed.length < 32) return false
            val cleaned = buildString(compressed.length) {
                for (ch in compressed) {
                    if (ch != '\n' && ch != '\r') append(ch)
                }
            }
            val decoded = runCatching { LZSEncoding.decompressFromBase64(cleaned) }.getOrNull()
                ?: return compressed.isNotBlank()
            if (!decoded.contains("freedraw")) return false
            return !EMPTY_ELEMENTS_JSON_REGEX.containsMatchIn(decoded)
        }
        return false
    }

    private const val LISTING_TAIL_SCAN_BYTES = 512 * 1024
    private const val LARGE_FILE_WINDOW_BYTES = 64 * 1024
    private const val LARGE_FILE_MAX_SCAN_BYTES = 4 * 1024 * 1024
    private val EMPTY_ELEMENTS_JSON_REGEX =
        Regex("""\"elements\"\s*:\s*\[\s*\]""")

    /** Markdown body between YAML frontmatter and the drawing section (trimmed). */
    fun extractMarkdownBody(content: String): String {
        val bodyStart = frontmatterEndIndex(content)
        val hasFencedDrawing = content.contains("```compressed-json") ||
            content.contains("```json")
        val drawStart = if (hasFencedDrawing) drawingTailStartIndex(content, bodyStart) else -1
        val body = if (drawStart >= 0) {
            content.substring(bodyStart, drawStart)
        } else {
            content.substring(bodyStart)
        }
        return body.trim()
    }

    /** Vault-relative path from the note's `singularity-drawing` wikilink. */
    fun drawingLinkPath(content: String): String? =
        DRAWING_PROPERTY_REGEX.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Adds or replaces the drawing association while preserving all other note content. */
    fun withDrawingLink(content: String, drawingRelativePath: String): String {
        val cleanPath = drawingRelativePath.replace('\\', '/').trimStart('/')
        val property = "$DRAWING_PROPERTY: \"[[$cleanPath]]\""
        val withoutOld = DRAWING_PROPERTY_REGEX.replace(content, "")
        if (withoutOld.startsWith("---")) {
            val end = withoutOld.indexOf("\n---", 3)
            if (end >= 0) {
                return withoutOld.substring(0, end).trimEnd() +
                    "\n$property" +
                    withoutOld.substring(end)
            }
        }
        return "---\n$property\n---\n\n${withoutOld.trimStart()}"
    }

    /** Removes only Singularity's drawing association from a text note. */
    fun withoutDrawingLink(content: String): String =
        DRAWING_PROPERTY_REGEX.replace(content, "")

    /** Replaces only a regular Markdown note's body, preserving YAML frontmatter. */
    fun rewriteMarkdownBody(content: String, markdownBody: String): String {
        val fmEnd = frontmatterEndIndex(content)
        val prefix = if (fmEnd > 0) content.substring(0, fmEnd).trimEnd() else ""
        val body = markdownBody.trim()
        return buildString {
            if (prefix.isNotEmpty()) append(prefix)
            if (prefix.isNotEmpty() && body.isNotEmpty()) append("\n\n")
            if (body.isNotEmpty()) append(body)
            append('\n')
        }
    }

    /**
     * Merges Obsidian Excalidraw frontmatter into [content] and strips obsolete
     * `flip-side:` / `pdf:` properties.
     */
    fun ensureExcalidrawFrontmatter(content: String): String {
        val stripped = FLIP_SIDE_FRONTMATTER_REGEX.replace(content, "")
            .let { PDF_FRONTMATTER_REGEX.replace(it, "") }
            .replace(Regex("\n{3,}"), "\n\n")

        if (!stripped.startsWith("---")) {
            return buildString {
                appendLine("---")
                appendLine(EXCALIDRAW_PLUGIN_LINE)
                appendLine("tags:")
                appendLine("  - excalidraw")
                appendLine("---")
                if (stripped.isNotBlank()) {
                    appendLine()
                    append(stripped.trim())
                    appendLine()
                }
            }
        }

        val end = stripped.indexOf("\n---", 3)
        if (end < 0) return stripped

        var fm = stripped.substring(0, end)
        if (!fm.contains("excalidraw-plugin:")) {
            fm += "\n$EXCALIDRAW_PLUGIN_LINE"
        }
        fm = if (fm.contains("excalidraw-open-md:")) {
            EXCALIDRAW_OPEN_MD_REGEX.replace(fm, "excalidraw-open-md: false")
        } else {
            "$fm\nexcalidraw-open-md: false"
        }
        if (!fm.contains("excalidraw")) {
            if (fm.contains("tags:")) {
                fm += "\n  - excalidraw"
            } else {
                fm += "\ntags:\n  - excalidraw"
            }
        }
        val tail = stripped.substring(end)
        return fm + tail
    }

    /** Full unified Excalidraw markdown: frontmatter + [markdownBody] + Obsidian `%%` drawing block. */
    fun serializeUnified(markdownBody: String, strokes: List<Stroke>): String {
        val body = markdownBody.trim()
        return buildString {
            appendLine("---")
            appendLine(EXCALIDRAW_PLUGIN_LINE)
            appendLine("excalidraw-open-md: false")
            appendLine("tags: [excalidraw]")
            appendLine("---")
            if (body.isNotEmpty()) {
                appendLine()
                appendLine(body)
            }
            appendLine()
            append(buildDrawingSection(strokes))
        }
    }

    /** Preserves frontmatter and text; replaces or appends the drawing block. */
    fun replaceDrawingInUnified(content: String, strokes: List<Stroke>): String =
        rewriteUnified(content, extractMarkdownBody(content), strokes)

    /** Rewrites the unified file with [markdownBody] and [strokes], merging Excalidraw frontmatter. */
    fun rewriteUnified(content: String, markdownBody: String, strokes: List<Stroke>): String {
        val withFm = ensureExcalidrawFrontmatter(content)
        return buildString {
            append(withFm.substring(0, frontmatterEndIndex(withFm)).trimEnd())
            val body = markdownBody.trim()
            if (body.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine(body)
            }
            appendLine()
            append(buildDrawingSection(strokes))
        }
    }

    /** Removes excalidraw drawing data and excalidraw frontmatter; keeps markdown text. */
    fun stripDrawingFromUnified(content: String): String {
        val fmEnd = frontmatterEndIndex(content)
        val drawStart = drawingTailStartIndex(content, fmEnd)
        val withoutDrawing = if (drawStart >= 0) content.substring(0, drawStart) else content
        val textBody = if (withoutDrawing.length > fmEnd) {
            withoutDrawing.substring(fmEnd).trim()
        } else {
            ""
        }
        val head = stripExcalidrawFrontmatterBlock(content)
        return buildString {
            if (head.isNotEmpty()) {
                append(head.trimEnd())
                appendLine()
            }
            if (textBody.isNotEmpty()) {
                if (head.isNotEmpty()) appendLine()
                appendLine(textBody)
            }
            appendLine()
        }
    }

    /** YAML frontmatter with excalidraw / flip-side keys removed; empty when nothing remains. */
    private fun stripExcalidrawFrontmatterBlock(content: String): String {
        if (!content.startsWith("---")) return ""
        val end = content.indexOf("\n---", 3)
        if (end < 0) return ""

        val lines = content.substring(3, end).lines()
        val kept = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                EXCALIDRAW_PLUGIN_FM_REGEX.matches(trimmed) -> i++
                EXCALIDRAW_OPEN_MD_REGEX.matches(trimmed) -> i++
                FLIP_SIDE_FRONTMATTER_REGEX.matches(trimmed) -> i++
                PDF_FRONTMATTER_REGEX.matches(trimmed) -> i++
                TAGS_EXCALIDRAW_INLINE_REGEX.matches(trimmed) -> i++
                trimmed == "tags:" -> {
                    i++
                    val tagLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith("-")) {
                        tagLines.add(lines[i])
                        i++
                    }
                    val remaining = tagLines.filterNot { EXCALIDRAW_TAG_REGEX.matches(it) }
                    if (remaining.isNotEmpty()) {
                        kept.add(line)
                        kept.addAll(remaining)
                    }
                }
                EXCALIDRAW_TAG_REGEX.matches(line) -> i++
                else -> {
                    kept.add(line)
                    i++
                }
            }
        }

        val fm = kept.joinToString("\n").trim()
        if (fm.isBlank()) return ""
        return "---\n$fm\n---"
    }

    /** End index (exclusive) of the YAML frontmatter block, or 0 when absent. */
    fun frontmatterEndIndex(content: String): Int {
        if (!content.startsWith("---")) return 0
        val end = content.indexOf("\n---", 3)
        if (end < 0) return 0
        val afterMarker = end + "\n---".length
        return if (afterMarker < content.length && content[afterMarker] == '\n') afterMarker + 1
        else afterMarker
    }

    /** Renders a complete .excalidraw.md file for [strokes] (legacy sidecar format). */
    fun serialize(strokes: List<Stroke>): String {
        val drawing = buildDrawingJson(strokes)

        return buildString {
            appendLine("---")
            appendLine()
            appendLine("excalidraw-plugin: parsed")
            appendLine("tags: [excalidraw]")
            appendLine()
            appendLine("---")
            appendLine("==⚠  Switch to EXCALIDRAW VIEW in the MORE OPTIONS menu of this document. ⚠==")
            appendLine()
            appendLine()
            appendLine("# Drawing")
            appendLine("```json")
            appendLine(drawing.toString(1))
            appendLine("```")
            appendLine("%%")
        }
    }

    /** Complete raw `.excalidraw` JSON with full-fidelity Singularity stroke metadata. */
    fun serializeRaw(strokes: List<Stroke>, existingContent: String? = null): String {
        val existingRoot = existingContent
            ?.let(::extractDrawingJson)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val baseRoot = existingRoot
            ?: runCatching { ExcalidrawUnifiedTemplate.defaultDrawingRoot() }.getOrNull()
        val root = buildDrawingJsonForExport(strokes, baseRoot)
        if (existingRoot != null) {
            val preserved = existingRoot.optJSONArray("elements")
            val generated = root.optJSONArray("elements") ?: JSONArray()
            val existingFreedraw = mutableMapOf<String, JSONObject>()
            val existingFreedrawInOrder = mutableListOf<JSONObject>()
            if (preserved != null) {
                for (i in 0 until preserved.length()) {
                    val element = preserved.optJSONObject(i) ?: continue
                    if (element.optString("type") == "freedraw") {
                        existingFreedraw[element.optString("id")] = element
                        existingFreedrawInOrder += element
                    }
                }
            }
            val merged = JSONArray()
            for (i in 0 until generated.length()) {
                val element = generated.optJSONObject(i) ?: continue
                val existing = existingFreedraw[element.optString("id")]
                    ?: existingFreedrawInOrder
                        .takeIf { it.size == generated.length() }
                        ?.getOrNull(i)
                if (existing != null) {
                    val keys = existing.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in REGENERATED_FREEDRAW_KEYS) {
                            element.put(key, existing.get(key))
                        }
                    }
                }
                merged.put(element)
            }
            if (preserved != null) {
                for (i in 0 until preserved.length()) {
                    val element = preserved.optJSONObject(i) ?: continue
                    if (element.optString("type") != "freedraw") merged.put(element)
                }
            }
            root.put("elements", merged)
        }
        return root.toString(2) + "\n"
    }

    private val REGENERATED_FREEDRAW_KEYS = setOf(
        "type", "x", "y", "width", "height", "angle", "points", "pressures",
        "customData", "lastCommittedPoint", "simulatePressure", "isDeleted"
    )

    /**
     * Parses an .excalidraw.md (or raw .excalidraw JSON) file back into strokes for
     * [pageId]. Returns null when no drawing JSON is found.
     */
    fun parse(content: String, pageId: String): List<Stroke>? {
        val json = extractDrawingJson(content) ?: return null
        return try {
            val root = JSONObject(json)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val strokes = mutableListOf<Stroke>()
            for (i in 0 until elements.length()) {
                val element = elements.optJSONObject(i) ?: continue
                if (element.optBoolean("isDeleted", false)) continue
                elementToStroke(element, pageId)?.let { strokes.add(it) }
            }
            strokes
        } catch (e: Exception) {
            log.e("Failed to parse excalidraw JSON: ${e.message}")
            null
        }
    }

    /** Extracts the drawing JSON from an .excalidraw.md file (or returns raw JSON as-is). */
    fun extractDrawingJson(content: String): String? {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) return trimmed

        // Obsidian saves the authoritative drawing as compressed-json (often in the %% block).
        // Prefer it over the plain ```json preview, which may be stale after external edits.
        decompressCompressedJsonBlock(content)?.let { return it }

        extractFencedBlock(content, "json")?.let { return it }

        return null
    }

    private fun decompressCompressedJsonBlock(content: String): String? {
        val compressed = extractFencedBlock(content, "compressed-json") ?: return null
        val cleaned = buildString(compressed.length) {
            for (ch in compressed) {
                if (ch != '\n' && ch != '\r') append(ch)
            }
        }
        return runCatching { LZSEncoding.decompressFromBase64(cleaned) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractFencedBlock(content: String, language: String, searchFrom: Int = -1): String? {
        val fence = "```$language"
        val drawingMarkers = listOf("## Drawing", "# Drawing", "# Excalidraw Data")
        val from = if (searchFrom >= 0) {
            searchFrom
        } else {
            drawingMarkers
                .map { content.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: 0
        }

        var fenceStart = content.indexOf(fence, from)
        if (fenceStart < 0) {
            fenceStart = content.indexOf(fence)
            if (fenceStart < 0) return null
        }
        return readFencedContent(content, fenceStart + fence.length)
    }

    private fun readFileTail(file: java.io.File, maxBytes: Int): String? {
        return try {
            val length = file.length()
            if (length <= 0) return null
            val readLen = minOf(length, maxBytes.toLong()).toInt()
            val start = length - readLen
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(readLen)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            log.e("Failed to read tail of ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * Scans backwards through large unified notes for freedraw elements without loading
     * the whole file (migrated Obsidian json blocks can be many MB).
     */
    private fun scanFileTailForInk(file: java.io.File): Boolean? {
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen <= 0) return false
                val window = ByteArray(LARGE_FILE_WINDOW_BYTES)
                var scanned = 0L
                var pos = fileLen
                while (pos > 0 && scanned < LARGE_FILE_MAX_SCAN_BYTES) {
                    val readLen = minOf(pos, window.size.toLong()).toInt()
                    pos -= readLen
                    raf.seek(pos)
                    raf.readFully(window, 0, readLen)
                    val chunk = String(window, 0, readLen, Charsets.UTF_8)
                    scanned += readLen
                    if (chunk.contains("\"freedraw\"")) return true
                    if (EMPTY_ELEMENTS_JSON_REGEX.containsMatchIn(chunk)) return false
                }
                false
            }
        } catch (e: Exception) {
            log.e("Failed to scan ${file.absolutePath} for ink: ${e.message}")
            null
        }
    }

    private fun readFencedContent(content: String, bodyStart: Int): String? {
        val end = content.indexOf("```", bodyStart)
        if (end < 0) return null
        return content.substring(bodyStart, end).trim().ifBlank { null }
    }

    private fun drawingSectionStartIndex(content: String, searchFrom: Int = 0): Int {
        val markers = listOf(
            "%%",
            "==⚠",
            "# Excalidraw Data",
            "## Drawing",
            "# Drawing",
            "```compressed-json",
            "```json"
        )
        return markers
            .map { content.indexOf(it, searchFrom) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
    }

    private fun drawingTailStartIndex(content: String, searchFrom: Int = 0): Int {
        val heading = DRAWING_HEADING_REGEX.findAll(content)
            .filter { match ->
                match.range.first >= searchFrom &&
                    listOf("```compressed-json", "```json")
                        .any { content.indexOf(it, match.range.last + 1) >= 0 }
            }
            .maxByOrNull { it.range.first }
            ?.range?.first ?: return -1
        val commentStart = content.lastIndexOf("%%", heading)
        return if (commentStart >= searchFrom) commentStart else heading
    }

    private val DRAWING_HEADING_REGEX =
        Regex("""^(?:# Excalidraw Data|#{1,2} Drawing)\s*$""", RegexOption.MULTILINE)

    private fun buildDrawingSection(strokes: List<Stroke>): String {
        val templateRoot = runCatching { ExcalidrawUnifiedTemplate.defaultDrawingRoot() }.getOrNull()
        val drawing = buildDrawingJsonForExport(strokes, templateRoot)
        return ExcalidrawUnifiedTemplate.wrapDrawingJson(drawing.toString())
    }

    /** Builds excalidraw JSON for vault export, optionally merging template [appState]. */
    fun buildDrawingJsonForExport(
        strokes: List<Stroke>,
        templateRoot: JSONObject? = null
    ): JSONObject {
        val elements = JSONArray()
        for (stroke in strokes) {
            elements.put(strokeToElement(stroke))
        }
        val root = templateRoot?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!root.has("type")) root.put("type", "excalidraw")
        if (!root.has("version")) root.put("version", 2)
        if (!root.has("source")) {
            root.put("source", "https://github.com/singularity-notes")
        }
        root.put("elements", elements)
        if (!root.has("files")) root.put("files", JSONObject())
        if (!root.has("appState")) {
            root.put(
                "appState", JSONObject()
                    .put("gridSize", JSONObject.NULL)
                    .put("viewBackgroundColor", "#ffffff")
            )
        }
        return root
    }

    private fun buildDrawingJson(strokes: List<Stroke>): JSONObject =
        buildDrawingJsonForExport(strokes, null)

    // --- Stroke → freedraw element ---

    private fun strokeToElement(stroke: Stroke): JSONObject {
        val points = JSONArray()
        val pressures = JSONArray()
        val originX = stroke.points.firstOrNull()?.x ?: stroke.left
        val originY = stroke.points.firstOrNull()?.y ?: stroke.top
        for (point in stroke.points) {
            points.put(JSONArray().put(point.x - originX).put(point.y - originY))
            val normalized = (point.pressure ?: (stroke.maxPressure / 2f)) / stroke.maxPressure
            pressures.put(normalized.coerceIn(0f, 1f))
        }
        val lastPoint = stroke.points.lastOrNull()

        return JSONObject()
            .put("type", "freedraw")
            .put(
                "id",
                stroke.id.substringAfter(IMPORTED_ID_SEPARATOR, "")
                    .takeIf { it.isNotEmpty() }
                    ?: stroke.id.take(20).replace("-", "")
            )
            .put("x", originX)
            .put("y", originY)
            .put("width", abs(stroke.right - stroke.left))
            .put("height", abs(stroke.bottom - stroke.top))
            .put("angle", 0)
            .put("strokeColor", colorToHex(stroke.color))
            .put("backgroundColor", "transparent")
            .put("fillStyle", "solid")
            .put("strokeWidth", stroke.size * EXCALIDRAW_WIDTH_SCALE)
            .put("strokeStyle", "solid")
            .put("roughness", 0)
            .put("opacity", if (stroke.pen == Pen.MARKER) 40 else 100)
            .put("groupIds", JSONArray())
            .put("frameId", JSONObject.NULL)
            .put("roundness", JSONObject.NULL)
            .put("seed", stroke.id.hashCode())
            .put("version", 1)
            .put("versionNonce", stroke.id.hashCode())
            .put("isDeleted", false)
            .put("boundElements", JSONObject.NULL)
            .put("updated", stroke.updatedAt.time)
            .put("link", JSONObject.NULL)
            .put("locked", false)
            .put("points", points)
            .put("pressures", pressures)
            .put("simulatePressure", false)
            .put(
                "lastCommittedPoint",
                if (lastPoint != null)
                    JSONArray().put(lastPoint.x - originX).put(lastPoint.y - originY)
                else JSONObject.NULL
            )
            .put("customData", JSONObject().put(CUSTOM_DATA_KEY, strokeCustomData(stroke)))
    }

    private fun strokeCustomData(stroke: Stroke): JSONObject {
        val nativePoints = JSONArray()
        for (point in stroke.points) {
            val p = JSONArray()
                .put(point.x)
                .put(point.y)
                .put(point.pressure ?: JSONObject.NULL)
                .put(point.tiltX ?: JSONObject.NULL)
                .put(point.tiltY ?: JSONObject.NULL)
                .put(point.dt?.toInt() ?: JSONObject.NULL)
            nativePoints.put(p)
        }
        return JSONObject()
            .put("pen", stroke.pen.penName)
            .put("size", stroke.size)
            .put("color", stroke.color)
            .put("maxPressure", stroke.maxPressure)
            .put("createdAt", stroke.createdAt.time)
            .put("points", nativePoints)
    }

    // --- freedraw element → Stroke ---

    private fun elementToStroke(element: JSONObject, pageId: String): Stroke? {
        if (element.optString("type") != "freedraw") return null

        val native = element.optJSONObject("customData")?.optJSONObject(CUSTOM_DATA_KEY)
        return if (native != null) nativeToStroke(native, element, pageId)
        else plainFreedrawToStroke(element, pageId)
    }

    /** Full-fidelity restore from customData. */
    private fun nativeToStroke(native: JSONObject, element: JSONObject, pageId: String): Stroke? {
        return try {
            val nativePoints = native.getJSONArray("points")
            val elementPoints = element.optJSONArray("points")
            val pressures = element.optJSONArray("pressures")
            val originX = element.optDouble("x", 0.0).toFloat()
            val originY = element.optDouble("y", 0.0).toFloat()
            val angle = element.optDouble("angle", 0.0)
            val centerX = originX + element.optDouble("width", 0.0).toFloat() / 2f
            val centerY = originY + element.optDouble("height", 0.0).toFloat() / 2f
            val points = mutableListOf<StrokePoint>()
            val sourceCount = elementPoints?.length()?.takeIf { it > 0 } ?: nativePoints.length()
            for (i in 0 until sourceCount) {
                val nativeIndex = if (sourceCount <= 1 || nativePoints.length() <= 1) {
                    0
                } else {
                    ((i.toDouble() / (sourceCount - 1)) * (nativePoints.length() - 1))
                        .toInt()
                }
                val nativePoint = nativePoints.getJSONArray(nativeIndex)
                val elementPoint = elementPoints?.optJSONArray(i)
                val useElementGeometry = elementPoint != null
                val unrotatedX = if (useElementGeometry) {
                    originX + elementPoint!!.getDouble(0).toFloat()
                } else {
                    nativePoint.getDouble(0).toFloat()
                }
                val unrotatedY = if (useElementGeometry) {
                    originY + elementPoint!!.getDouble(1).toFloat()
                } else {
                    nativePoint.getDouble(1).toFloat()
                }
                val dx = unrotatedX - centerX
                val dy = unrotatedY - centerY
                val rotatedX = centerX + dx * cos(angle).toFloat() - dy * sin(angle).toFloat()
                val rotatedY = centerY + dx * sin(angle).toFloat() + dy * cos(angle).toFloat()
                val pressure = if (sourceCount == nativePoints.length()) {
                    if (nativePoint.isNull(2)) null else nativePoint.getDouble(2).toFloat()
                } else {
                    pressures?.optDouble(i, 0.5)?.times(
                        native.optInt("maxPressure", 4096)
                    )?.toFloat()
                }
                points.add(
                    StrokePoint(
                        x = rotatedX,
                        y = rotatedY,
                        pressure = pressure,
                        tiltX = if (nativePoint.isNull(3)) null else nativePoint.getInt(3),
                        tiltY = if (nativePoint.isNull(4)) null else nativePoint.getInt(4),
                        dt = if (nativePoint.isNull(5)) null else nativePoint.getInt(5).toUShort()
                    )
                )
            }
            if (points.isEmpty()) return null
            val pen = Pen.fromString(native.optString("pen", Pen.BALLPEN.penName))
            Stroke(
                id = importedStrokeId(pageId, element),
                size = native.optDouble("size", 5.0).toFloat(),
                pen = pen,
                color = native.optInt("color", 0xFF000000.toInt()),
                maxPressure = native.optInt("maxPressure", 4096),
                top = points.minOf { it.y },
                bottom = points.maxOf { it.y },
                left = points.minOf { it.x },
                right = points.maxOf { it.x },
                points = points,
                pageId = pageId,
                createdAt = Date(native.optLong("createdAt", System.currentTimeMillis()))
            )
        } catch (e: Exception) {
            log.w("Failed to restore native stroke, falling back to freedraw: ${e.message}")
            plainFreedrawToStroke(element, pageId)
        }
    }

    /** Conversion for elements drawn in Excalidraw itself. */
    private fun plainFreedrawToStroke(element: JSONObject, pageId: String): Stroke? {
        return try {
            val originX = element.optDouble("x", 0.0).toFloat()
            val originY = element.optDouble("y", 0.0).toFloat()
            val pointsJson = element.optJSONArray("points") ?: return null
            val pressures = element.optJSONArray("pressures")
            val angle = element.optDouble("angle", 0.0)
            val centerX = originX + element.optDouble("width", 0.0).toFloat() / 2f
            val centerY = originY + element.optDouble("height", 0.0).toFloat() / 2f
            val points = mutableListOf<StrokePoint>()
            for (i in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONArray(i)
                val pressure = pressures?.optDouble(i, 0.5) ?: 0.5
                val x = originX + p.getDouble(0).toFloat()
                val y = originY + p.getDouble(1).toFloat()
                val dx = x - centerX
                val dy = y - centerY
                points.add(
                    StrokePoint(
                        x = centerX + dx * cos(angle).toFloat() - dy * sin(angle).toFloat(),
                        y = centerY + dx * sin(angle).toFloat() + dy * cos(angle).toFloat(),
                        pressure = (pressure * 4096).toFloat().coerceIn(1f, 4096f)
                    )
                )
            }
            if (points.isEmpty()) return null
            val width = element.optDouble("strokeWidth", 2.0).toFloat() / EXCALIDRAW_WIDTH_SCALE
            Stroke(
                id = importedStrokeId(pageId, element),
                size = width.coerceIn(1f, 60f),
                pen = Pen.BALLPEN,
                color = hexToColor(element.optString("strokeColor", "#000000")),
                maxPressure = 4096,
                top = points.minOf { it.y },
                bottom = points.maxOf { it.y },
                left = points.minOf { it.x },
                right = points.maxOf { it.x },
                points = points,
                pageId = pageId
            )
        } catch (e: Exception) {
            log.w("Failed to convert freedraw element: ${e.message}")
            null
        }
    }

    fun colorToHex(color: Int): String =
        "#%02x%02x%02x".format(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF
        )

    private fun importedStrokeId(pageId: String, element: JSONObject): String {
        val elementId = element.optString("id").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        return "$pageId$IMPORTED_ID_SEPARATOR$elementId"
    }

    fun hexToColor(hex: String): Int {
        val cleaned = hex.removePrefix("#")
        return try {
            when (cleaned.length) {
                6 -> (0xFF shl 24) or cleaned.toInt(16)
                8 -> cleaned.toLong(16).toInt()
                3 -> {
                    val r = cleaned[0].digitToInt(16) * 17
                    val g = cleaned[1].digitToInt(16) * 17
                    val b = cleaned[2].digitToInt(16) * 17
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> 0xFF000000.toInt()
            }
        } catch (_: Exception) {
            0xFF000000.toInt()
        }
    }
}
