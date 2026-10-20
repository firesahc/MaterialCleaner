package me.gm.cleaner.client

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.starter.Starter

enum class LaunchReason { BOOT, MANUAL, NOTIFICATION, RECOVERY }

object CleanerServerLauncher {
    private const val TAG = "MC/Launcher"
    private const val BINDER_WAIT_RETRIES = 20
    private const val BINDER_WAIT_DELAY_MS = 500L
    private val launchMutex = Mutex()

    suspend fun launch(
        context: Context,
        reason: LaunchReason,
        isLaunchStillValid: () -> Boolean = { ServiceBootStateStore.shouldRun() }
    ): Boolean = withContext(Dispatchers.IO) {
        launchMutex.withLock {
            runCatching {
                launchLocked(context.applicationContext, reason, isLaunchStillValid)
            }.onFailure {
                Log.e(TAG, "launch($reason) failed", it)
                CleanerClient.resetConnection()
            }.getOrDefault(false)
        }
    }

    fun stopCurrentServer() {
        CleanerClient.exit()
        CleanerClient.resetConnection()
        CleanerClient.killServerProcess()
    }

    fun isServerAlive(): Boolean = CleanerClient.pingBinder()

    private suspend fun launchLocked(
        context: Context,
        reason: LaunchReason,
        isLaunchStillValid: () -> Boolean
    ): Boolean {
        suspend fun ensureValid(stage: String): Boolean {
            val valid = isLaunchStillValid() && ServiceBootStateStore.shouldRun()
            if (!valid) {
                Log.i(TAG, "launch($reason): invalid at $stage, aborting")
                CleanerClient.resetConnection()
                CleanerClient.killServerProcess()
            }
            return valid
        }

        if (!ensureValid("before_root_check")) return false
        if ((reason == LaunchReason.BOOT || reason == LaunchReason.RECOVERY) &&
            CleanerClient.pingBinder()
        ) {
            Log.i(TAG, "launch($reason): binder already alive")
            return true
        }

        val isRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!isRoot) {
            Log.w(TAG, "launch($reason): no root access")
            return false
        }
        if (!ensureValid("after_root_check")) return false

        // 先尝试优雅退出：binder 存活时 server 会走 onDestroy 回收日志采集子进程。
        // 直接强杀会让这些子进程被 init 收养成孤儿，每次重启泄漏一批。
        // 强杀仅作兜底：binder 未连通或旧 server 无响应时仍需终止旧实例。
        CleanerClient.exit()
        CleanerClient.killServerProcess()
        if (!ensureValid("after_kill_old_server")) return false

        delay(1000)
        if (!ensureValid("after_start_delay")) return false

        Starter.writeDataFiles(context)
        if (!ensureValid("after_write_data_files")) return false

        // libsu 只有在显式 to(stdout, stderr) 时才会填充 Result.err，
        // 否则 Result.err 恒为空列表，失败证据会被整个丢掉。
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val result = Shell.cmd(Starter.command).to(stdout, stderr).exec()
        // starter 的失败信息写在 stderr（daemon 化之后只剩 logcat 一条证据链），
        // 这里无条件留痕：daemon 化路径上父进程退出码恒为 0，不能只靠 isSuccess 判定失败。
        val starterOutput = listOf(stderr.joinToString(), stdout.joinToString())
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        if (starterOutput.isNotBlank()) {
            Log.w(TAG, "launch($reason): starter output: $starterOutput")
        }
        if (!result.isSuccess) {
            val detail = starterOutput.take(200)
            Log.e(TAG, "launch($reason): shell command failed: $detail")
            recordSupFailure(ErrorCodes.SUP_START_FAILED, "$reason shell failed", detail)
            CleanerClient.resetConnection()
            return false
        }
        if (!ensureValid("after_shell_start")) return false

        if (!waitForBinder(isLaunchStillValid)) {
            Log.e(TAG, "launch($reason): binder timeout")
            // 启动器已退出但 binder 没起来：把启动器留下的输出并入失败详情，
            // 它是唯一能直接说明"服务为什么没起来"的证据（app 侧错误日志会进诊断包）。
            val timeoutDetail = if (starterOutput.isBlank()) {
                "starter exited but server binder never came up"
            } else {
                "starter exited but server binder never came up | ${starterOutput.take(200)}"
            }
            recordSupFailure(
                ErrorCodes.SUP_PROC_DEAD, "$reason binder timeout", timeoutDetail,
            )
            CleanerClient.resetConnection()
            CleanerClient.killServerProcess()
            return false
        }
        if (!ensureValid("after_wait_for_binder")) return false

        reloadServerConfiguration()
        if (!ensureValid("before_success")) return false

        Log.i(TAG, "launch($reason): success")
        return true
    }

    private suspend fun waitForBinder(isLaunchStillValid: () -> Boolean): Boolean {
        var retries = 0
        while (!CleanerClient.pingBinder() && retries < BINDER_WAIT_RETRIES) {
            if (!isLaunchStillValid() || !ServiceBootStateStore.shouldRun()) {
                CleanerClient.resetConnection()
                CleanerClient.killServerProcess()
                return false
            }
            delay(BINDER_WAIT_DELAY_MS)
            retries++
        }
        return CleanerClient.pingBinder()
    }

    private fun reloadServerConfiguration() {
        val svc = CleanerClient.service ?: return
        // 单次收敛通知：notifySrChanged 内部已覆盖偏好重载、快照发布
        // 与 VFS 重挂的完整副作用链；多次 notify 会形成发布/重挂回环
        // （真机实测一次恢复触发 3 轮 remount）。
        runCatching { svc.notifySrChanged() }
            .onFailure { Log.w(TAG, "notifySrChanged failed", it) }
        runCatching { svc.notifyReadOnlyChanged() }
            .onFailure { Log.w(TAG, "notifyReadOnlyChanged failed", it) }
    }

    /** 服务监督域失败事件入库：App 进程侧独立留痕（与 server 端 journal 分进程）。 */
    private fun recordSupFailure(code: String, reason: String, detail: String) {
        ClientErrorJournal.record(
            ErrorEvent(
                code = code,
                subject = reason,
                atElapsed = SystemClock.elapsedRealtime(),
                detail = detail,
            )
        )
    }
}
