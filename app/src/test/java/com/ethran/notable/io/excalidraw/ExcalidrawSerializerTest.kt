package com.ethran.notable.io.excalidraw

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ExcalidrawSerializerTest {

    init {
        ExcalidrawTestTemplate.ensureInitialized()
    }

    private fun sampleStroke(pen: Pen = Pen.FOUNTAIN): Stroke {
        val points = listOf(
            StrokePoint(x = 100f, y = 200f, pressure = 1000f, tiltX = 10, tiltY = -5, dt = 0u),
            StrokePoint(x = 110f, y = 210f, pressure = 2000f, tiltX = 11, tiltY = -4, dt = 8u),
            StrokePoint(x = 125f, y = 205f, pressure = 1500f, tiltX = 12, tiltY = -3, dt = 16u)
        )
        return Stroke(
            size = 7.5f,
            pen = pen,
            color = 0xFF112233.toInt(),
            maxPressure = 4096,
            top = 200f, bottom = 210f, left = 100f, right = 125f,
            points = points,
            pageId = "page-1",
            createdAt = Date(1700000000000L)
        )
    }

    @Test
    fun `round trip preserves native stroke fidelity`() {
        val original = sampleStroke()
        val content = ExcalidrawSerializer.serialize(listOf(original))
        val parsed = ExcalidrawSerializer.parse(content, "page-2")

        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        val restored = parsed[0]

        assertEquals("page-2", restored.pageId)
        assertEquals(original.pen, restored.pen)
        assertEquals(original.size, restored.size, 0f)
        assertEquals(original.color, restored.color)
        assertEquals(original.maxPressure, restored.maxPressure)
        assertEquals(original.createdAt.time, restored.createdAt.time)
        assertEquals(original.points.size, restored.points.size)
        original.points.zip(restored.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 0f)
            assertEquals(a.y, b.y, 0f)
            assertEquals(a.pressure, b.pressure)
            assertEquals(a.tiltX, b.tiltX)
            assertEquals(a.tiltY, b.tiltY)
            assertEquals(a.dt, b.dt)
        }
    }

    @Test
    fun `serializeUnified uses template json block and preserves text body`() {
        val stroke = sampleStroke()
        val content = ExcalidrawSerializer.serializeUnified("# Title\n\nBody text", listOf(stroke))
        assertTrue(content.contains("excalidraw-plugin: parsed"))
        assertTrue(content.contains("excalidraw-open-md: true"))
        assertTrue(content.contains("# Title"))
        assertTrue(content.contains("Body text"))
        assertTrue(content.contains("%%"))
        assertTrue(content.contains("```json"))
        assertFalse(content.contains("```compressed-json"))

        val parsed = ExcalidrawSerializer.parse(content, "page-2")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("# Title\n\nBody text", ExcalidrawSerializer.extractMarkdownBody(content))
    }

    @Test
    fun `replaceDrawingInUnified preserves text when strokes update`() {
        val original = ExcalidrawSerializer.serializeUnified("Keep me", listOf(sampleStroke()))
        val updated = ExcalidrawSerializer.replaceDrawingInUnified(
            original,
            listOf(sampleStroke(), sampleStroke(Pen.BALLPEN))
        )
        assertTrue(updated.contains("Keep me"))
        assertTrue(updated.contains("```json"))
        val parsed = ExcalidrawSerializer.parse(updated, "page-1")
        assertEquals(2, parsed!!.size)
    }

    @Test
    fun `ensureExcalidrawFrontmatter merges into existing yaml`() {
        val note = """
            ---
            title: My Note
            pdf: "[[old.pdf]]"
            flip-side: "[[sidecar]]"
            ---

            # Content
        """.trimIndent()
        val merged = ExcalidrawSerializer.ensureExcalidrawFrontmatter(note)
        assertTrue(merged.contains("excalidraw-plugin: parsed"))
        assertTrue(merged.contains("title: My Note"))
        assertFalse(merged.contains("pdf:"))
        assertFalse(merged.contains("flip-side:"))
        assertTrue(merged.contains("# Content"))
    }

    @Test
    fun `serialized output is an obsidian excalidraw markdown file`() {
        val content = ExcalidrawSerializer.serialize(listOf(sampleStroke()))
        assertTrue(content.startsWith("---"))
        assertTrue(content.contains("excalidraw-plugin: parsed"))
        assertTrue(content.contains("\"type\": \"excalidraw\""))
        assertTrue(content.contains("\"freedraw\""))
        assertTrue(content.contains("```json"))
    }

    @Test
    fun `parses plain freedraw element created in excalidraw itself`() {
        val json = """
            {
              "type": "excalidraw",
              "version": 2,
              "elements": [
                {
                  "type": "freedraw",
                  "id": "abc",
                  "x": 50.0,
                  "y": 60.0,
                  "strokeColor": "#ff0000",
                  "strokeWidth": 2.0,
                  "points": [[0, 0], [10, 5], [20, 10]],
                  "pressures": [0.3, 0.5, 0.7],
                  "isDeleted": false
                }
              ]
            }
        """.trimIndent()
        val parsed = ExcalidrawSerializer.parse(json, "page-1")
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        val stroke = parsed[0]
        assertEquals(Pen.BALLPEN, stroke.pen)
        assertEquals(0xFFFF0000.toInt(), stroke.color)
        assertEquals(3, stroke.points.size)
        assertEquals(50f, stroke.points[0].x, 0f)
        assertEquals(60f, stroke.points[0].y, 0f)
        assertEquals(70f, stroke.points[2].x, 0f)
    }

    @Test
    fun `deleted and non-freedraw elements are skipped`() {
        val json = """
            {
              "elements": [
                {"type": "rectangle", "x": 0, "y": 0},
                {"type": "freedraw", "x": 0, "y": 0, "points": [[0,0],[5,5]], "isDeleted": true}
              ]
            }
        """.trimIndent()
        val parsed = ExcalidrawSerializer.parse(json, "page-1")
        assertNotNull(parsed)
        assertTrue(parsed!!.isEmpty())
    }

    @Test
    fun `extractDrawingJson finds the fenced block after the drawing heading`() {
        val md = """
            ---
            excalidraw-plugin: parsed
            ---
            Some text

            # Drawing
            ```json
            {"elements": []}
            ```
            %%
        """.trimIndent()
        assertEquals("""{"elements": []}""", ExcalidrawSerializer.extractDrawingJson(md))
    }

    @Test
    fun `extractDrawingJson accepts raw json`() {
        assertEquals("""{"a":1}""", ExcalidrawSerializer.extractDrawingJson("""{"a":1}"""))
    }

    @Test
    fun `extractDrawingJson decompresses obsidian compressed-json block`() {
        val json = """{"type":"excalidraw","version":2,"elements":[]}"""
        val compressed = blazing.chain.LZSEncoding.compressToBase64(json)
        val md = """
            ---
            excalidraw-plugin: parsed
            ---
            ## Drawing
            ```compressed-json
            $compressed
            ```
            %%
        """.trimIndent()
        assertEquals(json, ExcalidrawSerializer.extractDrawingJson(md))
    }

    @Test
    fun `extractDrawingJson prefers compressed-json over stale json block`() {
        val freshJson = """{"type":"excalidraw","version":2,"elements":[{"type":"freedraw","x":0,"y":0,"points":[[0,0],[5,5]],"isDeleted":false}]}"""
        val compressed = blazing.chain.LZSEncoding.compressToBase64(freshJson)
        val md = """
            ---
            excalidraw-plugin: parsed
            ---
            # Drawing
            ```json
            {"type":"excalidraw","version":2,"elements":[]}
            ```
            %%
            ## Drawing
            ```compressed-json
            $compressed
            ```
            %%
        """.trimIndent()
        assertEquals(freshJson, ExcalidrawSerializer.extractDrawingJson(md))
    }

    @Test
    fun `parse returns null when no drawing found`() {
        assertNull(ExcalidrawSerializer.parse("just some markdown", "page-1"))
    }

    @Test
    fun `hasNonemptyInk detects strokes without full parse`() {
        val unified = ExcalidrawSerializer.serializeUnified("# note", listOf(sampleStroke()))
        assertTrue(ExcalidrawSerializer.hasNonemptyInk(unified))
    }

    @Test
    fun `hasNonemptyInk is false for empty elements`() {
        val unified = ExcalidrawSerializer.serializeUnified("# note", emptyList())
        assertFalse(ExcalidrawSerializer.hasNonemptyInk(unified))
    }

    @Test
    fun `hasNonemptyInkForListing scans large file tail only`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val note = java.io.File(dir, "large.md")
        val body = buildString {
            appendLine("---")
            appendLine("excalidraw-plugin: parsed")
            appendLine("---")
            appendLine()
            appendLine("# text")
            appendLine()
            append("%%\n# Excalidraw Data\n## Drawing\n```json\n")
            append("""{"type":"excalidraw","version":2,"elements":[""")
            repeat(200_000) { append("""{"type":"freedraw","x":0,"y":0,"points":[[0,0]],"isDeleted":false},""") }
            append("""{"type":"freedraw","x":1,"y":1,"points":[[0,0],[1,1]],"isDeleted":false}""")
            append("""],"files":{}}""")
            appendLine()
            appendLine("```")
            append("%%")
        }
        note.writeText(body)
        assertTrue(note.length() > 512 * 1024)
        assertTrue(ExcalidrawSerializer.hasNonemptyInkForListing(note))
        dir.deleteRecursively()
    }

    @Test
    fun `color conversion round trips`() {
        assertEquals("#112233", ExcalidrawSerializer.colorToHex(0xFF112233.toInt()))
        assertEquals(0xFF112233.toInt(), ExcalidrawSerializer.hexToColor("#112233"))
        assertEquals(0xFFFFFFFF.toInt(), ExcalidrawSerializer.hexToColor("#fff"))
        assertEquals(0xFF000000.toInt(), ExcalidrawSerializer.hexToColor("garbage"))
    }
}
