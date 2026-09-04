package com.ethran.notable.io.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VaultImageResolverTest {

    @Test
    fun `resolves image relative to note directory`() {
        val root = File.createTempFile("vault", "").apply { delete(); mkdir() }
        val noteDir = File(root, "notes").apply { mkdir() }
        val image = File(noteDir, "photo.png").apply { writeBytes(byteArrayOf(0)) }

        val resolved = VaultImageResolver.resolve(root, "notes/page.md", "photo.png")
        assertEquals(image.absolutePath, resolved?.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `resolves image relative to vault root`() {
        val root = File.createTempFile("vault", "").apply { delete(); mkdir() }
        val image = File(root, "attachments/diagram.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0))
        }

        val resolved = VaultImageResolver.resolve(root, "inbox/note.md", "attachments/diagram.jpg")
        assertEquals(image.absolutePath, resolved?.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `returns null for remote urls`() {
        val root = File.createTempFile("vault", "").apply { delete(); mkdir() }
        assertNull(VaultImageResolver.resolve(root, "note.md", "https://example.com/a.png"))
        root.deleteRecursively()
    }

    @Test
    fun `returns null when file is missing`() {
        val root = File.createTempFile("vault", "").apply { delete(); mkdir() }
        assertNull(VaultImageResolver.resolve(root, "note.md", "missing.png"))
        root.deleteRecursively()
    }
}
