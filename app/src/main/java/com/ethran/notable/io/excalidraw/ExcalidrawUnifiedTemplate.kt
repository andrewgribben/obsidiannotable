package com.ethran.notable.io.excalidraw

import blazing.chain.LZSEncoding
import com.ethran.notable.data.db.Stroke
import org.json.JSONObject
import java.io.BufferedReader

/**
 * Obsidian unified-note layout from [Template.excalidraw.md]:
 * frontmatter + optional markdown body + `%%` comment block with compressed JSON.
 */
object ExcalidrawUnifiedTemplate {

    private const val ASSET_NAME = "Template.excalidraw.md"
    private const val COMPRESSED_CHUNK_SIZE = 256

    private var defaultDrawingRoot: JSONObject? = null

    fun initFromAsset(openAsset: (String) -> BufferedReader) {
        openAsset(ASSET_NAME).use { reader ->
            initFromMarkdown(reader.readText())
        }
    }

    fun initFromMarkdown(templateMarkdown: String) {
        val json = ExcalidrawSerializer.extractDrawingJson(templateMarkdown)
            ?: throw IllegalArgumentException("Template missing drawing JSON")
        defaultDrawingRoot = JSONObject(json)
    }

    fun defaultDrawingRoot(): JSONObject {
        val root = defaultDrawingRoot
        check(root != null) { "ExcalidrawUnifiedTemplate not initialized" }
        return JSONObject(root.toString())
    }

    fun buildNewCapture(createdDate: String, strokes: List<Stroke>): String {
        return buildString {
            appendLine("---")
            appendLine("excalidraw-plugin: parsed")
            appendLine("excalidraw-open-md: false")
            appendLine("tags: [excalidraw]")
            appendLine("created: \"[[$createdDate]]\"")
            appendLine("---")
            appendLine()
            append(wrapDrawingBlock(strokes))
            appendLine()
        }
    }

    fun wrapDrawingBlock(strokes: List<Stroke>): String {
        val json = ExcalidrawSerializer.buildDrawingJsonForExport(strokes, defaultDrawingRoot())
        return wrapDrawingJson(json.toString())
    }

    fun wrapDrawingJson(jsonBody: String): String {
        val compressed = LZSEncoding.compressToBase64(jsonBody)
            .chunked(COMPRESSED_CHUNK_SIZE)
            .joinToString("\n\n")
        return buildString {
            appendLine("%%")
            appendLine("# Excalidraw Data")
            appendLine()
            appendLine("## Text Elements")
            appendLine()
            appendLine("## Drawing")
            appendLine("```compressed-json")
            appendLine(compressed)
            appendLine("```")
            append("%%")
        }
    }
}
