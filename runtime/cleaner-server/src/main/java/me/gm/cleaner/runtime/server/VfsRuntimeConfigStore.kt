package me.gm.cleaner.runtime.server

import api.SystemService
import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.PlatformCapabilities
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import me.gm.cleaner.core.storage.redirect.domain.RuntimeMountPlan

/**
 * VFS 层运行态策略视图。
 *
 * VFS/Mounter 只通过这里消费策略快照和平台能力，避免继续把
 * ServicePreferences、系统属性探测散落到 mount 热路径中。
 */
object VfsRuntimeConfigStore {
    @Volatile
    private var policySnapshot: RedirectPolicySnapshot? = null

    @Volatile
    private var platformCapabilities: PlatformCapabilities? = null

    fun updatePolicy(snapshot: RedirectPolicySnapshot) {
        policySnapshot = snapshot
    }

    fun updateCapabilities(capabilities: PlatformCapabilities) {
        platformCapabilities = capabilities
    }

    @JvmOverloads
    fun refreshPolicy(userIds: List<Int> = SystemService.getUserIdsNoThrow()): RedirectPolicySnapshot {
        val snapshot = RuntimeRedirectPolicyFactory.build(userIds)
        updatePolicy(snapshot)
        return snapshot
    }

    fun refreshCapabilities(): PlatformCapabilities {
        val capabilities = PlatformCapabilitiesDetector.detect()
        updateCapabilities(capabilities)
        return capabilities
    }

    fun currentPolicy(): RedirectPolicySnapshot =
        policySnapshot ?: refreshPolicy()

    fun currentCapabilities(): PlatformCapabilities =
        platformCapabilities ?: refreshCapabilities()

    fun getStorageRedirectPackages(): Set<String> =
        currentPolicy().storageRedirectRules.keys

    fun getPackageRuleCount(packageName: String): Int =
        currentPolicy().storageRedirectRules[packageName]
            ?.values
            ?.sumOf { it.size }
            ?: 0

    fun getMountPlan(packageName: String, userId: Int): RuntimeMountPlan? {
        val rules = currentPolicy()
            .storageRedirectRules[packageName]
            ?.let { userRules -> userRules[userId] }
            ?: return null
        return MountPlanDeriver.derive(packageName, userId, rules)
    }

    fun getMountTargets(packageName: String, userId: Int): List<String> =
        getMountPlan(packageName, userId)?.targets ?: emptyList()

    fun shouldRecordExternalAppSpecificStorage(packageName: String): Boolean {
        val policy = currentPolicy()
        return policy.recordExternalAppSpecificStorage && packageName !in policy.denylist
    }

    fun isFuseBpfEnabled(): Boolean =
        currentCapabilities().isFuseBpfEnabled

    fun shouldMountForAllPackages(): Boolean =
        !isFuseBpfEnabled() && currentPolicy().recordExternalAppSpecificStorage
}
