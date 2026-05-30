package me.gm.cleaner.client

import android.content.pm.PackageInfo
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.gm.cleaner.BuildConfig
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
        binder = null
        service = null
        serverVersion = -1
    }
}
