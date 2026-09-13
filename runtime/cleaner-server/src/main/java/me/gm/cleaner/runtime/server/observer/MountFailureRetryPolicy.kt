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

    /**
     * FUSE bypass 优化项失败时的降级路由（Issue #3）。
     *
     * bypass 只是 FUSE 下私有目录拦截优化，用户规则可在干净 baseline 上独立成立；
     * 因此 bypass 失败不应记失败或调度 pid 重试，而应由调用方以 fuseBypass=false
     * 再试一次。任何 errno 下均可降级，唯独 namespace 污染/目标已终止时除外。
     */
    fun shouldFallbackWithoutBypass(
        stage: String,
        namespaceDirty: Boolean,
        targetTerminated: Boolean,
        fuseBypassAttempted: Boolean,
    ): Boolean = fuseBypassAttempted &&
        stage in BYPASS_DEGRADABLE_STAGES &&
        !namespaceDirty && !targetTerminated
}

/** FUSE bypass 四阶段集合：优化项失败可降级，不应 gate 用户规则。 */
private val BYPASS_DEGRADABLE_STAGES = setOf(
    "fuse_bypass_data_source",
    "fuse_bypass_obb_source",
    "fuse_bypass_data_target",
    "fuse_bypass_obb_target",
)

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
