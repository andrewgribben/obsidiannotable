package com.ethran.notable.io.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NoteEditGuardTest {

    private fun freshKey() = NoteEditGuard.noteKey("vault-${UUID.randomUUID()}", "Note.md")

    @Test
    fun `second owner cannot acquire a held note`() {
        val key = freshKey()
        assertTrue(NoteEditGuard.tryAcquire(key, "window-a"))
        assertFalse(NoteEditGuard.tryAcquire(key, "window-b"))
    }

    @Test
    fun `same owner can re-acquire`() {
        val key = freshKey()
        assertTrue(NoteEditGuard.tryAcquire(key, "window-a"))
        assertTrue(NoteEditGuard.tryAcquire(key, "window-a"))
    }

    @Test
    fun `release frees the note for other owners`() {
        val key = freshKey()
        assertTrue(NoteEditGuard.tryAcquire(key, "window-a"))
        NoteEditGuard.release(key, "window-a")
        assertTrue(NoteEditGuard.tryAcquire(key, "window-b"))
    }

    @Test
    fun `release by non-owner does not free the note`() {
        val key = freshKey()
        assertTrue(NoteEditGuard.tryAcquire(key, "window-a"))
        NoteEditGuard.release(key, "window-b")
        assertFalse(NoteEditGuard.tryAcquire(key, "window-b"))
    }

    @Test
    fun `different notes are independent`() {
        val keyA = freshKey()
        val keyB = freshKey()
        assertTrue(NoteEditGuard.tryAcquire(keyA, "window-a"))
        assertTrue(NoteEditGuard.tryAcquire(keyB, "window-b"))
    }
}
