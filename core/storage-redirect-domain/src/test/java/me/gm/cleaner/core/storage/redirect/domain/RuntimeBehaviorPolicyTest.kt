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
    fun `快照行为字段直达无需投影桥`() {
        val snapshot = RedirectPolicySnapshot(
            behavior = RuntimeBehaviorPolicy(
                deniedPackages = setOf("blocked"),
                recordSharedStorage = true,
                recordExternalAppSpecificStorage = true,
                aggressivelyPromptForReadingMediaFiles = true,
                upsertRecords = false,
            ),
        )
        assertEquals(setOf("blocked"), snapshot.behavior.deniedPackages)
        assertTrue(snapshot.behavior.recordSharedStorage)
        assertTrue(snapshot.behavior.recordExternalAppSpecificStorage)
        assertTrue(snapshot.behavior.aggressivelyPromptForReadingMediaFiles)
        assertFalse(snapshot.behavior.upsertRecords)
        assertTrue(snapshot.behavior.shouldRecordExternal("allowed"))
        assertFalse(snapshot.behavior.shouldRecordExternal("blocked"))
    }
}
