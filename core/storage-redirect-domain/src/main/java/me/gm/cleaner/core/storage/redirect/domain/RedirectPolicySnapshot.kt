package me.gm.cleaner.core.storage.redirect.domain

/**
 * 存储重定向业务规则的权威快照。
 *
 * 这是整个重定向系统的策略真相源——不依赖 Binder、不依赖 UI、不依赖 Android Hook 类。
 * 所有三层拦截能力都从该快照推导出自己需要的子集：
 * - VFS Layer 查询 [storageRedirectRules] 决定是否 mount
 * - MediaProvider Java Hook Layer 查询 [storageRedirectRules] 修正 _data 路径
 * - FUSE Native Hook Layer 消费由 [storageRedirectRules] 推导出的 configured_mount_points
 *
 * @property schemaVersion 快照结构版本（当前为 1）
 * @property generation 快照代数（递增，用于判断是否过期）
 * @property publisherEpoch 发布者本轮生命周期标识；发布者重启后允许 generation 从 1 重新开始
 * @property redirectRevision 重定向配置正文 revision，不承担运行时排序
 * @property readOnlyRevision 只读配置正文 revision，不承担运行时排序
 * @property createdAt 快照创建时间戳
 * @property storageRedirectRules packageName → userId → List<RedirectRule>
 * @property readOnlyRules packageName → List<String>（只读路径列表）
 * @property denylist 黑名单包名集合（不参与重定向的包）
 * @property recordSharedStorage 是否记录共享存储事件
 * @property recordExternalAppSpecificStorage 是否处理外部应用专属存储
 * @property aggressivelyPromptForReadingMediaFiles 是否激进提示媒体文件读取
 * @property upsertRecords 是否 upsert 文件系统记录
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
    val storageRedirectRules: Map<String, Map<Int, List<RedirectRule>>> = emptyMap(),
    val readOnlyRules: Map<String, List<String>> = emptyMap(),
    val denylist: Set<String> = emptySet(),
    val recordSharedStorage: Boolean = false,
    val recordExternalAppSpecificStorage: Boolean = false,
    val aggressivelyPromptForReadingMediaFiles: Boolean = false,
    val upsertRecords: Boolean = true,
)
