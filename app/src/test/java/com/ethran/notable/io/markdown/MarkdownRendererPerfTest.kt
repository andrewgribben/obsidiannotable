package com.ethran.notable.io.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for the freeze reported when opening a link-heavy table of contents
 * note (hundreds of nested markdown links, ~64KB). Uses a real exported fixture.
 */
class MarkdownRendererPerfTest {

    private fun fixture(): String {
        val resource = javaClass.classLoader?.getResource("markdown/toc.md")
            ?: error("missing test fixture markdown/toc.md")
        return File(resource.toURI()).readText()
    }

    @Test
    fun `renders a large link-heavy table of contents quickly`() {
        val source = fixture()

        // Warm up the JIT so the measured run reflects steady-state performance.
        MarkdownRenderer.render(source)

        val start = System.nanoTime()
        val rendered = MarkdownRenderer.render(source)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        println("MarkdownRenderer.render(toc.md) took ${elapsedMs}ms for ${rendered.links.size} links")

        assertTrue("expected hundreds of links in the fixture", rendered.links.size > 100)
        assertTrue(
            "render took too long ($elapsedMs ms) — should comfortably fit a single frame budget",
            elapsedMs < 500
        )
    }
}
