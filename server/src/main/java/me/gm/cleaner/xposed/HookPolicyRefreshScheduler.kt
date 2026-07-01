package me.gm.cleaner.xposed

import android.util.Log
import me.gm.cleaner.dao.policy.DataBus
import java.util.Timer
import java.util.TimerTask

/**
 * HookPolicy 刷新调度器。
 *
 * 运行在 MediaProvider 进程中，使用 Timer 低频轮询
 * DataBus signal 文件，检测到变更时刷新 HookPolicyCache。
 *
 * 使用 java.util.Timer 避免 hidden-api 模块的 android.os.Handler stub 遮蔽问题。
 *
 * ## 设计
 * - Timer 守护线程每 [POLL_INTERVAL_MS] 检查一次
 * - 仅检查 configured_mount_points_changed signal（最低开销）
 * - 与 Binder setMountPoint 并行工作（独立 fallback）
 * - 不在 MediaProvider 中创建独立 native 线程或 inotify watcher
 */
object HookPolicyRefreshScheduler {
    private const val TAG = "HookPolicyRefreshScheduler"
    private const val POLL_INTERVAL_MS = 5000L

    private val timer = Timer("HookPolicyRefresh", true)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "Started, pollInterval=${POLL_INTERVAL_MS}ms")
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    pollAndRefresh()
                } catch (e: Exception) {
                    Log.e(TAG, "pollAndRefresh failed", e)
                }
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS)
    }

    private fun pollAndRefresh() {
        val mountSignalTime = DataBus.getSignalTimestamp(
            DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED
        )
        if (mountSignalTime > 0) {
            HookPolicyCache.tryRefreshNativeMountPoints()
        }
    }
}
