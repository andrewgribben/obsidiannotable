package com.ethran.notable.ui.views

import com.ethran.notable.io.vault.VaultNote
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VaultSortTest {

    private fun note(name: String, modified: Long) = VaultNote(
        file = File("/vault/$name.md"),
        relativePath = "$name.md",
        name = name,
        hasInk = false,
        lastModified = modified
    )

    private val notes = listOf(
        note("banana", 200L),
        note("Apple", 300L),
        note("cherry", 100L)
    )

    @Test
    fun `name ascending is case-insensitive`() {
        val sorted = VaultSort.apply(notes, VaultSort.NAME_ASC)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.name })
    }

    @Test
    fun `name descending reverses`() {
        val sorted = VaultSort.apply(notes, VaultSort.NAME_DESC)
        assertEquals(listOf("cherry", "banana", "Apple"), sorted.map { it.name })
    }

    @Test
    fun `newest first sorts by modified time descending`() {
        val sorted = VaultSort.apply(notes, VaultSort.NEWEST)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.name })
    }

    @Test
    fun `oldest first sorts by modified time ascending`() {
        val sorted = VaultSort.apply(notes, VaultSort.OLDEST)
        assertEquals(listOf("cherry", "banana", "Apple"), sorted.map { it.name })
    }

    @Test
    fun `unknown mode falls back to name ascending`() {
        val sorted = VaultSort.apply(notes, "garbage")
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.name })
    }
}
