package com.ethran.notable.io.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Covers link target parsing/resolution for the vault note reader: percent-encoded
 * relative markdown links, `#heading` / `^block` anchors, and the two ways a target
 * can point at a note (relative to the current note's folder, or vault-wide by path
 * or unique file name) regardless of which screen opened the note.
 */
class VaultLinkResolutionTest {

    private fun tempVault(): File = createTempDirectory("vault-link-test").toFile()

    private fun note(root: File, relativePath: String, body: String = "# Note\n"): File {
        val file = File(root, relativePath.replace('/', File.separatorChar))
        file.parentFile?.mkdirs()
        file.writeText(body)
        return file
    }

    // -- decodeVaultLinkTarget ------------------------------------------------------

    @Test
    fun `decodes percent-encoded spaces and punctuation`() {
        val decoded = decodeVaultLinkTarget(
            "Module%20Guide/1%20Learning%20outcomes.md#1.1%20Knowledge%20and%20understanding"
        )
        assertEquals(
            "Module Guide/1 Learning outcomes.md#1.1 Knowledge and understanding",
            decoded
        )
    }

    @Test
    fun `decodes percent-encoded colon and question mark in an anchor`() {
        val decoded = decodeVaultLinkTarget(
            "3%20W211.md#3.2%20Workload%3A%20how%20long%20should%20everything%20take%3F"
        )
        assertEquals("3 W211.md#3.2 Workload: how long should everything take?", decoded)
    }

    @Test
    fun `leaves a plain target unchanged`() {
        assertEquals("Other Note", decodeVaultLinkTarget("Other Note"))
    }

    @Test
    fun `does not treat plus as a space (unlike URLDecoder)`() {
        assertEquals("a+b.md", decodeVaultLinkTarget("a+b.md"))
    }

    @Test
    fun `passes through a malformed percent escape literally`() {
        assertEquals("100% done.md", decodeVaultLinkTarget("100% done.md"))
    }

    // -- normalizeVaultPath -----------------------------------------------------------

    @Test
    fun `normalizeVaultPath collapses dot and dot-dot segments`() {
        assertEquals("Module Guide/File.md", normalizeVaultPath("A/../Module Guide/./File.md"))
    }

    // -- resolveNoteLink: the real toc.md shape --------------------------------------

    @Test
    fun `resolves a percent-encoded relative markdown link with a heading anchor`() {
        val root = tempVault()
        note(root, "20 Modules/Year 2/W211/W211 Master table of contents.md")
        val target =
            note(root, "20 Modules/Year 2/W211/Module Guide/1 Learning outcomes.md")
        val index = VaultIndex(root)
        index.refresh()

        val resolved = resolveNoteLink(
            index,
            currentDir = "20 Modules/Year 2/W211",
            target = "Module%20Guide/1%20Learning%20outcomes.md" +
                "#1.1%20Knowledge%20and%20understanding"
        )

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `relative link to an existing file resolves without scanning the vault`() {
        val root = tempVault()
        val target = note(root, "W211/Module Guide/Introduction.md")
        val index = VaultIndex(root)

        val resolved = resolveNoteLink(index, "W211", "Module%20Guide/Introduction.md#Intro")

        assertEquals("W211/Module Guide/Introduction.md", resolved?.relativePath)
        assertEquals(target, resolved?.file)
        assertTrue("index must stay unscanned", index.allNotes().isEmpty())
    }

    @Test
    fun `resolves a link with a custom block-ref-style anchor`() {
        val root = tempVault()
        val target = note(root, "Module Guide/3 Module assessment.md")
        val index = VaultIndex(root)
        index.refresh()

        val resolved = resolveNoteLink(
            index,
            currentDir = "",
            target = "Module%20Guide/3%20Module%20assessment.md#s_3_2"
        )

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `resolves a vault-root-relative link starting with a slash`() {
        val root = tempVault()
        val target = note(root, "Introduction/1 Welcome.md")
        val index = VaultIndex(root)
        index.refresh()

        val resolved = resolveNoteLink(
            index,
            currentDir = "Some/Other/Folder",
            target = "/Introduction/1%20Welcome.md"
        )

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `wikilink with an alias and a heading anchor resolves by target, not alias`() {
        val root = tempVault()
        val target = note(root, "Notes/Target Note.md")
        val index = VaultIndex(root)
        index.refresh()

        // MarkdownRenderer already strips the "|alias" part before the target reaches
        // resolution; this is the target it hands openLink for `[[Target Note#Heading|alias]]`.
        val resolved = resolveNoteLink(index, currentDir = "Notes", target = "Target Note#Heading")

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `falls back to a unique vault-wide filename match when not found relative to the note`() {
        val root = tempVault()
        val target = note(root, "Deep/Folder/Structure/Unique Note.md")
        val index = VaultIndex(root)
        index.refresh()

        val resolved = resolveNoteLink(
            index,
            currentDir = "Somewhere/Else",
            target = "Unique Note"
        )

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `returns null for a target that matches nothing`() {
        val root = tempVault()
        note(root, "Notes/Existing.md")
        val index = VaultIndex(root)
        index.refresh()

        assertNull(resolveNoteLink(index, currentDir = "Notes", target = "Missing.md"))
    }

    @Test
    fun `returns null when the index is not yet available`() {
        assertNull(resolveNoteLink(null, currentDir = "Notes", target = "Anything.md"))
    }

    @Test
    fun `resolves lazily even when the index has never been refreshed`() {
        val root = tempVault()
        val target = note(root, "Module Guide/Introduction.md")
        val index = VaultIndex(root) // no refresh() call — resolveWikilink must self-populate

        val resolved = resolveNoteLink(
            index,
            currentDir = "",
            target = "Module%20Guide/Introduction.md"
        )

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), resolved?.relativePath)
    }

    @Test
    fun `resolution is identical whichever screen supplied the same vaultId and path`() {
        // Simulates opening the same note from the home bookshelf and from the vault
        // file browser: both hand NoteReaderView the same (vaultId, relativePath), so
        // they share one VaultIndexRegistry entry and resolve links the same way.
        val root = tempVault()
        note(root, "20 Modules/W211/W211 Master table of contents.md")
        val target = note(root, "20 Modules/W211/Module Guide/1 Learning outcomes.md")

        val fromBookshelf = VaultIndexRegistryTestHarness.forRoot(root)
        val fromFileBrowser = VaultIndexRegistryTestHarness.forRoot(root)
        assertTrue("both entry points must share the same index instance", fromBookshelf === fromFileBrowser)

        val currentDir = "20 Modules/W211"
        val target1 = resolveNoteLink(fromBookshelf, currentDir, "Module%20Guide/1%20Learning%20outcomes.md")
        val target2 = resolveNoteLink(fromFileBrowser, currentDir, "Module%20Guide/1%20Learning%20outcomes.md")

        assertEquals(target.relativeTo(root).path.replace('\\', '/'), target1?.relativePath)
        assertEquals(target1?.relativePath, target2?.relativePath)
    }
}

/** Minimal stand-in for [VaultIndexRegistry] keyed by root dir, for the identity test above. */
private object VaultIndexRegistryTestHarness {
    private val cache = mutableMapOf<String, VaultIndex>()

    fun forRoot(root: File): VaultIndex =
        cache.getOrPut(root.absolutePath) { VaultIndex(root) }
}
