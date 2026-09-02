package me.gm.cleaner.core.storage.redirect.domain

/**
 * Pure derivation helpers for storage redirect policy snapshots.
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
                val mountRules = MountRules(zipped)
                points.addAll(mountRules.mountPoint)
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
        return MountRules(zipped).getMountedPath(path)
    }
}
