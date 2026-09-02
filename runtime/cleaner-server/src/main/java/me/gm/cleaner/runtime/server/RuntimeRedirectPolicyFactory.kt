package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.config.ConfigSourceHealth
import me.gm.cleaner.core.config.ConfiguredPolicySnapshot
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * 运行时重定向策略工厂。
 *
 * 从 [ConfiguredPolicySnapshot]（配置门面的一次性读取结果）生成
 * [RedirectPolicySnapshot]。
 * 使用单调递增的 [generationCounter] 确保代数独立性——不依赖系统时钟。
 */
object RuntimeRedirectPolicyFactory {
    private const val PUBLISHER_IDENTITY = "RuntimeRedirectPolicyFactory"
    val publisherEpoch: String = UUID.randomUUID().toString()

    /** 单调递增的策略代数计数器 */
    private val generationCounter = AtomicLong(0)

    /**
     * 构建当前策略快照。
     * 每次调用生成一个新的 generation。
     */
    fun build(userIds: List<Int>): RedirectPolicySnapshot {
        check(ConfiguredPolicyStoreProvider.isInitialized()) {
            "ConfiguredPolicyStore 尚未初始化"
        }
        return build(ConfiguredPolicyStoreProvider.instance.readSnapshot(), userIds)
    }

    /** 使用一次读取的配置组合构建运行时快照，避免同一发布批次重复读取旧文件。 */
    fun build(
        configured: ConfiguredPolicySnapshot,
        userIds: List<Int>,
    ): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val gen = generationCounter.incrementAndGet()
        val rules = LinkedHashMap<String, Map<Int, List<RedirectRule>>>()
        val preferencesReady = ServicePreferences.isInitialized()
        val configuredRedirect = configured.redirect
        val configuredReadOnly = configured.readOnly
        require(configuredRedirect.health != ConfigSourceHealth.CORRUPT) {
            "重定向配置损坏: ${configuredRedirect.diagnostics.joinToString()}"
        }
        require(configuredReadOnly.health != ConfigSourceHealth.CORRUPT) {
            "只读配置损坏: ${configuredReadOnly.diagnostics.joinToString()}"
        }
        val readOnlyRules = buildReadOnlyRules(configuredReadOnly.envelope)

        for (policy in configuredRedirect.envelope.redirectPolicies) {
            val packageName = policy.scope.packageName
            val userRules = LinkedHashMap<Int, List<RedirectRule>>()
            for (userId in userIds) {
                val zipped = policy.rules.map { it.source to it.target }.map { (source, target) ->
                    RuntimeFileUtils.getPathAsUser(source, userId) to
                        RuntimeFileUtils.getPathAsUser(target, userId)
                }
                if (zipped.isNotEmpty()) {
                    val validRules = zipped.mapNotNull { (source, target) ->
                        buildRedirectRuleOrNull(packageName, userId, source, target)
                    }
                    if (validRules.isNotEmpty()) {
                        userRules[userId] = validRules
                    }
                }
            }
            if (userRules.isNotEmpty()) {
                rules[packageName] = userRules
            }
        }

        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = gen,
            publisherEpoch = publisherEpoch,
            createdAt = now,
            publisher = PUBLISHER_IDENTITY,
            redirectRevision = configuredRedirect.revision,
            readOnlyRevision = configuredReadOnly.revision,
            storageRedirectRules = rules,
            readOnlyRules = readOnlyRules,
            denylist = if (preferencesReady) ServicePreferences.denylist.toSet() else emptySet(),
            recordSharedStorage = if (preferencesReady) ServicePreferences.recordSharedStorage else false,
            recordExternalAppSpecificStorage = if (preferencesReady) {
                ServicePreferences.recordExternalAppSpecificStorage
            } else {
                false
            },
            aggressivelyPromptForReadingMediaFiles = if (preferencesReady) {
                ServicePreferences.aggressivelyPromptForReadingMediaFiles
            } else {
                false
            },
            upsertRecords = if (preferencesReady) ServicePreferences.upsert else true,
        )
    }

    /**
     * 构建停止态策略快照。
     *
     * 停止服务是用户主动暂停所有运行能力，不应继续向 Hook/VFS 暴露旧规则。
     */
    fun buildStopped(): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val gen = generationCounter.incrementAndGet()
        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = gen,
            publisherEpoch = publisherEpoch,
            createdAt = now,
            publisher = PUBLISHER_IDENTITY,
            storageRedirectRules = emptyMap(),
            readOnlyRules = emptyMap(),
            denylist = emptySet(),
            recordSharedStorage = false,
            recordExternalAppSpecificStorage = false,
            aggressivelyPromptForReadingMediaFiles = false,
            upsertRecords = false,
        )
    }

    private fun buildReadOnlyRules(
        envelope: StoragePolicyEnvelope,
    ): Map<String, List<String>> {
        val rules = LinkedHashMap<String, List<String>>()
        for ((packageName, paths) in envelope.readOnlyRules.groupBy {
            it.scope.packageName
        }.mapValues { (_, values) -> values.map { it.visiblePath } }) {
            val validPaths = paths.mapNotNull { path ->
                val normalized = validateExternalStoragePath(path)?.let {
                    RuntimeFileUtils.getPathAsUser(it, 0)
                }
                if (normalized == null) {
                    Log.w(
                        "MC_REDIRECT",
                        "[RuntimeRedirectPolicyFactory] drop invalid read-only rule " +
                                "pkg=$packageName path=$path"
                    )
                }
                normalized
            }.distinct()
            if (validPaths.isNotEmpty()) {
                rules[packageName] = validPaths
            }
        }
        return rules
    }

    private fun buildRedirectRuleOrNull(
        packageName: String,
        userId: Int,
        source: String,
        target: String,
    ): RedirectRule? {
        val normalizedSource = validateExternalStoragePath(source)
        val normalizedTarget = validateExternalStoragePath(target)
        if (normalizedSource == null || normalizedTarget == null) {
            Log.w(
                "MC_REDIRECT",
                "[RuntimeRedirectPolicyFactory] drop invalid rule pkg=$packageName " +
                        "user=$userId source=$source target=$target"
            )
            return null
        }
        return RedirectRule(source = normalizedSource, target = normalizedTarget)
    }

    /** 配置门面已经完成词法规范化；这里仅验证运行时卷边界。 */
    private fun validateExternalStoragePath(path: String): String? {
        if (path.isEmpty() || '\u0000' in path || !path.startsWith("/")) {
            return null
        }
        return path.takeIf {
            RuntimeFileUtils.childOf(RuntimeFileUtils.externalStorageDirParent, it)
        }
    }

}
