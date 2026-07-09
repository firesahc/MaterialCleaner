package me.gm.cleaner.client

import android.content.Context
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

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val launchGeneration = AtomicLong(0)

    @Volatile
    private var appContext: Context? = null
    private var crashCount = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        ServiceBootStateStore.ensureInitialized(context)
        if (BuildConfig.DEBUG) Log.d(TAG, "initialized")
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

        _state.value = ServerState.STARTING
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
