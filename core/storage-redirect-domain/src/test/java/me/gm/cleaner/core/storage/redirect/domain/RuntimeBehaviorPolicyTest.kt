package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBehaviorPolicyTest {

    @Test
    fun `外部记录需总开关开且包不在名单内`() {
        val policy = RuntimeBehaviorPolicy(
            deniedPackages = setOf("blocked"),
            recordExternalAppSpecificStorage = true,
        )
        assertTrue(policy.shouldRecordExternal("allowed"))
        assertFalse(policy.shouldRecordExternal("blocked"))
    }

    @Test
    fun `总开关关闭时一律不记录`() {
        val policy = RuntimeBehaviorPolicy(
            deniedPackages = emptySet(),
            recordExternalAppSpecificStorage = false,
        )
        assertFalse(policy.shouldRecordExternal("any"))
    }

    @Test
    fun `快照投影保留行为字段`() {
        val snapshot = RedirectPolicySnapshot(
            denylist = setOf("blocked"),
            recordSharedStorage = true,
            recordExternalAppSpecificStorage = true,
            aggressivelyPromptForReadingMediaFiles = true,
            upsertRecords = false,
        )
        val policy = RuntimeBehaviorPolicy.project(snapshot)
        assertEquals(setOf("blocked"), policy.deniedPackages)
        assertTrue(policy.recordSharedStorage)
        assertTrue(policy.recordExternalAppSpecificStorage)
        assertTrue(policy.aggressivelyPromptForReadingMediaFiles)
        assertFalse(policy.upsertRecords)
        assertTrue(policy.shouldRecordExternal("allowed"))
        assertFalse(policy.shouldRecordExternal("blocked"))
    }
}
