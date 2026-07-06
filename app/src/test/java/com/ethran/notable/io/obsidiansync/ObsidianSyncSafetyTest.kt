package com.ethran.notable.io.obsidiansync

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianSyncSafetyTest {

    @Test
    fun defaultsToProbeOnly() {
        assertTrue(ObsidianSyncSafety.isProbeOnly)
    }

    @Test
    fun blocksMutatingSyncInProbeOnly() {
        val previous = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.ProbeOnly
            assertThrows(IllegalStateException::class.java) {
                ObsidianSyncSafety.requireMutatingSyncAllowed("push")
            }
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }

    @Test
    fun allowsMutatingSyncWhenFullSyncEnabled() {
        val previous = ObsidianSyncSafety.mode
        try {
            ObsidianSyncSafety.mode = ObsidianSyncSafety.Mode.FullSync
            ObsidianSyncSafety.requireMutatingSyncAllowed("push")
        } finally {
            ObsidianSyncSafety.mode = previous
        }
    }
}
