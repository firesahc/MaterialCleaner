package me.gm.cleaner.client

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.common.err.ErrorEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * cleaner_server 状态机。
 *
 * 职责边界：
 * - 手动/通知启停入口
 * - UI 状态同步
 * - Binder 死亡后的失败恢复协调
 * - 通过启动 generation 让停止操作失效正在进行的启动流程
 *
 * 自动启动入口只属于 BootCompleteReceiver，状态机不读取 start_on_boot。
 */
enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED
}

enum class StartSource { MANUAL, NOTIFICATION }
enum class StopSource { USER }

object ServerStateMachine {
    private const val TAG = "MC/StateMachine"
    private const val MAX_RECOVERY_RETRIES = 5
    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val WATCHDOG_FAILURE_THRESHOLD = 3

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val launchGeneration = AtomicLong(0)

    @Volatile
    private var appContext: Context? = null
    private var crashCount = 0

    /** 假死监督的连续超时计数；任一次成功探测即清零。 */
    @Volatile
    private var unresponsiveCount = 0

    @Volatile
    private var watchdogStarted = false

    fun init(context: Context) {
        appContext = context.applicationContext
        ServiceBootStateStore.ensureInitialized(context)
        startWatchdog()
        if (BuildConfig.DEBUG) Log.d(TAG, "initialized")
    }

    /**
     * 假死监督循环：进程存活但 Binder 无响应的失效模式不会触发
     * Binder 死亡回调，必须由周期性带超时探测主动发现。
     * 仅当目标态为运行中且状态机已进入 RUNNING 时才实际探测。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                runCatching { ensureResponsive() }
                    .onFailure { Log.e(TAG, "watchdog cycle failed", it) }
            }
        }
    }

    fun ensureResponsive() {
        // 启动中的慢初始化由 launch 流程自身的 binder timeout 兜底，
        // watchdog 只监督已进入 RUNNING 的稳态服务。
        if (!ServiceBootStateStore.shouldRun()) return
        if (_state.value != ServerState.RUNNING) return

        if (CleanerClient.pingBinderWithTimeout()) {
            unresponsiveCount = 0
            return
        }

        unresponsiveCount++
        Log.w(
            TAG,
            "watchdog: server unresponsive ($unresponsiveCount/$WATCHDOG_FAILURE_THRESHOLD)"
        )
        if (unresponsiveCount < WATCHDOG_FAILURE_THRESHOLD) return

        // 连续超时判定假死：强停并走既有恢复流程重新拉起。
        unresponsiveCount = 0
        val ctx = appContext ?: return
        Log.e(TAG, "watchdog: server presumed hung, force restarting")
        ClientErrorJournal.record(
            ErrorEvent(
                code = ErrorCodes.SUP_WATCHDOG_RESTART,
                subject = "watchdog",
                atElapsed = SystemClock.elapsedRealtime(),
                detail = "ping timeout x$WATCHDOG_FAILURE_THRESHOLD",
            )
        )
        scope.launch {
            CleanerClient.killServerProcess()
            delay(backoffMillis(1))
            recoverIfTargetRunning(ctx, LaunchReason.RECOVERY)
        }
    }

    val isServiceOpen: Boolean
        get() = _state.value == ServerState.RUNNING &&
                ServiceBootStateStore.shouldRun() &&
                runCatching { Shell.getShell().isRoot }.getOrDefault(false) &&
                HooksBridgeProvider.isMediaProviderConnected()

    /**
     * 兼容旧 UI 命名：仅表示本次 boot 内用户手动停止。
     */
    val isSessionManuallyStopped: Boolean
        get() = ServiceBootStateStore.isStopped() &&
                ServiceBootStateStore.source == BootTargetSource.MANUAL

    suspend fun start(source: StartSource, context: Context): Boolean = withContext(Dispatchers.IO) {
        ServiceBootStateStore.ensureInitialized(context)
        ServiceBootStateStore.setTarget(BootTargetState.RUNNING, source.toBootTargetSource())
        crashCount = 0

        val token = launchGeneration.incrementAndGet()
        _state.value = ServerState.STARTING
        if (BuildConfig.DEBUG) Log.i(TAG, "start($source): token=$token")

        val success = CleanerServerLauncher.launch(context, source.toLaunchReason()) {
            isLaunchValid(token)
        }
        finishLaunch(token, success)
    }

    suspend fun recoverIfTargetRunning(
        context: Context,
        reason: LaunchReason = LaunchReason.RECOVERY
    ): Boolean = withContext(Dispatchers.IO) {
        ServiceBootStateStore.ensureInitialized(context)
        if (!ServiceBootStateStore.shouldRun()) {
            synchronizeWithTarget()
            return@withContext false
        }

        if (CleanerServerLauncher.isServerAlive()) {
            _state.value = ServerState.RUNNING
            crashCount = 0
            return@withContext true
        }

        if (_state.value == ServerState.STARTING) {
            if (BuildConfig.DEBUG) Log.d(TAG, "recover($reason): already STARTING")
            return@withContext false
        }

        val token = launchGeneration.incrementAndGet()
        _state.value = ServerState.STARTING
        if (BuildConfig.DEBUG) Log.i(TAG, "recover($reason): token=$token")

        val success = CleanerServerLauncher.launch(context, reason) {
            isLaunchValid(token)
        }
        finishLaunch(token, success)
    }

    suspend fun stop(source: StopSource) = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.i(TAG, "stop($source) from state=${_state.value}")
        launchGeneration.incrementAndGet()
        ServiceBootStateStore.setTarget(BootTargetState.STOPPED, BootTargetSource.MANUAL)
        crashCount = 0
        _state.value = ServerState.STOPPED
        CleanerServerLauncher.stopCurrentServer()
    }

    fun synchronizeWithTarget() {
        val shouldRun = ServiceBootStateStore.shouldRun()
        val alive = CleanerServerLauncher.isServerAlive()
        when {
            shouldRun && alive -> {
                _state.value = ServerState.RUNNING
                crashCount = 0
            }
            shouldRun -> {
                if (_state.value == ServerState.RUNNING) {
                    _state.value = ServerState.FAILED
                }
            }
            else -> {
                if (alive) {
                    launchGeneration.incrementAndGet()
                    CleanerClient.resetConnection()
                    scope.launch {
                        CleanerClient.killServerProcess()
                    }
                }
                _state.value = ServerState.STOPPED
                crashCount = 0
            }
        }
    }

    fun onBinderReceived() {
        if (!ServiceBootStateStore.shouldRun()) {
            Log.w(TAG, "onBinderReceived: target is ${ServiceBootStateStore.targetState}, rejecting binder")
            launchGeneration.incrementAndGet()
            _state.value = ServerState.STOPPED
            crashCount = 0
            CleanerClient.resetConnection()
            scope.launch {
                CleanerClient.killServerProcess()
            }
            return
        }

        if (BuildConfig.DEBUG) Log.i(TAG, "onBinderReceived: state=${_state.value} -> RUNNING")
        _state.value = ServerState.RUNNING
        crashCount = 0
    }

    fun onBinderDied() {
        if (!ServiceBootStateStore.shouldRun()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "onBinderDied: target stopped, ignored")
            _state.value = ServerState.STOPPED
            crashCount = 0
            return
        }

        crashCount++
        Log.w(TAG, "onBinderDied: crash #$crashCount, max=$MAX_RECOVERY_RETRIES")
        if (crashCount > MAX_RECOVERY_RETRIES) {
            _state.value = ServerState.FAILED
            return
        }

        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "onBinderDied: appContext null, cannot recover")
            _state.value = ServerState.FAILED
            return
        }

        // 注意：此处不得提前置 STARTING——recoverIfTargetRunning 会把
        // STARTING 视为"已有恢复进行中"而自我放弃（历史缺陷：死亡后
        // 永久卡在 STARTING、所有后续恢复被拒）。状态转换由恢复函数
        // 通过 STARTING 去重检查后自行完成。
        scope.launch {
            delay(backoffMillis(crashCount))
            recoverIfTargetRunning(ctx, LaunchReason.RECOVERY)
        }
    }

    fun onXposedConnected(changed: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "onXposedConnected: $changed")
    }

    fun reset() {
        if (BuildConfig.DEBUG) Log.i(TAG, "reset")
        crashCount = 0
        _state.value = if (ServiceBootStateStore.shouldRun()) ServerState.FAILED else ServerState.STOPPED
    }

    private fun finishLaunch(token: Long, success: Boolean): Boolean {
        if (!isLaunchValid(token)) {
            if (BuildConfig.DEBUG) Log.i(TAG, "finishLaunch: token=$token invalid")
            _state.value = ServerState.STOPPED
            CleanerServerLauncher.stopCurrentServer()
            return false
        }

        return if (success) {
            _state.value = ServerState.RUNNING
            crashCount = 0
            true
        } else {
            _state.value = if (ServiceBootStateStore.shouldRun()) ServerState.FAILED else ServerState.STOPPED
            false
        }
    }

    private fun isLaunchValid(token: Long): Boolean =
        launchGeneration.get() == token && ServiceBootStateStore.shouldRun()

    private fun backoffMillis(crash: Int): Long {
        val seconds = when {
            crash <= 0 -> 1L
            crash > 5 -> 30L
            else -> (1L shl (crash - 1)).coerceAtMost(30L)
        }
        return seconds * 1000
    }

    private fun StartSource.toBootTargetSource(): BootTargetSource = when (this) {
        StartSource.MANUAL -> BootTargetSource.MANUAL
        StartSource.NOTIFICATION -> BootTargetSource.NOTIFICATION
    }

    private fun StartSource.toLaunchReason(): LaunchReason = when (this) {
        StartSource.MANUAL -> LaunchReason.MANUAL
        StartSource.NOTIFICATION -> LaunchReason.NOTIFICATION
    }
}
