package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import java.io.File
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
            storageRedirectRules = rules,
            readOnlyRules = ServicePreferences.getAllReadOnly(),
            denylist = ServicePreferences.denylist.toSet(),
            recordSharedStorage = ServicePreferences.recordSharedStorage,
            recordExternalAppSpecificStorage = ServicePreferences.recordExternalAppSpecificStorage,
            aggressivelyPromptForReadingMediaFiles = ServicePreferences.aggressivelyPromptForReadingMediaFiles,
            upsertRecords = ServicePreferences.upsert,
        )
    }

    private fun buildRedirectRuleOrNull(
        packageName: String,
        userId: Int,
        source: String,
        target: String,
    ): RedirectRule? {
        val normalizedSource = normalizeExternalStoragePath(source)
        val normalizedTarget = normalizeExternalStoragePath(target)
        if (normalizedSource == null || normalizedTarget == null) {
            Log.w(
                "MC_REDIRECT",
                "[RuntimeRedirectPolicyFactory] drop invalid rule pkg=$packageName " +
                        "user=$userId source=$source target=$target"
            )
            return null
        }
        if (normalizedSource == normalizedTarget) {
            Log.w(
                "MC_REDIRECT",
                "[RuntimeRedirectPolicyFactory] drop no-op rule pkg=$packageName " +
                        "user=$userId path=$normalizedSource"
            )
            return null
        }
        return RedirectRule(source = normalizedSource, target = normalizedTarget)
    }

    private fun normalizeExternalStoragePath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || '\u0000' in trimmed || !trimmed.startsWith("/")) {
            return null
        }
        val normalized = try {
            File(trimmed).canonicalPath
        } catch (e: Exception) {
            File(trimmed).absolutePath
        }
        return normalized.takeIf {
            RuntimeFileUtils.startsWith(RuntimeFileUtils.externalStorageDirParent, it)
        }
    }

}
