package me.gm.cleaner.server

import android.util.Log
import api.SystemService
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.dao.policy.ConfiguredMountPointsSnapshot
import me.gm.cleaner.dao.policy.DataBus
import me.gm.cleaner.dao.policy.RedirectPolicyBuilder
import me.gm.cleaner.dao.policy.RedirectPolicySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 策略快照发布器。
 *
 * 负责将 [RedirectPolicyBuilder] 构建的策略快照序列化为 JSON，
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
    fun publishAll() {
        if (!DataBus.ensureInitialized()) {
            Log.w(TAG, "DataBus not available, skipping publishAll")
            return
        }

        val userIds = SystemService.getUserIdsNoThrow()
        val policy = RedirectPolicyBuilder.build(userIds)

        publishRedirectPolicy(policy)
        publishReadOnly(policy)
        publishConfiguredMountPoints(policy)
        Log.i(TAG, "publishAll: done, generation=${policy.generation}")
    }

    /**
     * 发布重定向策略快照。
     */
    @JvmOverloads
    fun publishRedirectPolicy(policy: RedirectPolicySnapshot? = null) {
        if (!DataBus.ensureInitialized()) return

        val snapshot = policy ?: RedirectPolicyBuilder.build(
            SystemService.getUserIdsNoThrow()
        )

        val json = serializeRedirectPolicy(snapshot)
        DataBus.writeSnapshot(DataBus.SNAPSHOT_REDIRECT_POLICY, json)
        DataBus.signal(DataBus.SIGNAL_REDIRECT_POLICY_CHANGED)
        Log.d(TAG, "publishRedirectPolicy: generation=${snapshot.generation}")
    }

    /**
     * 发布只读配置快照。
     */
    @JvmOverloads
    fun publishReadOnly(policy: RedirectPolicySnapshot? = null) {
        if (!DataBus.ensureInitialized()) return

        val snapshot = policy ?: RedirectPolicyBuilder.build(
            SystemService.getUserIdsNoThrow()
        )

        val json = serializeReadOnly(snapshot)
        DataBus.writeSnapshot(DataBus.SNAPSHOT_READ_ONLY, json)
        DataBus.signal(DataBus.SIGNAL_READ_ONLY_CHANGED)
        Log.d(TAG, "publishReadOnly: packages=${snapshot.readOnlyRules.size}")
    }

    /**
     * 发布配置挂载点快照。
     */
    @JvmOverloads
    fun publishConfiguredMountPoints(policy: RedirectPolicySnapshot? = null) {
        if (!DataBus.ensureInitialized()) return

        val snapshot = policy ?: RedirectPolicyBuilder.build(
            SystemService.getUserIdsNoThrow()
        )
        val mountPoints = RedirectPolicyBuilder.buildConfiguredMountPoints(snapshot)

        val json = serializeConfiguredMountPoints(mountPoints)
        DataBus.writeSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS, json)
        DataBus.signal(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        Log.d(TAG, "publishConfiguredMountPoints: count=${mountPoints.points.size}")
    }

    // ── JSON 序列化 ──

    private fun serializeRedirectPolicy(snapshot: RedirectPolicySnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("generation", snapshot.generation)
        root.put("createdAt", snapshot.createdAt)

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
        root.put("points", JSONArray(snapshot.points as Collection<*>))

        return root.toString(2)
    }
}
