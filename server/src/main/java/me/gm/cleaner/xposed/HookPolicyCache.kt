package me.gm.cleaner.xposed

import android.text.TextUtils
import android.util.Log
import me.gm.cleaner.dao.MountRules
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.dao.policy.DataBus
import org.json.JSONObject
import java.io.File

/**
 * MediaProvider Java Hook 层的本地策略缓存。
 *
 * 消费 [DataBus] 发布的快照，减少热路径对 Binder 的强依赖。
 *
 * ## 缓存内容
 * - **ReadOnlyCache**：packageName → Set<path>，来自 read_only.json 快照
 * - **RuleCache**：packageName → MountRules，来自 redirect_policy.json 快照
 * - **ConfiguredMountPoints**：挂载点列表，来自 configured_mount_points.json 快照（推送到 native）
 *
 * ## 刷新策略
 * - 初始化时从 DataBus 读取最后一次快照
 * - [refreshFromDataBus] 由外部按需调用（监听 signal 后）
 * - Binder 同步（setReadOnlyPaths/setMountPoint）仍保留作为 fallback
 *
 * ## 降级行为
 * - 快照不存在 → 返回 null，由调用方走 Binder fallback
 * - 快照存在但 generation 过期 → 仍使用当前缓存，直到下次刷新
 */
object HookPolicyCache {
    private const val TAG = "HookPolicyCache"

    /** 默认用户 ID（用于快照中未指定 userId 的规则） */
    private const val DEFAULT_USER_ID = 0

    // ── ReadOnly 缓存 ──
    @Volatile
    private var readOnlyCache: Map<String, Set<String>> = emptyMap()
    @Volatile
    private var readOnlyGeneration: Long = 0L

    // ── Rule 缓存 ──
    @Volatile
    private var ruleCache: Map<String, MountRules> = emptyMap()
    @Volatile
    private var policyGeneration: Long = 0L

    // ── Configured Mount Points（推送到 native） ──
    @Volatile
    private var configuredMountPointsGeneration: Long = 0L
    @Volatile
    private var lastMountSignalTimestamp: Long = 0L

    /** 已推送到 native 的 configured_mount_points generation（用于诊断） */
    val nativeMountPointsGeneration: Long get() = configuredMountPointsGeneration

    // ── Denylist ──
    @Volatile
    private var denylist: Set<String> = emptySet()

    // ── 偏好标记 ──
    @Volatile
    var recordExternalAppSpecificStorage: Boolean = false
        private set

    /**
     * 从 DataBus 加载最后一次快照初始化缓存。
     * 应在 MediaProvider 进程初始化时调用一次。
     */
    fun initFromDataBus() {
        Log.i(TAG, "initFromDataBus: loading snapshots...")

        // 读取 redirect_policy.json
        val policyJson = DataBus.readSnapshot(DataBus.SNAPSHOT_REDIRECT_POLICY)
        if (policyJson != null) {
            try {
                parseRedirectPolicy(policyJson)
                Log.i(TAG, "initFromDataBus: loaded redirect_policy, generation=$policyGeneration, " +
                        "packages=${ruleCache.size}")
            } catch (e: Exception) {
                Log.e(TAG, "initFromDataBus: failed to parse redirect_policy", e)
            }
        } else {
            Log.w(TAG, "initFromDataBus: no redirect_policy snapshot available")
        }

        // 读取 read_only.json
        val roJson = DataBus.readSnapshot(DataBus.SNAPSHOT_READ_ONLY)
        if (roJson != null) {
            try {
                parseReadOnly(roJson)
                Log.i(TAG, "initFromDataBus: loaded read_only, generation=$readOnlyGeneration, " +
                        "packages=${readOnlyCache.size}")
            } catch (e: Exception) {
                Log.e(TAG, "initFromDataBus: failed to parse read_only", e)
            }
        } else {
            Log.w(TAG, "initFromDataBus: no read_only snapshot available")
        }

        // 读取 configured_mount_points.json → 推送到 native
        loadAndPushConfiguredMountPoints()
    }

    /**
     * 检查快照是否比当前缓存更新。
     * signal timestamp 表示通知发生时间，snapshot generation 表示策略代数。
     * 两者独立追踪，不混用。
     */
    fun isStale(): Boolean {
        val roSignalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_READ_ONLY_CHANGED)
        val policySignalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_REDIRECT_POLICY_CHANGED)
        val mountSignalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        return roSignalTime > readOnlyGeneration
                || policySignalTime > policyGeneration
                || mountSignalTime > lastMountSignalTimestamp
    }

    /**
     * 从 DataBus 刷新全部缓存并同步 native 挂载点。
     */
    fun refreshFromDataBus() {
        Log.d(TAG, "refreshFromDataBus")
        initFromDataBus()
    }

    /**
     * 尝试从 DataBus 刷新 native 挂载点。
     * 先比较 signal timestamp（上次通知时间），如果未变化则跳过；
     * 如果变化则读取 snapshot，内部再比较 snapshot generation（策略代数）。
     */
    fun tryRefreshNativeMountPoints() {
        val mountSignalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        if (mountSignalTime <= lastMountSignalTimestamp && lastMountSignalTimestamp > 0) {
            return  // signal 未变更
        }
        lastMountSignalTimestamp = mountSignalTime
        loadAndPushConfiguredMountPoints()
    }

    // ═══════════════════════════════════════════════════════════
    // Configured Mount Points → Native
    // ═══════════════════════════════════════════════════════════

    /**
     * 从 DataBus 读取 configured_mount_points.json，
     * 解析 points 数组，并通过 [InlineHookConfig.setMountPoint] 推送到 native。
     * 此路径独立于 Binder setMountPoint，两者可并行工作。
     */
    private fun loadAndPushConfiguredMountPoints() {
        val json = DataBus.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
        if (json == null) {
            Log.d(TAG, "loadConfiguredMountPoints: no snapshot available")
            return
        }

        try {
            val root = JSONObject(json)
            val generation = root.optLong("generation", 0L)
            if (generation <= configuredMountPointsGeneration && configuredMountPointsGeneration > 0) {
                Log.d(TAG, "loadConfiguredMountPoints: generation not newer ($generation <= $configuredMountPointsGeneration)")
                return
            }

            val pointsArr = root.optJSONArray("points")
            if (pointsArr == null || pointsArr.length() == 0) {
                Log.i(TAG, "loadConfiguredMountPoints: empty points, clearing native mountPoint, generation=$generation")
                // 空数组必须显式推送到 native——清除旧 mountPoint
                InlineHookConfig.setMountPoint(emptyArray())
                configuredMountPointsGeneration = generation
                return
            }

            val points = Array(pointsArr.length()) { pointsArr.getString(it) }
            InlineHookConfig.setMountPoint(points)
            configuredMountPointsGeneration = generation

            Log.i(TAG, "loadConfiguredMountPoints: pushed ${points.size} points to native, generation=$generation")
        } catch (e: Exception) {
            Log.e(TAG, "loadConfiguredMountPoints: failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ReadOnly 查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查指定路径是否在只读规则中。
     *
     * @param packageName 包名
     * @param path 要检查的路径（已规范化）
     * @param pathAsUser 按 userId=0 展开的路径
     * @return true 如果路径命中只读规则
     */
    fun isReadOnly(packageName: String, pathAsUser: String): Boolean {
        val readOnlyPaths = readOnlyCache[packageName] ?: return false
        val parent = File(pathAsUser).parent ?: return false
        return readOnlyPaths.any { roPath ->
            roPath.equals(pathAsUser, ignoreCase = true) ||
                    roPath.equals(parent, ignoreCase = true)
        }
    }

    /**
     * 获取只读规则的 generation，用于判断缓存是否存在。
     * 返回 0 表示未加载任何快照。
     */
    fun getReadOnlyGeneration(): Long = readOnlyGeneration

    // ═══════════════════════════════════════════════════════════
    // Rule 查询（路径重定向）
    // ═══════════════════════════════════════════════════════════

    /**
     * 从本地缓存计算挂载后路径。
     *
     * @param packageName 包名
     * @param path 原始路径
     * @return 挂载后路径，如果包不在缓存中返回 null（调用方应走 Binder fallback）
     */
    fun getMountedPath(packageName: String, path: String): String? {
        val rules = ruleCache[packageName] ?: return null
        return rules.getMountedPath(path)
    }

    /**
     * 检查指定包是否在 denylist 中。
     */
    fun isDenied(packageName: String): Boolean =
        denylist.contains(packageName)

    // ═══════════════════════════════════════════════════════════
    // JSON 解析
    // ═══════════════════════════════════════════════════════════

    private fun parseRedirectPolicy(json: String) {
        val root = JSONObject(json)
        val generation = root.optLong("generation", 0L)
        if (generation <= policyGeneration && policyGeneration > 0) {
            Log.d(TAG, "parseRedirectPolicy: generation not newer ($generation <= $policyGeneration)")
            return
        }

        val newCache = mutableMapOf<String, MountRules>()
        val rulesObj = root.optJSONObject("storageRedirectRules")
        if (rulesObj != null) {
            for (pkg in rulesObj.keys()) {
                val userObj = rulesObj.optJSONObject(pkg)
                if (userObj == null) continue

                // 取 userId=0 的规则（最常用），暂不处理多用户
                val rulesArr = userObj.optJSONArray(DEFAULT_USER_ID.toString())
                    ?: userObj.optJSONArray("0")
                if (rulesArr == null || rulesArr.length() == 0) continue

                val zipped = mutableListOf<Pair<String, String>>()
                for (i in 0 until rulesArr.length()) {
                    val ruleObj = rulesArr.getJSONObject(i)
                    val source = ruleObj.getString("source")
                    val target = ruleObj.getString("target")
                    zipped.add(source to target)
                }
                newCache[pkg] = MountRules(zipped)
            }
        }

        ruleCache = newCache
        policyGeneration = generation

        // 解析 denylist
        val denyArr = root.optJSONArray("denylist")
        if (denyArr != null) {
            val denySet = mutableSetOf<String>()
            for (i in 0 until denyArr.length()) {
                denySet.add(denyArr.getString(i))
            }
            denylist = denySet
        }

        // 解析偏好标记
        recordExternalAppSpecificStorage = root.optBoolean("recordExternalAppSpecificStorage", false)
    }

    private fun parseReadOnly(json: String) {
        val root = JSONObject(json)
        val generation = root.optLong("generation", 0L)
        if (generation <= readOnlyGeneration && readOnlyGeneration > 0) {
            Log.d(TAG, "parseReadOnly: generation not newer ($generation <= $readOnlyGeneration)")
            return
        }

        val newCache = mutableMapOf<String, Set<String>>()
        val roObj = root.optJSONObject("readOnlyRules")
        if (roObj != null) {
            for (pkg in roObj.keys()) {
                val arr = roObj.optJSONArray(pkg) ?: continue
                val paths = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    paths.add(arr.getString(i))
                }
                if (paths.isNotEmpty()) {
                    newCache[pkg] = paths
                }
            }
        }

        readOnlyCache = newCache
        readOnlyGeneration = generation
    }
}
