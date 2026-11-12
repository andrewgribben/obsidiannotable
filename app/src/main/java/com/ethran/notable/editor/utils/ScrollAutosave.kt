package com.ethran.notable.editor.utils

/**
 * Tracks whether a page has unsaved stroke changes, to decide whether a debounced
 * background save (triggered once page scroll settles) should actually do work.
 *
 * Pure state, no coroutines/Android dependencies, so the decision logic is
 * unit-testable without a debounce timer or the real save pipeline.
 */
class ScrollAutosaveTracker {

    @Volatile
    private var dirty: Boolean = false

    /** Call whenever strokes are added, removed or updated on the page. */
    fun markDirty() {
        dirty = true
    }

    /**
     * Call when the debounced scroll-settle timer fires. Returns true (and clears the
     * dirty flag) when a save should run; returns false, leaving state untouched, when
     * there is nothing new to save since the last successful save was scheduled.
     */
    fun consumeIfDirty(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }

    fun isDirty(): Boolean = dirty
}
