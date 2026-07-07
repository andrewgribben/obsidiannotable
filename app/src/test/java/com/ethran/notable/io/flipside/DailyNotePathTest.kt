package com.ethran.notable.io.flipside

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DailyNotePathTest {

    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-07-07")!!

    @Test
    fun `daily note defaults to inbox folder`() {
        assertEquals(
            "inbox/2026-07-07.md",
            FlipSideManager.dailyNoteRelativePath("inbox", date, dailyNoteFolder = "")
        )
    }

    @Test
    fun `daily note uses configured folder relative to vault root`() {
        assertEquals(
            "Daily/2026-07-07.md",
            FlipSideManager.dailyNoteRelativePath("inbox", date, dailyNoteFolder = "Daily")
        )
    }

    @Test
    fun `daily note returns null when inbox is unknown and folder blank`() {
        assertNull(FlipSideManager.dailyNoteRelativePath(null, date, dailyNoteFolder = ""))
    }
}
