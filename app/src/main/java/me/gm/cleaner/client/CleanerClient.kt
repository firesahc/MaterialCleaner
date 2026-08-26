package me.gm.cleaner.client

import android.content.pm.PackageInfo
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.model.LayerStatus
import me.gm.cleaner.server.ICleanerService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class OrchestratedLayerStatus(
    val state: String = "UNAVAILABLE",
    val lastError: String? = null,
    val metrics: Map<String, String> = emptyMap(),
) {
    val isHealthy: Boolean
        get() = state == "HEALTHY"

    val isUnavailable: Boolean
        get() = state == "UNAVAILABLE"
}

data class OrchestratedRuntimeStatus(
    val health: String = "CRITICAL",
    val vfs: OrchestratedLayerStatus = OrchestratedLayerStatus(),
    val mediaProviderJavaHook: OrchestratedLayerStatus = OrchestratedLayerStatus(),
    val fuseNativeHook: OrchestratedLayerStatus = OrchestratedLayerStatus(),
    val dataBus: OrchestratedLayerStatus = OrchestratedLayerStatus(),
    val controlPlane: OrchestratedLayerStatus = OrchestratedLayerStatus(),
) {
    val isHealthy: Boolean
        get() = health == "HEALTHY"
}

object CleanerClient {
    /** 存活探测超时：超过此时长仍未返回即判定 server 假死。 */
    const val PING_TIMEOUT_MS: Long = 3_000L

    /**
     * 探测专用串行守护线程：假死时同步 Binder 调用会永久阻塞，
     * 必须与监督方隔离，避免拖垮公共线程池。
     */
    private val pingExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CleanerPingProbe").apply { isDaemon = true }
    }

    private val _serverVersionLiveData: MutableLiveData<Int> = MutableLiveData(-1)
    val serverVersionLiveData: LiveData<Int> = _serverVersionLiveData
    var serverVersion: Int
        get() = _serverVersionLiveData.value ?: -1
        private set(value) {
            _serverVersionLiveData.postValue(value)
        }

    private var binder: IBinder? = null
    var service: ICleanerService? = null
        private set
    private val DEATH_RECIPIENT: IBinder.DeathRecipient = IBinder.DeathRecipient {
        Log.w("MC/Test", "DEATH_RECIPIENT: Binder death detected! Server disconnected.")
        if (BuildConfig.DEBUG) Log.w("CleanerTest", "Binder death received! Server disconnected.")
        clearBinderState()
        ServerStateMachine.onBinderDied()
    }

    private fun clearBinderState() {
        binder = null
        service = null
        serverVersion = -1
    }

    fun pingBinder(): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d("MC/Test", "pingBinder: binder=${binder != null}, result=${binder?.pingBinder()}")
        }
        val result = binder?.pingBinder() == true
        if (BuildConfig.DEBUG) Log.d("CleanerTest", "pingBinder: result=$result")
        return result
    }

    /**
     * 带超时的存活探测。
     *
     * server 假死持锁时，同步 Binder 调用可能永久阻塞——探测放到独立
     * 守护线程上执行并限时等待，保证监督方总能获得确定性结论；
     * 超时后滞留的探测线程随进程退出回收（串行单线程，无池污染）。
     */
    fun pingBinderWithTimeout(timeoutMs: Long = PING_TIMEOUT_MS): Boolean =
        runCatching {
            CompletableFuture.supplyAsync({ pingBinder() }, pingExecutor)
                .get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

    /**
     * 服务"完全开启"统一判定。
     *
     * 仅当所有条件都满足时返回 true：
     *  1. 进程存活（[pingBinder]）
     *  2. 本次 boot 目标态要求服务运行
     *  3. Root 权限可用
     *  4. LSPosed Xposed Hooks 已连接到 MediaProvider（[HooksBridgeProvider.isMediaProviderConnected]）
     */
    fun isServiceOpen(): Boolean {
        val running = pingBinder()
        val targetRunning = ServiceBootStateStore.shouldRun()
        val rootAvailable = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        val xposedConnected = HooksBridgeProvider.isMediaProviderConnected()
        val open = running && targetRunning && rootAvailable && xposedConnected
        if (BuildConfig.DEBUG) {
            Log.d(
                "CleanerTest",
                "isServiceOpen: running=$running, targetRunning=$targetRunning, " +
                        "rootAvailable=$rootAvailable, xposedConnected=$xposedConnected → $open"
            )
        }
        return open
    }

    @Synchronized
    fun onBinderReceived(newBinder: IBinder) {
        Log.i("MC/Test", "onBinderReceived: newBinder received, currentBinder=${binder != null}")
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "onBinderReceived: newBinder=$newBinder, currentBinder=$binder")
        if (!ServiceBootStateStore.shouldRun()) {
            Log.w("MC/Test", "onBinderReceived: target stopped, rejecting binder")
            ServerStateMachine.onBinderReceived()
            return
        }
        if (binder == newBinder) {
            if (BuildConfig.DEBUG) Log.d("CleanerTest", "onBinderReceived: same binder, skipping")
            ServerStateMachine.onBinderReceived()
            return
        }
        binder?.unlinkToDeath(DEATH_RECIPIENT, 0)
        binder = newBinder
        binder?.linkToDeath(DEATH_RECIPIENT, 0)
        service = ICleanerService.Stub.asInterface(newBinder)
        serverVersion = service?.serverVersion ?: -1
        Log.i("MC/Test", "onBinderReceived: service established, serverVersion=$serverVersion")
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "onBinderReceived: service set, serverVersion=$serverVersion")
        ServerStateMachine.onBinderReceived()
    }

    fun getInstalledPackages(flags: Int): List<PackageInfo> {
        val result = service?.getInstalledPackages(flags)?.list ?: emptyList()
        if (BuildConfig.DEBUG) {
            Log.d("MC/Test", "getInstalledPackages: service=${service != null}, count=${result.size}")
        }
        return result
    }

    val mountedDirs: List<String>
        get() = try {
            if (pingBinder()) service?.mountedDirs ?: emptyList() else emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    fun getOrchestratedStatus(): OrchestratedRuntimeStatus? {
        val status = try {
            service?.orchestratedStatus
        } catch (e: Exception) {
            Log.w("MC/Test", "getOrchestratedStatus: failed", e)
            null
        } ?: return null
        return OrchestratedRuntimeStatus(
            health = status.health ?: "CRITICAL",
            vfs = status.vfs.toRuntimeLayer(),
            mediaProviderJavaHook = status.mediaProviderJavaHook.toRuntimeLayer(),
            fuseNativeHook = status.fuseNativeHook.toRuntimeLayer(),
            dataBus = status.dataBus.toRuntimeLayer(),
            controlPlane = status.controlPlane.toRuntimeLayer(),
        )
    }

    fun exit() {
        runCatching {
            service?.exit()
        }
    }

    /**
     * 重置 Binder 连接状态，在 DeadObjectException 时调用
     */
    fun resetConnection() {
        Log.w("MC/Test", "resetConnection: clearing binder and service state")
        binder?.unlinkToDeath(DEATH_RECIPIENT, 0)
        clearBinderState()
    }

    /** 通过 root shell 杀死所有 cleaner_server 进程 */
    fun killServerProcess() {
        // 身份加固：kill 前校验 /proc/<pid>/cmdline 确实包含 cleaner_server，
        // 收敛 ps 与 kill 之间 PID 被系统复用导致误杀无关进程的 TOCTOU 窗口。
        Shell.cmd(
            "for pid in \$(ps -A | grep cleaner_server | tr -s ' ' | cut -d' ' -f2); do " +
                "grep -q cleaner_server /proc/\$pid/cmdline 2>/dev/null && kill -9 \$pid 2>/dev/null; " +
                "done"
        ).exec()
    }

    /** 等待 Binder 就绪，最多重试 [maxRetries] 次，每次间隔 [delayMs] */
    suspend fun waitForBinder(maxRetries: Int = 20, delayMs: Long = 500): Boolean {
        var retries = 0
        while (!pingBinder() && retries < maxRetries) {
            delay(delayMs)
            retries++
        }
        return pingBinder()
    }

    private fun LayerStatus?.toRuntimeLayer(): OrchestratedLayerStatus {
        if (this == null) return OrchestratedLayerStatus()
        val keys = metricKeys ?: emptyArray()
        val values = metricValues ?: emptyArray()
        val metrics = keys.indices.associate { index ->
            keys[index] to values.getOrElse(index) { "" }
        }
        return OrchestratedLayerStatus(
            state = state ?: "UNAVAILABLE",
            lastError = lastError?.takeIf { it.isNotBlank() && it != "null" },
            metrics = metrics,
        )
    }
}
