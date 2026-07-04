package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultFileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String = "note.md"): File = File(folder.root, name)

    @Test
    fun `read returns content and matching hash`() {
        val f = file()
        f.writeText("hello vault")
        val result = VaultFileStore.read(f)
        assertNotNull(result)
        assertEquals("hello vault", result!!.content)
        assertEquals(VaultFileStore.hashOf("hello vault"), result.hash)
    }

    @Test
    fun `read of missing file returns null and missing hash`() {
        assertNull(VaultFileStore.read(file("nope.md")))
        assertEquals(VaultFileStore.HASH_MISSING, VaultFileStore.currentHash(file("nope.md")))
    }

    @Test
    fun `unconditional write creates the file`() {
        val f = file()
        val result = VaultFileStore.write(f, "content")
        assertTrue(result is VaultFileStore.WriteResult.Success)
        assertEquals("content", f.readText())
    }

    @Test
    fun `write creates missing parent directories`() {
        val f = File(folder.root, "sub/dir/note.md")
        val result = VaultFileStore.write(f, "nested")
        assertTrue(result is VaultFileStore.WriteResult.Success)
        assertEquals("nested", f.readText())
    }

    @Test
    fun `conditional write succeeds when hash matches`() {
        val f = file()
        VaultFileStore.write(f, "v1")
        val read = VaultFileStore.read(f)!!
        val result = VaultFileStore.write(f, "v2", expectedHash = read.hash)
        assertTrue(result is VaultFileStore.WriteResult.Success)
        assertEquals("v2", f.readText())
    }

    @Test
    fun `conditional write conflicts when file changed externally`() {
        val f = file()
        VaultFileStore.write(f, "v1")
        val read = VaultFileStore.read(f)!!
        // Simulate Obsidian Sync changing the file underneath us
        f.writeText("external change")

        val result = VaultFileStore.write(f, "v2", expectedHash = read.hash)
        assertTrue(result is VaultFileStore.WriteResult.Conflict)
        val conflict = result as VaultFileStore.WriteResult.Conflict
        assertEquals("external change", conflict.currentContent)
        // Nothing was written
        assertEquals("external change", f.readText())
    }

    @Test
    fun `conditional write with missing hash succeeds only when file absent`() {
        val f = file()
        val created = VaultFileStore.write(f, "new", expectedHash = VaultFileStore.HASH_MISSING)
        assertTrue(created is VaultFileStore.WriteResult.Success)

        val conflicted = VaultFileStore.write(f, "again", expectedHash = VaultFileStore.HASH_MISSING)
        assertTrue(conflicted is VaultFileStore.WriteResult.Conflict)
    }

    @Test
    fun `conflict copy is written next to the original`() {
        val f = file("My Note.md")
        f.writeText("original")
        val copy = VaultFileStore.writeConflictCopy(f, "my unsaved version")
        assertNotNull(copy)
        assertTrue(copy!!.name.startsWith("My Note (conflict "))
        assertTrue(copy.name.endsWith(".md"))
        assertEquals("my unsaved version", copy.readText())
        assertEquals("original", f.readText())
    }

    @Test
    fun `no temp files remain after write`() {
        val f = file()
        VaultFileStore.write(f, "content")
        val leftovers = folder.root.listFiles()!!.filter { it.name.contains(".tmp-") }
        assertTrue(leftovers.isEmpty())
    }
}
