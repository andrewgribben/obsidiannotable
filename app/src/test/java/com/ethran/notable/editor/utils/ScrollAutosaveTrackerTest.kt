package com.ethran.notable.editor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAutosaveTrackerTest {

    @Test
    fun `clean tracker has nothing to save`() {
        val tracker = ScrollAutosaveTracker()

        assertFalse(tracker.isDirty())
        assertFalse(tracker.consumeIfDirty())
    }

    @Test
    fun `markDirty makes the next consume fire once`() {
        val tracker = ScrollAutosaveTracker()

        tracker.markDirty()
        assertTrue(tracker.isDirty())

        assertTrue(tracker.consumeIfDirty())
        // Already saved: a second settle with no new strokes should be a no-op.
        assertFalse(tracker.consumeIfDirty())
    }

    @Test
    fun `strokes changed again after a save triggers another save`() {
        val tracker = ScrollAutosaveTracker()

        tracker.markDirty()
        assertTrue(tracker.consumeIfDirty())

        tracker.markDirty()
        assertTrue(tracker.isDirty())
        assertTrue(tracker.consumeIfDirty())
        assertFalse(tracker.consumeIfDirty())
    }

    @Test
    fun `multiple markDirty calls before a save still only fire once`() {
        val tracker = ScrollAutosaveTracker()

        tracker.markDirty()
        tracker.markDirty()
        tracker.markDirty()

        assertTrue(tracker.consumeIfDirty())
        assertFalse(tracker.consumeIfDirty())
    }
}
