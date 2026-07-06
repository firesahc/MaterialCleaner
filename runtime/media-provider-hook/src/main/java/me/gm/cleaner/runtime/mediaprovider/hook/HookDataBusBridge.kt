package me.gm.cleaner.runtime.mediaprovider.hook

import android.os.RemoteException
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.server.ICleanerServerCallback

/**
 * MediaProvider 进程访问 DataBus 的统一入口。
 *
 * 设备实测表明，MediaProvider SELinux 域可能无法直接访问
 * /data/local/tmp/cleaner/bus，因此优先通过 root server 注入的 Binder
 * callback 读取快照和 signal 时间戳。直接文件读取只作为兜底路径保留。
 */
object HookDataBusBridge {
    private const val TAG = "HookDataBusBridge"

    @Volatile
    private var callback: ICleanerServerCallback? = null

    fun setCallback(value: ICleanerServerCallback?) {
        callback = value
    }

    fun readSnapshot(name: String): String? {
        val remote = callback
        if (remote != null) {
            try {
                return remote.readDataBusSnapshot(name)?.takeIf { it.isNotEmpty() }
            } catch (e: RemoteException) {
                Log.w(TAG, "readSnapshot via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "readSnapshot via server failed: $name", e)
            }
            return null
        }
        return DataBus.readSnapshot(name)
    }

    fun getSignalTimestamp(name: String): Long {
        val remote = callback
        if (remote != null) {
            try {
                return remote.getDataBusSignalTimestamp(name)
            } catch (e: RemoteException) {
                Log.w(TAG, "getSignalTimestamp via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "getSignalTimestamp via server failed: $name", e)
            }
            return 0L
        }
        return DataBus.getSignalTimestamp(name)
    }

    fun writeEvent(queue: String, content: String): Long {
        val remote = callback
        if (remote != null) {
            try {
                val seq = remote.writeDataBusEvent(queue, content)
                if (seq >= 0L) return seq
            } catch (e: RemoteException) {
                Log.w(TAG, "writeEvent via server failed: $queue", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeEvent via server failed: $queue", e)
            }
        }
        return DataBus.writeEvent(queue, content)
    }

    fun writeLease(category: String, key: String, content: String): Boolean {
        val remote = callback
        if (remote != null) {
            try {
                if (remote.writeDataBusLease(category, key, content)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "writeLease via server failed: $category/$key", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeLease via server failed: $category/$key", e)
            }
        }
        return DataBus.writeLease(category, key, content)
    }

    fun writeSnapshot(name: String, content: String): Boolean {
        val remote = callback
        if (remote != null) {
            try {
                if (remote.writeDataBusSnapshot(name, content)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "writeSnapshot via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "writeSnapshot via server failed: $name", e)
            }
        }
        return DataBus.ensureInitialized() && DataBus.writeSnapshot(name, content)
    }

    fun signal(name: String): Boolean {
        val remote = callback
        if (remote != null) {
            try {
                if (remote.signalDataBus(name)) return true
            } catch (e: RemoteException) {
                Log.w(TAG, "signal via server failed: $name", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "signal via server failed: $name", e)
            }
        }
        return DataBus.signal(name)
    }
}
