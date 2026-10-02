package me.gm.cleaner.core.storage.redirect.domain

/**
 * 配置中跨修订保持稳定的规则标识。
 *
 * 标识由配置仓库创建；领域层不依赖 UUID、JSON 或具体持久化格式。
 */
data class RuleId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ruleId 不能为空" }
        require(value == value.trim()) { "ruleId 不能包含首尾空白" }
    }
}

/** 配置应用于全部 Android 用户，包含保存配置后才创建的用户。 */
sealed interface StorageUserScope {
    data object AllUsers : StorageUserScope
}

/** 当前 schema 只声明主外部存储卷，不把保存时的具体挂载路径固化进配置。 */
enum class StorageVolumeScope {
    PRIMARY_EXTERNAL,
}

/** 一组路径策略的包、用户和卷作用域。 */
data class PackageStorageScope(
    val packageName: String,
    val users: StorageUserScope,
    val volume: StorageVolumeScope = StorageVolumeScope.PRIMARY_EXTERNAL,
) {
    init {
        require(packageName.isNotBlank()) { "包名不能为空" }
        require(packageName == packageName.trim()) { "包名不能包含首尾空白" }
    }
}

/**
 * 有序重定向规则的类型。
 *
 * [PRESERVE] 是旧恒等 Pair 的完整语义，不是可以删除的 no-op。
 */
enum class RedirectRuleType {
    MAP,
    PRESERVE,
}

/**
 * 一条有序重定向规则。
 *
 * [source]、[target] 和 [orderIndex] 均保存配置输入，领域层不做规范化、去重或重排。
 */
data class OrderedRedirectRule(
    val ruleId: RuleId,
    val type: RedirectRuleType,
    val source: String,
    val target: String,
    val orderIndex: Int,
) {
    init {
        require(source.isNotBlank()) { "重定向 source 不能为空" }
        require(target.isNotBlank()) { "重定向 target 不能为空" }
        require(orderIndex >= 0) { "重定向规则索引不能为负数" }
        when (type) {
            RedirectRuleType.MAP ->
                require(source != target) { "MAP 的 source 与 target 必须不同" }

            RedirectRuleType.PRESERVE ->
                require(source == target) { "PRESERVE 必须完整保存恒等 Pair" }
        }
    }
}

/**
 * 同一包、用户范围和卷上的完整有序重定向序列。
 *
 * 规则列表本身就是解释顺序；索引必须连续，避免持久化层静默丢项或重排。
 */
data class OrderedRedirectPolicy(
    val scope: PackageStorageScope,
    val rules: List<OrderedRedirectRule>,
) {
    init {
        require(rules.isNotEmpty()) { "有序重定向策略不能为空" }
        require(rules.map(OrderedRedirectRule::orderIndex) == rules.indices.toList()) {
            "有序重定向规则索引必须从 0 开始连续且与声明顺序一致"
        }
    }
}

/** 对规范可见路径子树实施递归只读保护。 */
data class ReadOnlyRule(
    val ruleId: RuleId,
    val scope: PackageStorageScope,
    val visiblePath: String,
) {
    init {
        require(visiblePath.isNotBlank()) { "只读路径不能为空" }
    }
}

/**
 * 首版完整类型化存储策略正文。
 *
 * 所有激活规则共享一个 ruleId 命名空间，避免跨规则族引用产生歧义。重复的规则内容合法，
 * 但其 ruleId 必须不同；有序重定向的重复项和顺序会被原样保留。
 */
data class StoragePolicyEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val redirectPolicies: List<OrderedRedirectPolicy> = emptyList(),
    val readOnlyRules: List<ReadOnlyRule> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "StoragePolicyEnvelope 只接受 schemaVersion=$CURRENT_SCHEMA_VERSION"
        }
        val ruleIds = buildList {
            redirectPolicies.forEach { policy -> addAll(policy.rules.map(OrderedRedirectRule::ruleId)) }
            addAll(readOnlyRules.map(ReadOnlyRule::ruleId))
        }
        require(ruleIds.size == ruleIds.distinct().size) {
            "同一策略 envelope 中的 ruleId 必须全局唯一"
        }
        val redirectScopes = redirectPolicies.map(OrderedRedirectPolicy::scope)
        require(redirectScopes.size == redirectScopes.distinct().size) {
            "同一包、用户范围和卷只能声明一个有序重定向序列"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
