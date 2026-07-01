package me.gm.cleaner.server.orchestrator

/**
 * 系统编排状态快照。
 *
 * 聚合所有五层的 [LayerReport]，并计算整体健康评估。
 *
 * 健康评估规则：
 * - 若 VFS 层为 HEALTHY，其他层均不为 UNAVAILABLE → [OverallHealth.HEALTHY]
 * - 若 VFS 层为 HEALTHY，但至少一个兼容层为 UNAVAILABLE/RECOVERING/STALE → [OverallHealth.DEGRADED]
 * - 若 VFS 层为 UNAVAILABLE → [OverallHealth.CRITICAL]
 * - 若 VFS 为 DISABLED → [OverallHealth.CRITICAL]
 *
 * @property health 整体健康评估
 * @property vfs VFS 层报告
 * @property mediaProviderJavaHook MediaProvider Java Hook 层报告
 * @property fuseNativeHook FUSE Native Hook 层报告
 * @property dataBus DataBus 层报告
 * @property controlPlane 控制面报告
 */
data class OrchestratedStatus(
    val health: OverallHealth,
    val vfs: LayerReport,
    val mediaProviderJavaHook: LayerReport,
    val fuseNativeHook: LayerReport,
    val dataBus: LayerReport,
    val controlPlane: LayerReport,
) {
    companion object {
        /**
         * 根据各层报告计算整体健康状态。
         */
        @JvmStatic
        fun evaluate(
            vfs: LayerReport,
            mediaProviderJavaHook: LayerReport,
            fuseNativeHook: LayerReport,
            dataBus: LayerReport,
            controlPlane: LayerReport,
        ): OrchestratedStatus {
            val health = when {
                vfs.state == LayerState.UNAVAILABLE || vfs.state == LayerState.DISABLED ->
                    OverallHealth.CRITICAL
                vfs.state == LayerState.HEALTHY || vfs.state == LayerState.DEGRADED -> {
                    val compatLayersDegraded = listOf(
                        mediaProviderJavaHook,
                        fuseNativeHook,
                    ).any {
                        it.state in setOf(
                            LayerState.UNAVAILABLE,
                            LayerState.RECOVERING,
                            LayerState.STALE,
                        )
                    }
                    if (compatLayersDegraded) OverallHealth.DEGRADED
                    else OverallHealth.HEALTHY
                }
                // VFS 尚未就绪（STARTING/UNINITIALIZED/RECOVERING/STALE）
                else -> OverallHealth.DEGRADED
            }

            return OrchestratedStatus(
                health = health,
                vfs = vfs,
                mediaProviderJavaHook = mediaProviderJavaHook,
                fuseNativeHook = fuseNativeHook,
                dataBus = dataBus,
                controlPlane = controlPlane,
            )
        }

        /**
         * 创建全部未初始化的默认状态。
         */
        @JvmStatic
        fun uninitialized(): OrchestratedStatus = evaluate(
            vfs = LayerReport.uninitialized(LayerId.VFS),
            mediaProviderJavaHook = LayerReport.uninitialized(LayerId.MEDIA_PROVIDER_JAVA_HOOK),
            fuseNativeHook = LayerReport.uninitialized(LayerId.FUSE_NATIVE_HOOK),
            dataBus = LayerReport.uninitialized(LayerId.DATA_BUS),
            controlPlane = LayerReport.uninitialized(LayerId.CONTROL_PLANE),
        )
    }
}
