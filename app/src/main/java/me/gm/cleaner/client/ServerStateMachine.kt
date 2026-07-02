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
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.starter.Starter

/**
 * 服务进程（cleaner_server）的 4 状态状态机。
 *
 * 管理服务器进程的启/停/崩溃恢复。
 * 不关心 Xposed 连接状态——那是 [XposedConnectionState] 的职责。
 *
 * 状态转换：
 *   STOPPED ──[start(source)]──→ STARTING ──[Binder收到]──→ RUNNING
 *      ▲                             │                        │
 *      │                        [超时/失败]              [Binder意外断]
 *      │                             ▼                        │
 *      │                           FAILED ←────── 重试耗尽 ───┘
 *      │                               │
 *      └───────[start(MANUAL)]─────────┘
 *
 * stop(USER) → 任何状态 → STOPPED（永远不触发重试）
 */
enum class ServerState {
    /** 手动停止 / 初始状态 */
    STOPPED,
    /** Shell.cmd 已发送，等待 Binder */
    STARTING,
    /** Binder 已通，AIDL 可调用 */
    RUNNING,
    /** 启动失败或崩溃超过最大重试次数 */
    FAILED
}

enum class StartSource { MANUAL, AUTO, BOOT, NOTIFICATION }
enum class StopSource { USER }

object ServerStateMachine {

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var crashCount = 0
    private var lastStartSource = StartSource.AUTO
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 用于 FAILED 状态下区分"是否曾经成功运行过" */
    private var hasEverRun = false

    // ── 初始化 ──

    /**
     * 在 Application.onCreate() 中调用。
     * 传入 Application Context 供自动重试时使用。
     */
    fun init(context: Context) {
        appContext = context
        if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "initialized")
    }

    /** 当前是否应认为"服务完全开启"（用于 UI 按钮标签） */
    val isServiceOpen: Boolean
        get() = _state.value == ServerState.RUNNING
                && !ServicePreferences.isServiceManuallyStopped
                && runCatching { Shell.getShell().isRoot }.getOrDefault(false)
                && HooksBridgeProvider.isMediaProviderConnected()

    // ── 统一启动 ──

    /**
     * 启动服务器进程。
     * @param source 启动来源决定前置检查和重试策略
     * @param context 用于 writeDataFiles 的 Context（device-protected storage）
     * @return true=启动成功并进入 RUNNING
     */
    suspend fun start(source: StartSource, context: Context): Boolean = withContext(Dispatchers.IO) {
        val current = _state.value
        if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "start($source) from state=$current")

        // ── 前置检查 ──

        // 已在运行 → 跳过
        if (current == ServerState.RUNNING) {
            if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "start($source): already RUNNING, skip")
            return@withContext true
        }

        // 启动中 → 等待调用方 await 后续状态
        if (current == ServerState.STARTING) {
            if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "start($source): already STARTING, skip")
            return@withContext false
        }

        // 手动停止标记检查（MANUAL 来源例外）
        if (ServicePreferences.isServiceManuallyStopped && source != StartSource.MANUAL) {
            if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "start($source): skipped, manually stopped")
            return@withContext false
        }

        // Root 权限检查
        val isRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!isRoot) {
            Log.w("MC/StateMachine", "start($source): no root access")
            _state.value = ServerState.FAILED
            return@withContext false
        }

        // ── 状态转换 ──

        lastStartSource = source
        if (source == StartSource.MANUAL) {
            // 用户手动启动：清除停止标记 + 重置计数器
            ServicePreferences.isServiceManuallyStopped = false
            crashCount = 0
        }
        _state.value = ServerState.STARTING

        // ── 执行启动 ──

        try {
            // 1) 杀旧进程，确保干净
            CleanerClient.killServerProcess()
            delay(1000)

            // 2) 写数据文件 + 执行 Shell 命令
            Starter.writeDataFiles(context)
            val result = Shell.cmd(Starter.command).exec()
            if (!result.isSuccess) {
                Log.e("MC/StateMachine", "start($source): shell command failed: ${result.err.joinToString()}")
                _state.value = ServerState.FAILED
                return@withContext false
            }

            // 3) 等待 Binder 就绪（最长 ~10s = 20×500ms）
            if (!CleanerClient.waitForBinder()) {
                Log.e("MC/StateMachine", "start($source): binder timeout")
                _state.value = ServerState.FAILED
                return@withContext false
            }

            // 4) 重载配置到服务器（逐个 try，单次失败不阻塞后续）
            val svc = CleanerClient.service
            if (svc != null) {
                runCatching { svc.notifyPreferencesChanged() }
                    .onFailure { Log.w("MC/StateMachine", "notifyPreferencesChanged failed", it) }
                runCatching { svc.notifySrChanged() }
                    .onFailure { Log.w("MC/StateMachine", "notifySrChanged failed", it) }
                runCatching { svc.notifyReadOnlyChanged() }
                    .onFailure { Log.w("MC/StateMachine", "notifyReadOnlyChanged failed", it) }
                val packages = ServicePreferences.srPackages.toTypedArray()
                if (packages.isNotEmpty()) {
                    runCatching { svc.remount(packages) }
                        .onFailure { Log.w("MC/StateMachine", "remount failed", it) }
                }
            }

            _state.value = ServerState.RUNNING
            crashCount = 0
            hasEverRun = true
            if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "start($source) → RUNNING")
            return@withContext true

        } catch (e: Exception) {
            Log.e("MC/StateMachine", "start($source) exception", e)
            _state.value = ServerState.FAILED
            return@withContext false
        }
    }

    // ── 统一停止 ──

    /**
     * 停止服务器进程。
     * 三层清理：优雅退出 → 释放 Binder → root shell 杀死。
     */
    suspend fun stop(source: StopSource) {
        if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "stop($source) from state=${_state.value}")
        _state.value = ServerState.STOPPED

        ServicePreferences.isServiceManuallyStopped = true

        CleanerClient.exit()
        CleanerClient.resetConnection()
        CleanerClient.killServerProcess()
    }

    // ── 事件驱动 ──

    /**
     * Binder 到达时调用（从 CleanerClient.onBinderReceived）。
     *
     * 处理两种场景：
     *   1. STARTING → RUNNING（正常启动成功）
     *   2. STOPPED → RUNNING（进程重启但服务器还在）
     */
    fun onBinderReceived() {
        when (_state.value) {
            ServerState.STARTING -> {
                if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "onBinderReceived: STARTING → RUNNING")
                _state.value = ServerState.RUNNING
                crashCount = 0
                hasEverRun = true
            }
            ServerState.STOPPED -> {
                // 进程重启场景：Binder 意外到达但服务器还在
                val manuallyStopped = runCatching { ServicePreferences.isServiceManuallyStopped }
                    .getOrDefault(true) // App.onCreate 未完成时默认为手动停止
                if (!manuallyStopped) {
                    if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "onBinderReceived: STOPPED → RUNNING (process restart)")
                    _state.value = ServerState.RUNNING
                    hasEverRun = true
                }
            }
            else -> { /* 非预期状态下的 Binder 到达 → 忽略 */ }
        }
    }

    /**
     * Binder 死亡时调用（从 CleanerClient.DEATH_RECIPIENT）。
     *
     * - RUNNING 状态死亡 → 计为一次崩溃，按策略自动重试
     * - STOPPED 状态死亡 → 正常停止后的残留回调 → 忽略
     */
    fun onBinderDied() {
        when (_state.value) {
            ServerState.RUNNING -> {
                crashCount++
                val maxRetries = if (lastStartSource == StartSource.MANUAL) 5 else 3
                Log.w("MC/StateMachine", "onBinderDied: crash #$crashCount, max=$maxRetries")

                if (crashCount <= maxRetries) {
                    // 自动重试：异步执行 start()（使用 appContext）
                    val ctx = appContext
                    if (ctx != null) {
                        _state.value = ServerState.STARTING  // 立即反映重试状态
                        scope.launch {
                            delay(backoffMillis(crashCount))
                            start(lastStartSource, ctx)
                        }
                    } else {
                        // appContext 尚未初始化 → 无法自动重试
                        Log.e("MC/StateMachine", "onBinderDied: appContext null, cannot auto-retry")
                        _state.value = ServerState.FAILED
                    }
                } else {
                    Log.e("MC/StateMachine", "onBinderDied: max retries reached")
                    _state.value = ServerState.FAILED
                }
            }
            ServerState.STOPPED -> {
                // 正常停止后的残留 DEATH_RECIPIENT → 忽略
                if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "onBinderDied: STOPPED, ignored")
            }
            else -> {
                if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "onBinderDied: state=${_state.value}, ignored")
            }
        }
    }

    /**
     * Xposed 连接状态变化通知——仅用于日志记录。
     * 状态机不据此改变自身状态。
     */
    fun onXposedConnected(changed: Boolean) {
        if (BuildConfig.DEBUG) Log.d("MC/StateMachine", "onXposedConnected: $changed")
    }

    // ── 工具 ──

    /** 从 FAILED 恢复到 STOPPED，重置计数器 */
    fun reset() {
        if (BuildConfig.DEBUG) Log.i("MC/StateMachine", "reset")
        crashCount = 0
        _state.value = ServerState.STOPPED
    }

    /** 指数退避：第 n 次崩溃后等待 2^n 秒（上限 30s） */
    private fun backoffMillis(crash: Int): Long {
        val seconds = when {
            crash <= 0 -> 1L
            crash > 5 -> 30L
            else -> (1L shl (crash - 1)).coerceAtMost(30L)
        }
        return seconds * 1000
    }
}
