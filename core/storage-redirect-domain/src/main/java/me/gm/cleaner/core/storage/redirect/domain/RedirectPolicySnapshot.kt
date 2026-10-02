package me.gm.cleaner.core.storage.redirect.domain

/**
 * 存储重定向业务规则的权威快照。
 *
 * 这是整个重定向系统的策略真相源——不依赖 Binder、不依赖 UI、不依赖 Android Hook 类。
 * 所有三层拦截能力都从该快照推导出自己需要的子集：
 * - VFS Layer 查询 [RuntimeStoragePolicy] 决定是否 mount
 * - MediaProvider Java Hook Layer 查询 [RuntimeStoragePolicy] 修正 _data 路径
 * - FUSE Native Hook Layer 消费由 [RuntimeStoragePolicy] 推导出的 configured_mount_points
 *
 * 逻辑分拆但聚合发布：[storage] 与 [behavior] 生命周期不同，但 DataBus 仍以
 * 同一 generation/epoch 原子发布，JSON 字段名保持不变以兼容已发布快照。
 *
 * @property schemaVersion 快照结构版本（当前为 1）
 * @property generation 快照代数（递增，用于判断是否过期）
 * @property publisherEpoch 发布者本轮生命周期标识；发布者重启后允许 generation 从 1 重新开始
 * @property redirectRevision 重定向配置正文 revision，不承担运行时排序
 * @property readOnlyRevision 只读配置正文 revision，不承担运行时排序
 * @property createdAt 快照创建时间戳
 * @property storage 存储策略：重定向映射与只读路径
 * @property behavior 行为策略：名单、记录、提示与 upsert
 */
data class RedirectPolicySnapshot(
    val schemaVersion: Int = 1,
    val generation: Long = 0L,
    val publisherEpoch: String = "",
    val createdAt: Long = 0L,
    val publisher: String = "",
    /** 由配置门面计算的重定向内容 revision；不是运行时 generation。 */
    val redirectRevision: String = "",
    /** 由配置门面计算的只读内容 revision；不是运行时 generation。 */
    val readOnlyRevision: String = "",
    val storage: RuntimeStoragePolicy = RuntimeStoragePolicy(),
    val behavior: RuntimeBehaviorPolicy = RuntimeBehaviorPolicy(),
)

/**
 * 运行时存储策略：已投影、可直接消费的重定向映射与只读路径。
 *
 * @property redirectRules packageName → userId → 规则
 * @property readOnlyRules packageName → 只读路径列表
 */
data class RuntimeStoragePolicy(
    val redirectRules: Map<String, Map<Int, List<RedirectRule>>> = emptyMap(),
    val readOnlyRules: Map<String, List<String>> = emptyMap(),
)
