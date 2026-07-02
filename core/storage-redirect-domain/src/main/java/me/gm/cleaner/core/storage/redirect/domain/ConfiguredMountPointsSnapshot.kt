package me.gm.cleaner.core.storage.redirect.domain

/**
 * 由 [RedirectPolicySnapshot] 推导出的配置挂载点快照。
 *
 * 该快照被 FUSE Native Hook Layer 消费——native 层只关心"哪些路径已被挂载"，
 * 不需要完整业务规则。这消除了 native Hook 对 Java Binder 和完整策略的依赖。
 *
 * @property schemaVersion 快照结构版本
 * @property generation 策略代数（与来源 RedirectPolicySnapshot.generation 一致）
 * @property createdAt 快照创建时间戳
 * @property points 所有配置的目标挂载点路径列表
 */
data class ConfiguredMountPointsSnapshot(
    val schemaVersion: Int = 1,
    val generation: Long = 0L,
    val createdAt: Long = 0L,
    val points: List<String> = emptyList(),
)
