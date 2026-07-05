package me.gm.cleaner.runtime.server.orchestrator

/**
 * 层运行状态。
 *
 * 状态机语义：
 * ```
 * UNINITIALIZED → STARTING → HEALTHY
 *                            ├→ DEGRADED（部分能力丧失，核心仍可用）
 *                            ├→ UNAVAILABLE（完全不可用）
 *                            └→ DISABLED（主动关闭）
 *
 * HEALTHY / DEGRADED → STALE（长时间无心跳 / 快照过期）
 *
 * UNAVAILABLE → RECOVERING（重连/重建中）→ HEALTHY / DEGRADED
 *
 * 任意状态 → DISABLED（通过配置显式关闭）
 * ```
 *
 * 降级规则：
 * - [DEGRADED]：该层部分功能不可用，但核心能力仍可对外服务。
 *   例如 MediaProvider Hook Binder 断开但本地缓存仍有效。
 * - [STALE]：该层仍在运行，但状态数据（心跳/快照）超过 TTL。
 *   例如 configured_mount_points 超过 60s 未刷新。
 * - [RECOVERING]：该层正在重建连接或重新初始化，当前不对外服务。
 */
enum class LayerState {
    /** 尚未初始化 */
    UNINITIALIZED,

    /** 正在启动 */
    STARTING,

    /** 完全健康，所有能力可用 */
    HEALTHY,

    /** 降级运行，部分能力丧失但核心可用 */
    DEGRADED,

    /** 状态数据过期，运行中但数据可能不是最新的 */
    STALE,

    /** 完全不可用 */
    UNAVAILABLE,

    /** 正在恢复中 */
    RECOVERING,

    /** 通过配置显式禁用 */
    DISABLED,
}
