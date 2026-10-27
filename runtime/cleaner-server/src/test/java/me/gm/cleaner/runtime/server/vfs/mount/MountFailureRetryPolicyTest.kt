package me.gm.cleaner.runtime.server.vfs.mount

import me.gm.cleaner.core.common.err.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountFailureRetryPolicyTest {
    @Test
    fun `worker安全超时允许退避重试`() {
        val disposition = MountFailureRetryPolicy.classify(
            stage = "child_result_timeout",
            errno = 110,
            namespaceDirty = false,
            targetTerminated = false,
        )

        assertTrue(disposition.retryable)
        assertFalse(disposition.forceStopTargetPackage)
    }

    @Test
    fun `权限和参数错误不会形成恢复风暴`() {
        assertFalse(MountFailureRetryPolicy.classify("setns", 1, false, false).retryable)
        assertFalse(MountFailureRetryPolicy.classify("invalid_target", 22, false, false).retryable)
    }

    @Test
    fun `冷却内不重投冷却后重投`() {
        assertFalse(
            MountFailureRetryPolicy.shouldRequeueFailedPackage(
                nowMs = 100_000L,
                lastFailedAtMs = 90_000L,
                failedPlanHash = 1234,
                currentPlanHash = 1234,
                cooldownMs = 60_000L,
            ),
        )
        assertTrue(
            MountFailureRetryPolicy.shouldRequeueFailedPackage(
                nowMs = 200_000L,
                lastFailedAtMs = 90_000L,
                failedPlanHash = 1234,
                currentPlanHash = 1234,
                cooldownMs = 60_000L,
            ),
        )
    }

    @Test
    fun `计划内容变化旧条目作废`() {
        assertFalse(
            MountFailureRetryPolicy.shouldRequeueFailedPackage(
                nowMs = 1_000_000L,
                lastFailedAtMs = 100_000L,
                failedPlanHash = 1234,
                currentPlanHash = 5678,
            ),
        )
    }

    @Test
    fun `计划消失不重投空事务`() {
        assertFalse(
            MountFailureRetryPolicy.shouldRequeueFailedPackage(
                nowMs = 1_000_000L,
                lastFailedAtMs = 100_000L,
                failedPlanHash = 1234,
                currentPlanHash = null,
            ),
        )
    }

    @Test
    fun `零时刻不重投`() {
        assertFalse(
            MountFailureRetryPolicy.shouldRequeueFailedPackage(
                nowMs = 1_000_000L,
                lastFailedAtMs = 0L,
                failedPlanHash = 1234,
                currentPlanHash = 1234,
            ),
        )
    }

    @Test
    fun `脏namespace且native未终止目标时要求forceStop`() {
        val disposition = MountFailureRetryPolicy.classify(
            stage = "namespace_rollback_failed",
            errno = 5,
            namespaceDirty = true,
            targetTerminated = false,
        )

        assertFalse(disposition.retryable)
        assertTrue(disposition.forceStopTargetPackage)
    }

    @Test
    fun `native已终止原目标时不重复forceStop`() {
        val disposition = MountFailureRetryPolicy.classify(
            stage = "namespace_transaction_timeout",
            errno = 110,
            namespaceDirty = true,
            targetTerminated = true,
        )

        assertFalse(disposition.retryable)
        assertFalse(disposition.forceStopTargetPackage)
    }

    @Test
    fun `身份复核失败属于永久性失败`() {
        // target_identity 意味着 PID 已被复用或目标消失，重试必然指向错误进程。
        val disposition = MountFailureRetryPolicy.classify(
            stage = "target_identity",
            errno = 3, // ESRCH
            namespaceDirty = false,
            targetTerminated = false,
        )
        assertFalse(disposition.retryable)
        assertFalse(disposition.forceStopTargetPackage)
        assertTrue(MountFailureRetryPolicy.isExpectedTargetDisappearance("target_identity", false))
    }

    @Test
    fun `目标在等待zygote期间消失不计作挂载失败`() {
        val disposition = MountFailureRetryPolicy.classify(
            stage = "target_gone",
            errno = 2, // ENOENT
            namespaceDirty = false,
            targetTerminated = false,
        )

        assertTrue(MountFailureRetryPolicy.isExpectedTargetDisappearance("target_gone", false))
        assertFalse(disposition.retryable)
        assertFalse(disposition.forceStopTargetPackage)
    }

    @Test
    fun `目标消失但namespace已脏仍执行安全处置`() {
        assertFalse(MountFailureRetryPolicy.isExpectedTargetDisappearance("target_gone", true))
        val disposition = MountFailureRetryPolicy.classify(
            stage = "target_gone",
            errno = 2,
            namespaceDirty = true,
            targetTerminated = false,
        )

        assertFalse(disposition.retryable)
        assertTrue(disposition.forceStopTargetPackage)
    }

    @Test
    fun `zygote等待超时和读取错误映射到不同错误码`() {
        assertEquals(ErrorCodes.MOUNT_ZYGOTE_WAIT_TIMEOUT, mountErrorCodeForStage("wait_zygote"))
        assertEquals(ErrorCodes.MOUNT_INTERNAL_FAILED, mountErrorCodeForStage("wait_zygote_error"))
        assertTrue(
            MountFailureRetryPolicy.classify("wait_zygote_error", 5, false, false).retryable,
        )
        assertFalse(
            MountFailureRetryPolicy.classify("wait_zygote_error", 13, false, false).retryable,
        )
    }

    @Test
    fun `规则挂载的临时性错误可重试`() {
        // EBUSY(16)/EAGAIN(11) 等瞬时错误在干净回滚后允许退避重试。
        assertTrue(
            MountFailureRetryPolicy.classify("mount_rule", 16, false, false).retryable,
        )
    }

    @Test
    fun `bypass优化项失败走降级而非失败重试`() {
        // Issue #3：fuse_bypass_data_source ENOTCONN(107) 干净失败时，
        // 调用方应以 fuseBypass=false 再试一次，而不是记失败调度 pid 重试。
        assertTrue(
            MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                stage = "fuse_bypass_data_source",
                namespaceDirty = false,
                targetTerminated = false,
                fuseBypassAttempted = true,
            ),
        )
        assertTrue(
            MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                stage = "fuse_bypass_obb_target",
                namespaceDirty = false,
                targetTerminated = false,
                fuseBypassAttempted = true,
            ),
        )
    }

    @Test
    fun `bypass降级仅在干净且确实尝试过bypass时触发`() {
        // 脏 namespace 照旧走污染处置，不降级。
        assertFalse(
            MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                stage = "fuse_bypass_data_source",
                namespaceDirty = true,
                targetTerminated = false,
                fuseBypassAttempted = true,
            ),
        )
        // 未尝试 bypass（如 sdcardfs 设备或空规则路径）不触发。
        assertFalse(
            MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                stage = "fuse_bypass_data_source",
                namespaceDirty = false,
                targetTerminated = false,
                fuseBypassAttempted = false,
            ),
        )
        // 用户规则失败仍走原重试路径，不降级。
        assertFalse(
            MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                stage = "mount_rule",
                namespaceDirty = false,
                targetTerminated = false,
                fuseBypassAttempted = true,
            ),
        )
    }

    @Test
    fun `重试目标必须同时匹配pid uid和包名`() {
        assertTrue(
            MountRetryTargetPolicy.matches(
                "pkg.expected", 123, 10_123,
                123, 10_123, arrayOf("pkg.expected", "pkg.shared"),
            ),
        )
        assertFalse(
            MountRetryTargetPolicy.matches(
                "pkg.expected", 123, 10_123,
                123, 10_124, arrayOf("pkg.expected"),
            ),
        )
        assertFalse(
            MountRetryTargetPolicy.matches(
                "pkg.expected", 123, 10_123,
                123, 10_123, arrayOf("pkg.reused"),
            ),
        )
        assertFalse(
            MountRetryTargetPolicy.matches(
                "pkg.expected", 123, 10_123,
                124, 10_123, arrayOf("pkg.expected"),
            ),
        )
    }
}
