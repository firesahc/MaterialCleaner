package me.gm.cleaner.core.storage.redirect.domain

/**
 * Pure derivation helpers for storage redirect policy snapshots.
 *
 * P3 迁移第一步：挂载点推导改走 canonical interpreter。
 * 快照内仍是裸 Pair（ruleId/type/order 在 Factory 投影时丢失），这里按
 * source==target 恢复 PRESERVE/MAP、按 List 顺序恢复 orderIndex 后解释。
 * P2 差分护栏已证明规范输入下与 MountRules 全等；非规范历史数据抛异常时
 * 回退旧实现，保证发布链永不因语义迁移崩溃（过渡兜底，legacy 清除后删除）。
 */
object RedirectPolicyDeriver {

    /**
     * Derives the FUSE-visible configured mount point set from redirect rules.
     *
     * The result is a rule-derived view, not a report of mounts that already
     * succeeded at VFS runtime.
     */
    fun buildConfiguredMountPoints(policy: RedirectPolicySnapshot): ConfiguredMountPointsSnapshot {
        val points = mutableListOf<String>()

        for ((_, userRules) in policy.storageRedirectRules) {
            for ((_, rules) in userRules) {
                val zipped = rules.map { it.source to it.target }
                points.addAll(deriveMountPoints(zipped))
            }
        }

        return ConfiguredMountPointsSnapshot(
            schemaVersion = 1,
            generation = policy.generation,
            publisherEpoch = policy.publisherEpoch,
            createdAt = policy.createdAt,
            publisher = policy.publisher,
            redirectRevision = policy.redirectRevision,
            points = points,
        )
    }

    fun getMountedPath(
        policy: RedirectPolicySnapshot,
        packageName: String,
        userId: Int,
        path: String,
    ): String {
        val rules = policy.storageRedirectRules[packageName]?.get(userId) ?: return path
        val zipped = rules.map { it.source to it.target }
        return interpretMountedPath(zipped, path)
    }

    private fun deriveMountPoints(zipped: List<Pair<String, String>>): List<String> {
        val ordered = toOrderedRulesOrNull(zipped) ?: return MountRules(zipped).mountPoint
        return try {
            OrderedRedirectInterpreter.deriveMountPoints(ordered)
                .map(RedirectMountPoint::derivedPath)
        } catch (_: IllegalArgumentException) {
            MountRules(zipped).mountPoint
        }
    }

    private fun interpretMountedPath(zipped: List<Pair<String, String>>, path: String): String {
        val ordered = toOrderedRulesOrNull(zipped) ?: return MountRules(zipped).getMountedPath(path)
        return try {
            OrderedRedirectInterpreter.interpret(path, ordered).derivedPath
        } catch (_: IllegalArgumentException) {
            MountRules(zipped).getMountedPath(path)
        }
    }

    private fun toOrderedRulesOrNull(zipped: List<Pair<String, String>>): List<OrderedRedirectRule>? {
        if (zipped.isEmpty()) return emptyList()
        return try {
            zipped.mapIndexed { index, (source, target) ->
                OrderedRedirectRule(
                    ruleId = RuleId("deriver-$index"),
                    type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                    source = source,
                    target = target,
                    orderIndex = index,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
