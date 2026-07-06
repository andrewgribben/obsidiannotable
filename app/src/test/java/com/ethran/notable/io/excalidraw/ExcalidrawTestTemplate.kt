package com.ethran.notable.io.excalidraw

import java.io.File

object ExcalidrawTestTemplate {
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        val candidates = listOf(
            File("src/main/assets/Template.excalidraw.md"),
            File("app/src/main/assets/Template.excalidraw.md"),
        )
        val template = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Template.excalidraw.md not found for tests")
        ExcalidrawUnifiedTemplate.initFromMarkdown(template)
        initialized = true
    }
}
