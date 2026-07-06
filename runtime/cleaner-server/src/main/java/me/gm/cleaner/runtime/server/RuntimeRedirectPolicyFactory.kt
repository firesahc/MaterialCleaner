package me.gm.cleaner.runtime.server

import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * 运行时重定向策略工厂。
 *
 * 从 ServicePreferences（配置仓库）生成 [RedirectPolicySnapshot]。
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
        val now = System.currentTimeMillis()
        val gen = generationCounter.incrementAndGet()
        val rules = LinkedHashMap<String, Map<Int, List<RedirectRule>>>()

        for (packageName in ServicePreferences.srPackages) {
            val userRules = LinkedHashMap<Int, List<RedirectRule>>()
            for (userId in userIds) {
                val zipped = ServicePreferences.getPackageSrZipped(packageName, userId)
                if (zipped.isNotEmpty()) {
                    userRules[userId] = zipped.map { (source, target) ->
                        RedirectRule(source = source, target = target)
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
            storageRedirectRules = rules,
            readOnlyRules = ServicePreferences.getAllReadOnly(),
            denylist = ServicePreferences.denylist.toSet(),
            recordSharedStorage = ServicePreferences.recordSharedStorage,
            recordExternalAppSpecificStorage = ServicePreferences.recordExternalAppSpecificStorage,
            aggressivelyPromptForReadingMediaFiles = ServicePreferences.aggressivelyPromptForReadingMediaFiles,
            upsertRecords = ServicePreferences.upsert,
        )
    }

}
