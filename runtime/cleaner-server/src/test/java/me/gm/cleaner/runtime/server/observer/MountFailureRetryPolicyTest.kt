package me.gm.cleaner.runtime.server.observer

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
    }

    @Test
    fun `规则挂载的临时性错误可重试`() {
        // EBUSY(16)/EAGAIN(11) 等瞬时错误在干净回滚后允许退避重试。
        assertTrue(
            MountFailureRetryPolicy.classify("mount_rule", 16, false, false).retryable,
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
