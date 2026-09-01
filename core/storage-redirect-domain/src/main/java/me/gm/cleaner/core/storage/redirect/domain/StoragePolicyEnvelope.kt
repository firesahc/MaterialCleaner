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

    data class SpecificUser(
        val userId: Int,
    ) : StorageUserScope {
        init {
            require(userId >= 0) { "userId 不能为负数" }
        }
    }
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

/** 对规范可见目录根及其子树拒绝受控新访问。 */
data class DenyAllRule(
    val ruleId: RuleId,
    val scope: PackageStorageScope,
    val visiblePath: String,
) {
    init {
        require(visiblePath.isNotBlank()) { "禁止访问路径不能为空" }
    }
}

/** 仅抑制指定包的存储整理提示，不改变其存储访问能力。 */
data class PromptSuppression(
    val ruleId: RuleId,
    val packageName: String,
    val users: StorageUserScope,
) {
    init {
        require(packageName.isNotBlank()) { "提示抑制包名不能为空" }
        require(packageName == packageName.trim()) { "提示抑制包名不能包含首尾空白" }
    }
}

/** 使指定包不参与存储映射及相关运行时处理。 */
data class PackageExclusion(
    val ruleId: RuleId,
    val packageName: String,
    val users: StorageUserScope,
) {
    init {
        require(packageName.isNotBlank()) { "排除包名不能为空" }
        require(packageName == packageName.trim()) { "排除包名不能包含首尾空白" }
    }
}

/**
 * 旧 Wizard inaccessible 列表的未激活草稿。
 *
 * 本类型不能直接出现在 [StoragePolicyEnvelope.denyAllRules] 中；只有用户确认后，
 * 配置层才能创建拥有新 [RuleId] 的 [DenyAllRule]。
 */
data class LegacyWizardDraft(
    val draftId: String,
    val scope: PackageStorageScope,
    val inaccessiblePaths: List<String>,
) {
    init {
        require(draftId.isNotBlank()) { "Wizard 草稿标识不能为空" }
        require(draftId == draftId.trim()) { "Wizard 草稿标识不能包含首尾空白" }
        require(inaccessiblePaths.isNotEmpty()) { "Wizard 草稿路径不能为空" }
        require(inaccessiblePaths.all(String::isNotBlank)) { "Wizard 草稿不能包含空路径" }
    }
}

data class LegacyWizardMountPair(
    val source: String?,
    val target: String?,
)

/**
 * 正式 v4.0.0 的全局 Wizard 模板快照。
 *
 * q4 从未写入旧 Parcel，因此本模型刻意不提供 q4 Boolean。inaccessible 列表仅是等待
 * 用户确认的模板输入，不能直接生成 [DenyAllRule]。
 */
data class LegacyWizardTemplate(
    val templateId: String,
    val q1: Boolean,
    val q2: Boolean,
    val q3: Boolean,
    val q11: Boolean,
    val q12: Boolean,
    val accessiblePlaces: List<String?>,
    val mountRules: List<LegacyWizardMountPair>,
    val inaccessiblePlaces: List<String?>,
) {
    init {
        require(templateId.isNotBlank() && templateId == templateId.trim()) {
            "Wizard 模板标识无效"
        }
    }

    val q4: Boolean?
        get() = null
}

/** 无法证明等价的 legacy 输入；保留完整诊断供 UI 手工修复，不参与运行时激活。 */
data class LegacyPolicyQuarantine(
    val artifact: String,
    val sourceFingerprint: String,
    val code: String,
    val packageName: String?,
    val itemIndex: Int?,
    val details: List<String>,
) {
    init {
        require(artifact.isNotBlank() && code.isNotBlank()) { "legacy 隔离类型无效" }
        require(sourceFingerprint.isNotBlank()) { "legacy 隔离来源不能为空" }
        require(itemIndex == null || itemIndex >= 0) { "legacy 隔离索引不能为负数" }
        require(details.isNotEmpty() && details.none(String::isBlank)) {
            "legacy 隔离必须包含非空诊断"
        }
    }
}

enum class PolicyMigrationSource {
    LEGACY_CONFIG,
    LEGACY_WIZARD,
    CURRENT_POLICY,
}

/**
 * 配置迁移的可审计来源信息。
 *
 * [sourceFingerprint] 由迁移适配层计算，领域层只校验其可作为稳定引用；
 * [notices] 保留诸如旧只读范围扩大的用户可见说明。
 */
data class PolicyMigrationMetadata(
    val source: PolicyMigrationSource,
    val sourceSchemaVersion: Int?,
    val sourceFingerprint: String,
    val migratedAtEpochMillis: Long,
    val notices: List<String> = emptyList(),
) {
    init {
        require(sourceSchemaVersion == null || sourceSchemaVersion > 0) {
            "来源 schemaVersion 必须为正数"
        }
        require(sourceFingerprint.isNotBlank()) { "迁移来源指纹不能为空" }
        require(sourceFingerprint == sourceFingerprint.trim()) {
            "迁移来源指纹不能包含首尾空白"
        }
        require(migratedAtEpochMillis >= 0L) { "迁移时间不能为负数" }
        require(notices.all(String::isNotBlank)) { "迁移说明不能包含空内容" }
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
    val denyAllRules: List<DenyAllRule> = emptyList(),
    val promptSuppressions: List<PromptSuppression> = emptyList(),
    val packageExclusions: List<PackageExclusion> = emptyList(),
    val legacyWizardDrafts: List<LegacyWizardDraft> = emptyList(),
    val legacyWizardTemplate: LegacyWizardTemplate? = null,
    val legacyQuarantines: List<LegacyPolicyQuarantine> = emptyList(),
    val migrationMetadata: PolicyMigrationMetadata? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "StoragePolicyEnvelope 只接受 schemaVersion=$CURRENT_SCHEMA_VERSION"
        }
        val ruleIds = buildList {
            redirectPolicies.forEach { policy -> addAll(policy.rules.map(OrderedRedirectRule::ruleId)) }
            addAll(readOnlyRules.map(ReadOnlyRule::ruleId))
            addAll(denyAllRules.map(DenyAllRule::ruleId))
            addAll(promptSuppressions.map(PromptSuppression::ruleId))
            addAll(packageExclusions.map(PackageExclusion::ruleId))
        }
        require(ruleIds.size == ruleIds.distinct().size) {
            "同一策略 envelope 中的 ruleId 必须全局唯一"
        }
        val redirectScopes = redirectPolicies.map(OrderedRedirectPolicy::scope)
        require(redirectScopes.size == redirectScopes.distinct().size) {
            "同一包、用户范围和卷只能声明一个有序重定向序列"
        }
        val draftIds = legacyWizardDrafts.map(LegacyWizardDraft::draftId)
        require(draftIds.size == draftIds.distinct().size) {
            "同一策略 envelope 中的 Wizard 草稿标识必须唯一"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
