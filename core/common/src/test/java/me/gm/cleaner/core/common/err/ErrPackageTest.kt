package me.gm.cleaner.core.common.err

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorEventTest {

    @Test
    fun `紧凑表示包含全部非默认字段且不含空字段`() {
        val full = ErrorEvent(
            code = ErrorCodes.MOUNT_RULE_FAILED,
            errno = 13,
            subject = "com.example.app",
            pathDigest = "a1b2c3d4e5f6a7b8",
            generation = 7L,
            atElapsed = 123_456L,
            detail = "rule#3 /storage/a -> /data/b",
        ).toCompactString()
        assertTrue(full.contains("MOUNT.RULE.FAILED"))
        assertTrue(full.contains("errno=13"))
        assertTrue(full.contains("subject=com.example.app"))
        assertTrue(full.contains("path=a1b2c3d4e5f6a7b8"))
        assertTrue(full.contains("gen=7"))

        val minimal = ErrorEvent(code = ErrorCodes.SUP_PROC_DEAD, atElapsed = 1L).toCompactString()
        assertFalse(minimal.contains("errno"))
        assertFalse(minimal.contains("subject"))
        assertFalse(minimal.contains("gen="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空码被拒绝`() {
        ErrorEvent(code = "", atElapsed = 0L)
    }

    @Test
    fun `detail 缺省为 null`() {
        val event = ErrorEvent(code = ErrorCodes.BUS_QUEUE_FULL, atElapsed = 5L)
        assertNull(event.detail)
        assertEquals(ErrorCodes.BUS_QUEUE_FULL, event.code)
    }
}

class ErrorJournalTest {

    @Test
    fun `环形缓冲满容量时淘汰最旧事件`() {
        val journal = ErrorJournal(capacity = 3)
        val events = (1L..5L).map {
            ErrorEvent(
                code = if (it % 2 == 0L) ErrorCodes.MOUNT_RULE_FAILED else ErrorCodes.HOOK_JAVA_REG_FAILED,
                atElapsed = it,
                detail = "e$it",
            )
        }
        events.forEach { journal.record(it) }

        val snapshot = journal.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("e3", "e4", "e5"), snapshot.map { it.detail })
    }

    @Test
    fun `快照是副本后续写入不影响既有结果`() {
        val journal = ErrorJournal(capacity = 2)
        journal.record(ErrorEvent(code = ErrorCodes.BUS_WRITE_REJECTED, atElapsed = 1L))
        val taken = journal.snapshot()
        journal.record(ErrorEvent(code = ErrorCodes.BUS_WRITE_REJECTED, atElapsed = 2L))
        journal.clear()

        assertEquals(1, taken.size)
        assertEquals(0, journal.snapshot().size)
    }
}

class ErrorLogThrottleTest {

    @Test
    fun `窗口内同码只放行首个事件`() {
        val throttle = ErrorLogThrottle(windowMillis = 1_000L)
        assertTrue(throttle.tryAcquire("X.Y.Z", nowElapsed = 0L))
        assertFalse(throttle.tryAcquire("X.Y.Z", nowElapsed = 500L))
        assertTrue(throttle.tryAcquire("X.Y.W", nowElapsed = 500L))
        assertTrue(throttle.tryAcquire("X.Y.Z", nowElapsed = 1_100L))
    }

    @Test
    fun `reset 后立即放行`() {
        val throttle = ErrorLogThrottle(windowMillis = 60_000L)
        assertTrue(throttle.tryAcquire("A.B.C", nowElapsed = 0L))
        assertFalse(throttle.tryAcquire("A.B.C", nowElapsed = 10L))
        throttle.reset("A.B.C")
        assertTrue(throttle.tryAcquire("A.B.C", nowElapsed = 20L))
    }
}
