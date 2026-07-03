package me.gm.cleaner.runtime.server.orchestrator

/**
 * 系统整体健康评估。
 *
 * 评估规则（由 [OrchestratedStatus] 计算）：
 * - [HEALTHY]：所有核心层（VFS）为 HEALTHY，兼容层处于非 CRITICAL 状态
 * - [DEGRADED]：VFS 仍为 HEALTHY，但至少一个兼容层（MediaProvider Hook 或 FUSE Native Hook）
 *   处于 UNAVAILABLE / RECOVERING / STALE 状态。核心存储重定向能力不受影响。
 * - [CRITICAL]：VFS 层不可用，存储重定向完全失效。
 */
enum class OverallHealth {
    /** 所有层健康 */
    HEALTHY,

    /** 降级运行，至少一个兼容层异常 */
    DEGRADED,

    /** 严重故障，核心层不可用 */
    CRITICAL,
}
