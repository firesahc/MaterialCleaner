package me.gm.cleaner.runtime.server

import android.util.Log
import api.SystemService
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.domain.ConfiguredMountPointsSnapshot
import me.gm.cleaner.core.storage.redirect.domain.PlatformCapabilities
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicyDeriver
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 策略快照发布器。
 *
 * 负责将运行时策略快照序列化为 JSON，
 * 并通过 [DataBus] 发布到文件系统总线。
 *
 * 发布时机：
 * - 服务器初始化完成后（publishAll）
 * - notifySrChanged 后（publishRedirectPolicy + publishConfiguredMountPoints）
 * - notifyReadOnlyChanged 后（publishReadOnly）
 * - notifyPreferencesChanged 后（publishRedirectPolicy）
 * - Hook 重连成功后（publishAll）
 */
object SnapshotPublisher {
    private const val TAG = "SnapshotPublisher"

    /**
     * 发布全部快照。
     * 调用时机：服务器启动、Hook 重连成功后。
     */
    fun publishAll(): Boolean {
        if (!DataBus.ensureInitialized()) {
            Log.w(TAG, "DataBus not available, skipping publishAll")
            return false
        }

        val userIds = SystemService.getUserIdsNoThrow()
        val policy = RuntimeRedirectPolicyFactory.build(userIds)

        val policyPublished = publishRedirectPolicy(policy)
        val readOnlyPublished = publishReadOnly(policy)
        val mountPointsPublished = publishConfiguredMountPoints(policy)
        val capsPublished = publishPlatformCapabilities()
        // 关键快照（策略/只读/挂载点）决定架构有效性；
        // platform_capabilities 是信息性快照，发布失败不影响架构正常运行。
        val criticalPublished = policyPublished && readOnlyPublished && mountPointsPublished
        if (!capsPublished) {
            Log.w(TAG, "publishAll: platform_capabilities snapshot failed (non-critical)")
        }
        Log.i(TAG, "publishAll: done, generation=${policy.generation}, critical=$criticalPublished, caps=$capsPublished")
        return criticalPublished
    }

    /**
     * 发布重定向策略快照。
     */
    @JvmOverloads
    fun publishRedirectPolicy(policy: RedirectPolicySnapshot? = null): Boolean {
        if (!DataBus.ensureInitialized()) return false

        val snapshot = policy ?: RuntimeRedirectPolicyFactory.build(SystemService.getUserIdsNoThrow())

        val json = serializeRedirectPolicy(snapshot)
        val written = DataBus.writeSnapshot(DataBus.SNAPSHOT_REDIRECT_POLICY, json)
        val signaled = written && DataBus.signal(DataBus.SIGNAL_REDIRECT_POLICY_CHANGED)
        Log.d(TAG, "publishRedirectPolicy: generation=${snapshot.generation}")
        return written && signaled
    }

    /**
     * 发布只读配置快照。
     */
    @JvmOverloads
    fun publishReadOnly(policy: RedirectPolicySnapshot? = null): Boolean {
        if (!DataBus.ensureInitialized()) return false

        val snapshot = policy ?: RuntimeRedirectPolicyFactory.build(SystemService.getUserIdsNoThrow())

        val json = serializeReadOnly(snapshot)
        val written = DataBus.writeSnapshot(DataBus.SNAPSHOT_READ_ONLY, json)
        val signaled = written && DataBus.signal(DataBus.SIGNAL_READ_ONLY_CHANGED)
        Log.d(TAG, "publishReadOnly: packages=${snapshot.readOnlyRules.size}")
        return written && signaled
    }

    /**
     * 发布配置挂载点快照。
     */
    @JvmOverloads
    fun publishConfiguredMountPoints(policy: RedirectPolicySnapshot? = null): Boolean {
        if (!DataBus.ensureInitialized()) return false

        val snapshot = policy ?: RuntimeRedirectPolicyFactory.build(SystemService.getUserIdsNoThrow())
        val mountPoints = RedirectPolicyDeriver.buildConfiguredMountPoints(snapshot)

        val json = serializeConfiguredMountPoints(mountPoints)
        val written = DataBus.writeSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS, json)
        val signaled = written && DataBus.signal(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        Log.d(TAG, "publishConfiguredMountPoints: count=${mountPoints.points.size}")
        return written && signaled
    }

    /**
     * 发布平台能力快照。
     */
    fun publishPlatformCapabilities(): Boolean {
        if (!DataBus.ensureInitialized()) return false

        val caps = PlatformCapabilitiesDetector.detect()
        val json = PlatformCapabilitiesDetector.toJson(caps)
        val written = DataBus.writeSnapshot(DataBus.SNAPSHOT_PLATFORM_CAPABILITIES, json)
        val signaled = written && DataBus.signal(DataBus.SIGNAL_PLATFORM_CAPABILITIES_CHANGED)
        Log.d(TAG, "publishPlatformCapabilities: sdk=${caps.sdkVersionInt}, " +
                "fuseBpf=${caps.isFuseBpfEnabled}, fuse=${caps.fuseAvailable}")
        return written && signaled
    }

    // ── JSON 序列化 ──

    private fun serializeRedirectPolicy(snapshot: RedirectPolicySnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("generation", snapshot.generation)
        root.put("createdAt", snapshot.createdAt)
        root.put("publisher", snapshot.publisher)

        // storageRedirectRules: { pkg: { userId: [{source, target}] } }
        val rulesObj = JSONObject()
        for ((pkg, userRules) in snapshot.storageRedirectRules) {
            val userObj = JSONObject()
            for ((userId, rules) in userRules) {
                val rulesArr = JSONArray()
                for (rule in rules) {
                    val ruleObj = JSONObject()
                    ruleObj.put("source", rule.source)
                    ruleObj.put("target", rule.target)
                    rulesArr.put(ruleObj)
                }
                userObj.put(userId.toString(), rulesArr)
            }
            rulesObj.put(pkg, userObj)
        }
        root.put("storageRedirectRules", rulesObj)

        // readOnlyRules: { pkg: [paths] }
        val roObj = JSONObject()
        for ((pkg, paths) in snapshot.readOnlyRules) {
            roObj.put(pkg, JSONArray(paths as Collection<*>))
        }
        root.put("readOnlyRules", roObj)

        // denylist
        root.put("denylist", JSONArray(snapshot.denylist.toList() as Collection<*>))

        // booleans
        root.put("recordSharedStorage", snapshot.recordSharedStorage)
        root.put("recordExternalAppSpecificStorage", snapshot.recordExternalAppSpecificStorage)
        root.put("aggressivelyPromptForReadingMediaFiles", snapshot.aggressivelyPromptForReadingMediaFiles)
        root.put("upsertRecords", snapshot.upsertRecords)

        return root.toString(2)
    }

    private fun serializeReadOnly(snapshot: RedirectPolicySnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("generation", snapshot.generation)
        root.put("createdAt", snapshot.createdAt)
        root.put("publisher", snapshot.publisher)

        val roObj = JSONObject()
        for ((pkg, paths) in snapshot.readOnlyRules) {
            roObj.put(pkg, JSONArray(paths as Collection<*>))
        }
        root.put("readOnlyRules", roObj)

        return root.toString(2)
    }

    private fun serializeConfiguredMountPoints(
        snapshot: ConfiguredMountPointsSnapshot
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("generation", snapshot.generation)
        root.put("createdAt", snapshot.createdAt)
        root.put("publisher", snapshot.publisher)
        root.put("points", JSONArray(snapshot.points as Collection<*>))

        return root.toString(2)
    }
}
