package me.gm.cleaner.core.storage.redirect.databus

/**
 * DataBus 协议常量分区。
 *
 * 承载跨进程共享的持久化事实命名协议（快照 / 信号 / 事件队列 / Lease 分类），
 * 以及与协议强绑定的数据载体与健康口径。
 *
 * 运行期实现（目录准备、原子写、游标、计数器、校验实现）仍保留在 [DataBus]。
 * 现有调用方已迁移至本对象；新代码请直接引用本对象，不再经 [DataBus] 转发。
 */
object DataBusProtocol {
    // ── 快照文件名 ──
    const val SNAPSHOT_REDIRECT_POLICY = "redirect_policy.json"
    const val SNAPSHOT_READ_ONLY = "read_only.json"
    const val SNAPSHOT_CONFIGURED_MOUNT_POINTS = "configured_mount_points.json"
    const val SNAPSHOT_PLATFORM_CAPABILITIES = "platform_capabilities.json"
    const val SNAPSHOT_ORCHESTRATED_STATUS = "orchestrated_status.json"
    const val SNAPSHOT_NATIVE_HOOK_STATUS = "native_hook_status.json"

    // ── 信号文件名 ──
    const val SIGNAL_REDIRECT_POLICY_CHANGED = "redirect_policy_changed"
    const val SIGNAL_READ_ONLY_CHANGED = "read_only_changed"
    const val SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED = "configured_mount_points_changed"
    const val SIGNAL_PLATFORM_CAPABILITIES_CHANGED = "platform_capabilities_changed"
    const val SIGNAL_NATIVE_HOOK_STATUS_CHANGED = "native_hook_status_changed"
    const val SIGNAL_FILESYSTEM_EVENTS_CHANGED = "filesystem_events_changed"
    const val SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED = "redirect_notice_events_changed"
    const val SIGNAL_QUERY_SESSION_LEASES_CHANGED = "query_session_leases_changed"

    // ── 事件子目录 ──
    const val EVENT_FILESYSTEM = "filesystem"
    const val EVENT_REDIRECT_NOTICE = "redirect_notice"
    const val EVENT_MEDIA_INSERT_AUDIT = "media_insert_audit"

    // ── Lease 子目录 ──
    const val LEASE_QUERY_SESSIONS = "query_sessions"

    // ── 协议白名单（仅模块内可见，不扩大为 public）──
    internal val validSnapshotNames = setOf(
        SNAPSHOT_REDIRECT_POLICY,
        SNAPSHOT_READ_ONLY,
        SNAPSHOT_CONFIGURED_MOUNT_POINTS,
        SNAPSHOT_PLATFORM_CAPABILITIES,
        SNAPSHOT_ORCHESTRATED_STATUS,
        SNAPSHOT_NATIVE_HOOK_STATUS,
    )
    internal val validSignalNames = setOf(
        SIGNAL_REDIRECT_POLICY_CHANGED,
        SIGNAL_READ_ONLY_CHANGED,
        SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED,
        SIGNAL_PLATFORM_CAPABILITIES_CHANGED,
        SIGNAL_NATIVE_HOOK_STATUS_CHANGED,
        SIGNAL_FILESYSTEM_EVENTS_CHANGED,
        SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED,
        SIGNAL_QUERY_SESSION_LEASES_CHANGED,
    )
    internal val validEventQueues = setOf(
        EVENT_FILESYSTEM,
        EVENT_REDIRECT_NOTICE,
        EVENT_MEDIA_INSERT_AUDIT,
    )
    internal val validLeaseCategories = setOf(
        LEASE_QUERY_SESSIONS,
    )

    // ── 事件文件名正则（模块内可见，供 DataBus 解析序号）──
    internal val EVENT_FILE_NAME_PATTERN = Regex("^(\\d{20})-\\d+-\\d+-[0-9a-fA-F]{4}\\.json$")

    data class SnapshotHealth(
        val name: String,
        val exists: Boolean,
        val validJson: Boolean,
        val error: String? = null,
    )

    data class HealthReport(
        val initialized: Boolean,
        val missingDirectories: List<String>,
        val permissionIssues: List<String>,
        val snapshots: List<SnapshotHealth>,
        /**
         * 物理存在的源事件文件数（含已消费）。
         * 用途是存储残留，不是积压——不得用它驱动 backlog 告警，
         * 否则历史文件会让告警长期误报。
         */
        val eventQueueCounts: Map<String, Int>,
        val leaseCounts: Map<String, Int>,
        /**
         * 待处理事件数（`name > cursor` 的有效事件）。
         * 唯一驱动积压告警的口径；与消费端 `readEventFiles`
         * 用同一筛选规则（命名格式 + 常规文件 + 同字典序边界）。
         */
        val pendingEventCounts: Map<String, Int> = emptyMap(),
        /** 隔离目录中的证据文件数（毒丸增长监控）。 */
        val quarantineCounts: Map<String, Int> = emptyMap(),
        /**
         * 各队列游标可信状态。map 中缺失 == 状态未知，**不得当作 OK**；
         * UNREADABLE 的队列，其 pending 计数不可信，不得作为积压判断依据。
         */
        val cursorReadStates: Map<String, CursorRead> = emptyMap(),
    ) {
        fun hasSnapshot(name: String): Boolean =
            snapshots.any { it.name == name && it.exists && it.validJson }

        val criticalSnapshotsReady: Boolean
            get() = hasSnapshot(SNAPSHOT_REDIRECT_POLICY) &&
                    hasSnapshot(SNAPSHOT_READ_ONLY) &&
                    hasSnapshot(SNAPSHOT_CONFIGURED_MOUNT_POINTS)

        val healthy: Boolean
            get() = initialized &&
                    missingDirectories.isEmpty() &&
                    permissionIssues.isEmpty() &&
                    criticalSnapshotsReady
    }

    data class EventFile(
        val name: String,
        val content: String,
    )

    /**
     * 持久化游标的可信读取状态。
     *
     * ABSENT：确定不存在（新队列，尚未消费过）。
     * OK：成功读到非空游标，可作为删除/统计水位。
     * UNREADABLE：路径存在但无法确认内容（软链/目录/读取异常/内容空白），
     *   此时不得把游标当作可信空值使用。
     */
    enum class CursorRead { ABSENT, OK, UNREADABLE }

    /** 清理结果：删除授权状态必须原样交还调用方，不能压成布尔值。 */
    data class PruneResult(
        val scanned: Int,
        val deleted: Int,
        val failed: Int,
        /** 源事件清理返回 ABSENT/OK/UNREADABLE；隔离目录清理不依赖游标，返回 null。 */
        val cursorRead: CursorRead?,
    ) {
        companion object {
            /** 参数非法或队列名校验失败：未做任何事，也未获得任何删除授权。 */
            fun rejected(): PruneResult = PruneResult(0, 0, 0, null)
        }
    }

    /**
     * 恢复总账区分式读取结果（公开契约，跨模块可见）。
     *
     * Absent：路径确定不存在（新机/已清除），可按全新处理；
     * Ok：常规文件且读取成功；
     * Corrupted：存在但不可确认（非常规文件/读取异常），
     *   调用方不得按全新处理，必须进保守恢复态。
     */
    sealed interface RecoveryLedgerRead {
        data object Absent : RecoveryLedgerRead
        data class Ok(val json: String) : RecoveryLedgerRead
        data class Corrupted(val reason: String) : RecoveryLedgerRead
    }

    // ── 快照清单（模块内可见，供健康检查遍历）──
    internal fun snapshotNames(): List<String> = listOf(
        SNAPSHOT_REDIRECT_POLICY,
        SNAPSHOT_READ_ONLY,
        SNAPSHOT_CONFIGURED_MOUNT_POINTS,
        SNAPSHOT_PLATFORM_CAPABILITIES,
        SNAPSHOT_ORCHESTRATED_STATUS,
        SNAPSHOT_NATIVE_HOOK_STATUS,
    )
}
