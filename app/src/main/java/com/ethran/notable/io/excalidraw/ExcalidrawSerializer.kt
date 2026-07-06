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
    private val PAIRED_COMMENT_REGEX = Regex("""%%[\s\S]*?%%""")

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
        val drawStart = drawingSectionStartIndex(content, bodyStart)
        val body = if (drawStart >= 0) {
            content.substring(bodyStart, drawStart)
        } else {
            content.substring(bodyStart)
        }
        return body.trim()
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
        if (!fm.contains("excalidraw-open-md:")) {
            fm += "\nexcalidraw-open-md: true"
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
            appendLine("excalidraw-open-md: true")
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
        val withoutComments = PAIRED_COMMENT_REGEX.replace(content, "")
        val textBody = extractMarkdownBody(withoutComments)
        val head = stripExcalidrawFrontmatterBlock(withoutComments)
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
                    while (i < lines.size && EXCALIDRAW_TAG_REGEX.matches(lines[i])) i++
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

    private fun buildDrawingSection(strokes: List<Stroke>): String {
        val templateRoot = runCatching { ExcalidrawUnifiedTemplate.defaultDrawingRoot() }.getOrNull()
        val drawing = buildDrawingJsonForExport(strokes, templateRoot)
        return ExcalidrawUnifiedTemplate.wrapDrawingJson(drawing.toString(2))
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
            .put("id", stroke.id.take(20).replace("-", ""))
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
            val pointsJson = native.getJSONArray("points")
            val points = mutableListOf<StrokePoint>()
            for (i in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONArray(i)
                points.add(
                    StrokePoint(
                        x = p.getDouble(0).toFloat(),
                        y = p.getDouble(1).toFloat(),
                        pressure = if (p.isNull(2)) null else p.getDouble(2).toFloat(),
                        tiltX = if (p.isNull(3)) null else p.getInt(3),
                        tiltY = if (p.isNull(4)) null else p.getInt(4),
                        dt = if (p.isNull(5)) null else p.getInt(5).toUShort()
                    )
                )
            }
            if (points.isEmpty()) return null
            val pen = Pen.fromString(native.optString("pen", Pen.BALLPEN.penName))
            Stroke(
                id = UUID.randomUUID().toString(),
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
            val points = mutableListOf<StrokePoint>()
            for (i in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONArray(i)
                val pressure = pressures?.optDouble(i, 0.5) ?: 0.5
                points.add(
                    StrokePoint(
                        x = originX + p.getDouble(0).toFloat(),
                        y = originY + p.getDouble(1).toFloat(),
                        pressure = (pressure * 4096).toFloat().coerceIn(1f, 4096f)
                    )
                )
            }
            if (points.isEmpty()) return null
            val width = element.optDouble("strokeWidth", 2.0).toFloat() / EXCALIDRAW_WIDTH_SCALE
            Stroke(
                id = UUID.randomUUID().toString(),
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
