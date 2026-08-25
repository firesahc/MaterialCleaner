package me.gm.cleaner.runtime.server.observer

/**
 * 挂载失败的处置分类：区分可重试失败、永久失败与 namespace 污染。
 *
 * 判定依据来自 native 事务的结构化结果（stage/errno/namespaceDirty/targetTerminated），
 * 纯函数无副作用，便于单测穷举。
 */
internal data class MountFailureDisposition(
    val retryable: Boolean,
    val forceStopTargetPackage: Boolean,
)

internal object MountFailureRetryPolicy {
    /** 这些 stage 表示事务本身或环境已不可信，重试必然复现。 */
    private val permanentStages = setOf(
        "invalid_args",
        "invalid_source",
        "invalid_target",
        "target_identity",
        "namespace_transaction_timeout",
        "namespace_rollback_failed",
        "baseline_recovery_failed",
    )

    /** 权限/不支持类 errno：重试无法改变结果。 */
    private val permanentErrnos = setOf(
        1,  // EPERM
        13, // EACCES
        19, // ENODEV
        22, // EINVAL
        38, // ENOSYS
        95, // EOPNOTSUPP
    )

    fun classify(
        stage: String,
        errno: Int,
        namespaceDirty: Boolean,
        targetTerminated: Boolean,
    ): MountFailureDisposition {
        if (namespaceDirty) {
            // namespace 已污染且无法确认恢复：目标应用可能运行在脏视图上，
            // 必须安全停止；若 native 已终止过则无需重复处置。
            return MountFailureDisposition(
                retryable = false,
                forceStopTargetPackage = !targetTerminated,
            )
        }
        val retryable = stage !in permanentStages && errno !in permanentErrnos
        return MountFailureDisposition(
            retryable = retryable,
            forceStopTargetPackage = false,
        )
    }
}

/** 重试前校验目标进程仍是事务登记时的那个：PID 复用后不得对旧 PID 重试。 */
internal object MountRetryTargetPolicy {
    fun matches(
        expectedPackageName: String,
        expectedPid: Int,
        expectedUid: Int,
        observedPid: Int,
        observedUid: Int,
        observedPackages: Array<String>?,
    ): Boolean = observedPid == expectedPid && observedUid == expectedUid &&
            observedPackages?.contains(expectedPackageName) == true
}
