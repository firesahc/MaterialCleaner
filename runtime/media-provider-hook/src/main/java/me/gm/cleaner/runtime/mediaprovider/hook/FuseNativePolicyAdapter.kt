package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * FUSE Native Hook 的策略适配器。
 *
 * 负责将 DataBus 中的策略快照同步到 FUSE Native Hook (libinline.so)。
 *
 * ## 数据流
 * ```
 * DataBus configured_mount_points.json
 *   → HookPolicyCache.loadAndPushConfiguredMountPoints()
 *     → FuseNativePolicyAdapter.applyConfiguredMountPoints()
 *       → InlineHookConfig.setMountPoint() [JNI → native]
 * ```
 *
 * ## 独立刷新路径
 * [refreshFromDataBus] 可被 HookPolicyRefreshScheduler 定时调用，
 * 不依赖 Binder 推送。这实现了"Native 侧通过 Java 中介读取 DataBus"
 * 的架构。未来优化方向：Native 侧直接读取 DataBus（跳过 Java 层）。
 *
 * ## 降级行为
 * - DataBus 快照不可用 → keep 上次有效值（不主动清空 native mountPoint）
 * - 空数组 → 清空 native mountPoint（显式清空操作）
 */
object FuseNativePolicyAdapter {
    private const val TAG = "FuseNativePolicyAdapter"

    /**
     * 从 DataBus 读取 configured_mount_points.json 并推送到 native。
     * 由 HookPolicyCache 或定时刷新调度器调用。
     *
     * @param currentGeneration 当前已缓存的 generation，用于跳过未变更的快照
     * @param signalTimestamp 上次 signal 时间戳，用于跳过未变更的信号
     * @return Pair(是否成功, 新的 generation)
     */
    fun refreshFromDataBus(
        currentGeneration: Long,
        signalTimestamp: Long
    ): Pair<Boolean, Long> {
        // 先检查 signal 是否变更
        val mountSignalTime = HookDataBusBridge.getSignalTimestamp(
            DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED
        )
        if (mountSignalTime <= signalTimestamp && signalTimestamp > 0) {
            return Pair(true, currentGeneration) // 未变更
        }

        // 读取快照
        val json = HookDataBusBridge.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
            ?: return Pair(false, currentGeneration)

        return try {
            val root = JSONObject(json)
            val generation = root.optLong("generation", 0L)
            if (generation <= currentGeneration && currentGeneration > 0) {
                return Pair(true, currentGeneration) // generation 未更新
            }

            val pointsArr = root.optJSONArray("points")
            if (pointsArr == null || pointsArr.length() == 0) {
                // 空数组：显式清空 native mountPoint
                applyConfiguredMountPoints(emptyArray(), generation)
                return Pair(true, generation)
            }

            val points = Array(pointsArr.length()) { pointsArr.getString(it) }
            applyConfiguredMountPoints(points, generation)
            Pair(true, generation)
        } catch (e: Throwable) {
            Log.e(TAG, "refreshFromDataBus: failed", e)
            Pair(false, currentGeneration)
        }
    }

    fun applyConfiguredMountPoints(points: Array<String>, generation: Long) {
        try {
            InlineHookConfig.setMountPoint(points)
            NativeHookStatus.markMountPointsApplySucceeded(generation, points.size)
            Log.i(TAG, "applyConfiguredMountPoints: count=${points.size}, generation=$generation")
        } catch (t: Throwable) {
            NativeHookStatus.markMountPointsApplyFailed(generation, points.size, t)
            Log.e(TAG, "applyConfiguredMountPoints failed: count=${points.size}, generation=$generation", t)
            throw t
        }
    }

    fun applyRecordExternalAppSpecificStorage(value: Boolean) {
        try {
            InlineHookConfig.setRecordExternalAppSpecificStorage(value)
        } catch (t: Throwable) {
            Log.w(TAG, "applyRecordExternalAppSpecificStorage ignored because native hook is unavailable", t)
        }
    }
}
