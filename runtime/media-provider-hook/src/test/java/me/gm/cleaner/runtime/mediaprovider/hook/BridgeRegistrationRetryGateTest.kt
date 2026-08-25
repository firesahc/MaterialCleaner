package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRegistrationRetryGateTest {
    @Test
    fun `等待或运行中的外部请求被合并`() {
        val gate = BridgeRegistrationRetryGate()

        assertTrue(gate.requestSchedule())
        assertFalse(gate.requestSchedule())
        assertTrue(gate.beginScheduledRun())
        assertFalse(gate.requestSchedule())
        assertFalse(gate.beginScheduledRun())
    }

    @Test
    fun `失败进入等待后不会重置预算`() {
        val gate = BridgeRegistrationRetryGate()

        assertTrue(gate.requestSchedule())
        assertTrue(gate.beginScheduledRun())
        gate.markWaiting()

        assertFalse(gate.requestSchedule())
        assertTrue(gate.beginScheduledRun())
    }

    @Test
    fun `成功或调度失败后允许新恢复序列`() {
        val gate = BridgeRegistrationRetryGate()

        assertTrue(gate.requestSchedule())
        assertTrue(gate.beginScheduledRun())
        gate.markIdle()

        assertTrue(gate.requestSchedule())
    }
}
