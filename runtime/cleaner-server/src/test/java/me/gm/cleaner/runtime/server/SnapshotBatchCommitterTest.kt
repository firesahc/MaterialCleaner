package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBatchCommitterTest {

    private val publications = listOf(
        SnapshotPublication("mount.json", "mount", "mount_changed"),
        SnapshotPublication("policy.json", "policy", "policy_changed"),
    )

    @Test
    fun `空批次不能被误报为发布成功`() {
        val result = SnapshotBatchCommitter.commit(
            publications = emptyList(),
            writeSnapshot = { _, _ -> true },
            signal = { true },
        )

        assertFalse(result.snapshotsWritten)
        assertFalse(result.signalsDelivered)
        assertFalse(result.successful)
    }

    @Test
    fun `所有快照写完后才发送第一个信号`() {
        val operations = mutableListOf<String>()

        val result = SnapshotBatchCommitter.commit(
            publications = publications,
            writeSnapshot = { name, _ ->
                operations += "write:$name"
                true
            },
            signal = { name ->
                operations += "signal:$name"
                true
            },
        )

        assertTrue(result.successful)
        assertEquals(
            listOf(
                "write:mount.json",
                "write:policy.json",
                "signal:mount_changed",
                "signal:policy_changed",
            ),
            operations,
        )
    }

    @Test
    fun `任一快照写入失败时不发送批次信号`() {
        val operations = mutableListOf<String>()

        val result = SnapshotBatchCommitter.commit(
            publications = publications,
            writeSnapshot = { name, _ ->
                operations += "write:$name"
                name != "policy.json"
            },
            signal = { name ->
                operations += "signal:$name"
                true
            },
        )

        assertFalse(result.snapshotsWritten)
        assertFalse(result.signalsDelivered)
        assertEquals(
            listOf("write:mount.json", "write:policy.json"),
            operations,
        )
    }

    @Test
    fun `单个通知失败仍尝试发送后续通知`() {
        val signaled = mutableListOf<String>()

        val result = SnapshotBatchCommitter.commit(
            publications = publications,
            writeSnapshot = { _, _ -> true },
            signal = { name ->
                signaled += name
                name != "mount_changed"
            },
        )

        assertTrue(result.snapshotsWritten)
        assertFalse(result.signalsDelivered)
        assertEquals(listOf("mount_changed", "policy_changed"), signaled)
    }
}
