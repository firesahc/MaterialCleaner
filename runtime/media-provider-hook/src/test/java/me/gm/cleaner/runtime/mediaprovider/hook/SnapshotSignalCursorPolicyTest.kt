package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotSignalCursorPolicyTest {

    @Test
    fun `signal必须在快照消费前捕获`() {
        val order = mutableListOf<String>()

        consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = 0L,
            captureSignalTimestamp = {
                order += "signal"
                1L
            },
            consumeSnapshot = {
                order += "snapshot"
                SnapshotConsumeOutcome(succeeded = true)
            },
        )

        assertEquals(listOf("signal", "snapshot"), order)
    }

    @Test
    fun `消费期间signal增长时只确认读取前水位`() {
        var signal = 10L

        val result = consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = 8L,
            captureSignalTimestamp = { signal },
            consumeSnapshot = {
                signal = 11L
                SnapshotConsumeOutcome(succeeded = true)
            },
        )

        assertEquals(10L, result.acknowledgedTimestamp)
        assertEquals(11L, signal)
    }

    @Test
    fun `消费失败不推进游标`() {
        val result = consumeAfterSignalCapture(8L, { 10L }) {
            SnapshotConsumeOutcome(succeeded = false)
        }

        assertEquals(8L, result.acknowledgedTimestamp)
    }

    @Test
    fun `成功消费推进到捕获水位`() {
        val result = consumeAfterSignalCapture(8L, { 10L }) {
            SnapshotConsumeOutcome(succeeded = true)
        }

        assertEquals(10L, result.acknowledgedTimestamp)
    }

    @Test
    fun `signal回退不能使游标倒退`() {
        val result = consumeAfterSignalCapture(12L, { 10L }) {
            SnapshotConsumeOutcome(succeeded = true)
        }

        assertEquals(12L, result.acknowledgedTimestamp)
    }

    @Test
    fun `同版本未改变仍确认signal`() {
        val result = consumeAfterSignalCapture(8L, { 10L }) {
            SnapshotConsumeOutcome(succeeded = true, changed = false)
        }

        assertEquals(10L, result.acknowledgedTimestamp)
        assertEquals(false, result.changed)
    }

    @Test
    fun `策略改变同时推进游标并传播changed`() {
        val result = consumeAfterSignalCapture(8L, { 10L }) {
            SnapshotConsumeOutcome(succeeded = true, changed = true)
        }

        assertEquals(10L, result.acknowledgedTimestamp)
        assertEquals(true, result.changed)
    }

    @Test
    fun `消费异常不能产生确认结果`() {
        var acknowledged = 8L

        assertThrows(IllegalStateException::class.java) {
            acknowledged = consumeAfterSignalCapture(acknowledged, { 10L }) {
                throw IllegalStateException("snapshot failed")
            }.acknowledgedTimestamp
        }

        assertEquals(8L, acknowledged)
    }
}
