package me.gm.cleaner.client

import android.content.pm.PackageInfo
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.server.ICleanerService

object CleanerClient {
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
        Log.d("MC/Test", "pingBinder: binder=${binder != null}, result=${binder?.pingBinder()}")
        val result = binder?.pingBinder() == true
        if (BuildConfig.DEBUG) Log.d("CleanerTest", "pingBinder: result=$result")
        return result
    }

    /**
     * 服务"完全开启"统一判定。
     *
     * 仅当所有条件都满足时返回 true：
     *  1. 进程存活（[pingBinder]）
     *  2. 用户未手动停止（[ServicePreferences.isServiceManuallyStopped] == false）
     *  3. Root 权限可用
     *  4. LSPosed Xposed Hooks 已连接到 MediaProvider（[HooksBridgeProvider.isMediaProviderConnected]）
     */
    fun isServiceOpen(): Boolean {
        val running = pingBinder()
        val notManuallyStopped = !ServicePreferences.isServiceManuallyStopped
        val rootAvailable = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        val xposedConnected = HooksBridgeProvider.isMediaProviderConnected()
        val open = running && notManuallyStopped && rootAvailable && xposedConnected
        if (BuildConfig.DEBUG) {
            Log.d(
                "CleanerTest",
                "isServiceOpen: running=$running, notManuallyStopped=$notManuallyStopped, " +
                        "rootAvailable=$rootAvailable, xposedConnected=$xposedConnected → $open"
            )
        }
        return open
    }

    @Synchronized
    fun onBinderReceived(newBinder: IBinder) {
        Log.i("MC/Test", "onBinderReceived: newBinder received, currentBinder=${binder != null}")
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "onBinderReceived: newBinder=$newBinder, currentBinder=$binder")
        if (binder == newBinder) {
            if (BuildConfig.DEBUG) Log.d("CleanerTest", "onBinderReceived: same binder, skipping")
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
        Log.d("MC/Test", "getInstalledPackages: service=${service != null}, count=${result.size}")
        return result
    }

    val mountedDirs: List<String>
        get() = try {
            if (pingBinder()) service?.mountedDirs ?: emptyList() else emptyList()
        } catch (e: SecurityException) {
            emptyList()
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
        Shell.cmd("ps -A | grep cleaner_server | tr -s ' ' | cut -d' ' -f2 | while read pid; do kill -9 \$pid 2>/dev/null; done").exec()
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
}
