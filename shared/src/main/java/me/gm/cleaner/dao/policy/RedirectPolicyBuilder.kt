package me.gm.cleaner.dao.policy

import me.gm.cleaner.dao.MountRules
import me.gm.cleaner.dao.ServicePreferences

/**
 * 重定向策略构建器。
 *
 * 负责：
 * - 从 [ServicePreferences] 构建 [RedirectPolicySnapshot]（策略权威快照）
 * - 从 [RedirectPolicySnapshot] 推导 [ConfiguredMountPointsSnapshot]（挂载点快照）
 * - 提供统一的路径匹配能力 [getMountedPath]
 *
 * 设计原则：
 * - 所有路径计算复用现有 [MountRules] 逻辑，不重复实现
 * - 纯函数风格：getMountedPath / buildConfiguredMountPoints 无副作用
 * - [build] 方法需外部提供 userIds（避免 shared 模块依赖 SystemService）
 */
object RedirectPolicyBuilder {

    /**
     * 从 [ServicePreferences] 构建完整的重定向策略快照。
     *
     * @param userIds 所有需要展开规则的用户 ID 列表（由调用方通过 SystemService 获取）
     */
    fun build(userIds: List<Int>): RedirectPolicySnapshot {
        val now = System.currentTimeMillis()
        val rules = LinkedHashMap<String, Map<Int, List<RedirectRule>>>()

        for (packageName in ServicePreferences.srPackages) {
            val userMap = LinkedHashMap<Int, List<RedirectRule>>()
            for (userId in userIds) {
                val zipped = ServicePreferences.getPackageSrZipped(packageName, userId)
                if (zipped.isNotEmpty()) {
                    userMap[userId] = zipped.map { (source, target) ->
                        RedirectRule(source = source, target = target)
                    }
                }
            }
            if (userMap.isNotEmpty()) {
                rules[packageName] = userMap
            }
        }

        return RedirectPolicySnapshot(
            schemaVersion = 1,
            generation = now,
            createdAt = now,
            storageRedirectRules = rules,
            readOnlyRules = ServicePreferences.getAllReadOnly(),
            denylist = ServicePreferences.denylist.toSet(),
            recordSharedStorage = ServicePreferences.recordSharedStorage,
            recordExternalAppSpecificStorage = ServicePreferences.recordExternalAppSpecificStorage,
            aggressivelyPromptForReadingMediaFiles = ServicePreferences.aggressivelyPromptForReadingMediaFiles,
            upsertRecords = ServicePreferences.upsert,
        )
    }

    /**
     * 从策略快照推导所有配置挂载点。
     *
     * 与旧 [me.gm.cleaner.client.CleanerHooksClient.syncMountPoint] 输出完全一致：
     * 对每个 package × userId 组合，使用 [MountRules.mountPoint] 计算挂载点列表。
     */
    fun buildConfiguredMountPoints(policy: RedirectPolicySnapshot): ConfiguredMountPointsSnapshot {
        val points = mutableListOf<String>()

        for ((_, userRules) in policy.storageRedirectRules) {
            for ((_, rules) in userRules) {
                val zipped = rules.map { it.source to it.target }
                val mountRules = MountRules(zipped)
                points.addAll(mountRules.mountPoint)
            }
        }

        return ConfiguredMountPointsSnapshot(
            schemaVersion = 1,
            generation = policy.generation,
            createdAt = policy.createdAt,
            points = points,
        )
    }

    /**
     * 计算指定路径在重定向策略下的挂载后路径。
     *
     * 复用 [MountRules.getMountedPath] 逻辑，纯函数无副作用。
     *
     * @param policy 当前策略快照
     * @param packageName 调用包名
     * @param userId 调用方用户 ID
     * @param path 原始路径
     * @return 挂载后的实际路径（如果未命中任何规则，返回原 path）
     */
    fun getMountedPath(
        policy: RedirectPolicySnapshot,
        packageName: String,
        userId: Int,
        path: String,
    ): String {
        val rules = policy.storageRedirectRules[packageName]?.get(userId) ?: return path
        val zipped = rules.map { it.source to it.target }
        return MountRules(zipped).getMountedPath(path)
    }
}
