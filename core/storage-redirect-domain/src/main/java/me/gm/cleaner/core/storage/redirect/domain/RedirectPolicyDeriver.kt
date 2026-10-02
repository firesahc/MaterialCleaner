package me.gm.cleaner.core.storage.redirect.domain

/**
 * Pure derivation helpers for storage redirect policy snapshots.
 *
 * 挂载点推导经唯一的 [MountPlanDeriver] 投影桥，不直接持有解释器。
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

        for ((packageName, userRules) in policy.storageRedirectRules) {
            for ((userId, rules) in userRules) {
                val plan = MountPlanDeriver.derive(packageName, userId, rules) ?: continue
                points.addAll(plan.mountPoints)
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
        return MountPlanDeriver.resolveMountedPath(rules, path)
    }
}
