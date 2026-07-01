package me.gm.cleaner.server.orchestrator

/**
 * 单层状态报告。
 *
 * 由各层的生命周期管理者定期更新，供 [OrchestratedStatus] 聚合。
 *
 * @property id 层标识
 * @property state 当前状态
 * @property generation 状态代数（每次状态变更递增，用于跳过过期更新）
 * @property lastStartedAt 最后一次启动的时间戳（System.currentTimeMillis()，0 表示未曾启动）
 * @property lastHeartbeatAt 最后一次心跳的时间戳
 * @property lastErrorAt 最后一次出错的时间戳（0 表示无错误）
 * @property lastError 最后一次出错的描述信息
 * @property metrics 该层的度量指标（可读的 key-value 对，用于诊断展示）
 */
data class LayerReport(
    val id: LayerId,
    val state: LayerState,
    val generation: Long = 0L,
    val lastStartedAt: Long = 0L,
    val lastHeartbeatAt: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastError: String? = null,
    val metrics: Map<String, String> = emptyMap(),
) {
    companion object {
        /**
         * 创建未初始化状态的默认报告。
         */
        @JvmStatic
        fun uninitialized(id: LayerId): LayerReport = LayerReport(
            id = id,
            state = LayerState.UNINITIALIZED,
        )
    }
}
