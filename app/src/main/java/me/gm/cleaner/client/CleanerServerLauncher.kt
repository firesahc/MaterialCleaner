package me.gm.cleaner.client

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.starter.Starter
import java.util.Random

enum class LaunchReason { BOOT, MANUAL, NOTIFICATION, RECOVERY }

/**
 * 启动结果：区分「成功」、「彻底失败」和「后台重试中」三种状态。
 * 调用方可据此决定是否展示失败 UI、是否继续轮询等。
 */
sealed class LaunchResult {
    object Success : LaunchResult()
    object Failure : LaunchResult()
    object Retrying : LaunchResult()
}

object CleanerServerLauncher {
    private const val TAG = "MC/Launcher"
    private const val BINDER_WAIT_RETRIES = 20
    private const val BINDER_WAIT_DELAY_MS = 500L
    /** 启动阶段 Binder 握手失败后的独立重试延迟序列：200ms → 500ms → 1000ms → 2000ms × 5（BOOT/RECOVERY） */
    private val STARTUP_RETRY_DELAYS_MS_LONG = longArrayOf(200, 500, 1000, 2000, 2000, 2000, 2000, 2000, 2000)
    /** 启动阶段 Binder 握手失败后的独立重试延迟序列：200ms → 500ms → 1000ms → 2000ms × 2（MANUAL/NOTIFICATION） */
    private val STARTUP_RETRY_DELAYS_MS_SHORT = longArrayOf(200, 500, 1000, 2000, 2000)
    /** 抖动上限（毫秒），避免多设备同时重启造成重试风暴 */
    private val JITTER_MAX_MS: Long = 200L
    private val random = Random()
    private val launchMutex = Mutex()
    /** 启动后台 Binder 重试作业，用于在服务停止或 launch 无效时取消 */
    private var startupRetryJob: Job? = null
    /** 当前启动代际，用于旧重试任务与新启动任务的代际隔离。 */
    private val launchGeneration = AtomicInteger(0)

    suspend fun launch(
        context: Context,
        reason: LaunchReason,
        isLaunchStillValid: () -> Boolean = { ServiceBootStateStore.shouldRun() }
    ): LaunchResult = withContext(Dispatchers.IO) {
        launchMutex.withLock {
            runCatching {
                launchLocked(context.applicationContext, reason, isLaunchStillValid)
            }.onFailure {
                Log.e(TAG, "launch($reason) failed", it)
                CleanerClient.resetConnection()
            }.getOrDefault(LaunchResult.Failure)
        }
    }

    fun stopCurrentServer() {
        // 取消启动后台重试，避免服务停止后协程继续运行
        startupRetryJob?.cancel()
        startupRetryJob = null
        // 增加 generation，使任何尚未执行副作用的旧重试任务失效
        launchGeneration.incrementAndGet()
        CleanerClient.exit()
        CleanerClient.resetConnection()
        CleanerClient.killServerProcess()
    }

    fun isServerAlive(): Boolean = CleanerClient.pingBinder()

    private suspend fun launchLocked(
        context: Context,
        reason: LaunchReason,
        isLaunchStillValid: () -> Boolean
    ): LaunchResult {
        val generation = launchGeneration.incrementAndGet()
        suspend fun ensureValid(stage: String): Boolean {
            val valid = isLaunchStillValid() && ServiceBootStateStore.shouldRun()
            if (!valid) {
                Log.i(TAG, "launch($reason): invalid at $stage, aborting")
                CleanerClient.resetConnection()
                CleanerClient.killServerProcess()
            }
            return valid
        }

        if (!ensureValid("before_root_check")) return LaunchResult.Failure
        if ((reason == LaunchReason.BOOT || reason == LaunchReason.RECOVERY) &&
            CleanerClient.pingBinder()
        ) {
            Log.i(TAG, "launch($reason): binder already alive")
            return LaunchResult.Success
        }

        val isRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!isRoot) {
            Log.w(TAG, "launch($reason): no root access")
            return LaunchResult.Failure
        }
        if (!ensureValid("after_root_check")) return LaunchResult.Failure

        // 先尝试优雅退出：binder 存活时 server 会走 onDestroy 回收日志采集子进程。
        // 直接强杀会让这些子进程被 init 收养成孤儿，每次重启泄漏一批。
        // 强杀仅作兜底：binder 未连通或旧 server 无响应时仍需终止旧实例。
        CleanerClient.exit()
        CleanerClient.killServerProcess()
        if (!ensureValid("after_kill_old_server")) return LaunchResult.Failure

        delay(1000)
        if (!ensureValid("after_start_delay")) return LaunchResult.Failure

        Starter.writeDataFiles(context)
        if (!ensureValid("after_write_data_files")) return LaunchResult.Failure

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
            return LaunchResult.Failure
        }
        if (!ensureValid("after_shell_start")) return LaunchResult.Failure

        if (!waitForBinder(isLaunchStillValid)) {
            Log.e(TAG, "launch($reason): binder timeout, scheduling background startup retry")
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
            // 不立即杀死服务器进程，而是调度后台重试，避免开机期间 Hook Binder
            // 握手失败导致策略空窗（5.5.11 开机策略空窗修复）。
            scheduleStartupRetry(reason, isLaunchStillValid, generation)
            return LaunchResult.Retrying
        }
        if (!ensureValid("after_wait_for_binder")) return LaunchResult.Failure

        reloadServerConfiguration()
        if (!ensureValid("before_success")) return LaunchResult.Failure

        Log.i(TAG, "launch($reason): success")
        return LaunchResult.Success
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

    /**
     * 启动后台独立重试：按 200ms → 500ms → 1000ms → 2000ms × 5 的延迟序列
     * 持续尝试连接 Hook Binder，成功后执行服务器配置刷新，避免开机期间
     * 策略空窗（5.5.11 开机策略空窗修复）。通过独立协程执行，不阻塞主启动流程。
     *
     * 改进点：
     * - isActive 防护：避免重复启动重试协程；
     * - 延迟抖动：每次延迟加上随机抖动（0~200ms），避免多设备同时重启造成重试风暴；
     * - 启动类型区分：BOOT/RECOVERY 使用长序列，MANUAL/NOTIFICATION 使用短序列，
     *   手动启动用户期望更快反馈。
     */
    private fun scheduleStartupRetry(
        reason: LaunchReason,
        isLaunchStillValid: () -> Boolean,
        generation: Int
    ) {
        // 避免重复启动重试协程
        startupRetryJob?.takeIf { it.isActive }?.let {
            Log.i(TAG, "startup retry already active, skipping duplicate schedule")
            return
        }

        // 根据启动类型选择延迟序列
        val delays = when (reason) {
            LaunchReason.BOOT, LaunchReason.RECOVERY -> STARTUP_RETRY_DELAYS_MS_LONG
            LaunchReason.MANUAL, LaunchReason.NOTIFICATION -> STARTUP_RETRY_DELAYS_MS_SHORT
        }

        startupRetryJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var delayIndex = 0
            while (this.isActive) {
                if (!isLaunchStillValid() || !ServiceBootStateStore.shouldRun()) {
                    Log.i(TAG, "startup retry stopped: launch no longer valid")
                    return@launch
                }
                if (generation != launchGeneration.get()) {
                    Log.i(TAG, "startup retry stopped: generation changed, current generation=${launchGeneration.get()}, task generation=$generation")
                    return@launch
                }
                if (CleanerClient.pingBinder()) {
                    if (generation != launchGeneration.get()) {
                        Log.i(TAG, "startup retry: binder connected but generation changed, skipping config reload")
                        return@launch
                    }
                    Log.i(TAG, "startup retry: binder connected, reloading server configuration")
                    reloadServerConfiguration()
                    return@launch
                }
                val baseDelay = delays[delayIndex.coerceAtMost(delays.size - 1)]
                val jitter = random.nextInt(JITTER_MAX_MS.coerceAtLeast(0).toInt())
                val delayMs = baseDelay + jitter
                Log.i(TAG, "startup retry #${delayIndex + 1}: binder not ready, retrying in ${delayMs}ms (base=$baseDelay jitter=$jitter)")
                delay(delayMs)
                delayIndex++
            }
            Log.w(TAG, "startup retry: cancelled or completed without binder connection")
        }
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
