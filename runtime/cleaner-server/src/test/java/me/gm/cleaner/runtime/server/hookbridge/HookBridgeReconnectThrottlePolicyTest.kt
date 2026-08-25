package me.gm.cleaner.runtime.server.hookbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookBridgeReconnectThrottlePolicyTest {

    @Test
    fun `首次重连立即允许`() {
        assertTrue(HookBridgeReconnectThrottlePolicy.shouldAttempt(1_000L, 0L))
    }

    @Test
    fun `最小间隔内抑制重复重连`() {
        assertFalse(
            HookBridgeReconnectThrottlePolicy.shouldAttempt(
                nowUptimeMs = 5_999L,
                lastAttemptUptimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun `达到最小间隔后允许重连`() {
        assertTrue(
            HookBridgeReconnectThrottlePolicy.shouldAttempt(
                nowUptimeMs = 6_000L,
                lastAttemptUptimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun `单调时钟回拨期间保持抑制`() {
        assertFalse(
            HookBridgeReconnectThrottlePolicy.shouldAttempt(
                nowUptimeMs = 4_000L,
                lastAttemptUptimeMs = 6_000L,
            ),
        )
    }
}
