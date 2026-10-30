package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.server.ICleanerServerCallback

/**
 * MediaProvider process 的 DataBus 访问结果。
 *
 * `Available(null)` 表示 server 已成功响应，但该快照当前不存在；
 * `Unavailable` 表示 callback 缺失或 Binder 调用失败。两种情况不能混为一谈。
 */
internal sealed class DataBusBridgeResult<out T> {
    data class Available<T>(val value: T) : DataBusBridgeResult<T>()
    data class Unavailable(val operation: String, val reason: String) : DataBusBridgeResult<Nothing>()
}

/** 只通过显式注入的 server callback 调用，不在 callback 缺失时猜测其他访问路径。 */
internal inline fun <C, T> callDataBusCallback(
    callback: C?,
    operation: String,
    call: (C) -> T,
): DataBusBridgeResult<T> {
    if (callback == null) {
        return DataBusBridgeResult.Unavailable(operation, "callback_missing")
    }
    return try {
        DataBusBridgeResult.Available(call(callback))
    } catch (e: Exception) {
        DataBusBridgeResult.Unavailable(operation, e.javaClass.simpleName)
    }
}

/**
 * MediaProvider 进程访问 DataBus 的统一入口。
 *
 * MediaProvider SELinux 域可能无法访问 `/data/local/tmp/cleaner/bus`，因此此对象
 * 只通过 cleaner_server 注入的 Binder callback 访问 DataBus。callback 不可用时
 * 保留明确的 unavailable 结果，不直接访问共享目录，也不把失败折叠为空快照/零时间戳。
 */
object HookDataBusBridge {
    private const val TAG = "HookDataBusBridge"
    private val hookWritableSnapshots = setOf(
        DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS,
    )
    private val hookWritableSignals = setOf(
        DataBusProtocol.SIGNAL_FILESYSTEM_EVENTS_CHANGED,
        DataBusProtocol.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED,
        DataBusProtocol.SIGNAL_QUERY_SESSION_LEASES_CHANGED,
        DataBusProtocol.SIGNAL_NATIVE_HOOK_STATUS_CHANGED,
    )

    @Volatile
    private var callback: ICleanerServerCallback? = null

    fun setCallback(value: ICleanerServerCallback?) {
        callback = value
    }

    internal fun readSnapshot(name: String): DataBusBridgeResult<String?> =
        reportUnavailable("readSnapshot:$name", callDataBusCallback(callback, "readSnapshot:$name") {
            val value = it.readDataBusSnapshot(name)
            if (value.isNullOrEmpty()) null else value
        })

    internal fun getSignalTimestamp(name: String): DataBusBridgeResult<Long> =
        reportUnavailable("getSignalTimestamp:$name", callDataBusCallback(callback, "getSignalTimestamp:$name") {
            it.getDataBusSignalTimestamp(name)
        })

    /** 返回 -1 表示事件未被 server 确认写入；不改走共享目录写入。 */
    fun writeEvent(queue: String, content: String): Long = when (
        val result = reportUnavailable(
            "writeEvent:$queue",
            callDataBusCallback(callback, "writeEvent:$queue") {
                it.writeDataBusEvent(queue, content)
            },
        )
    ) {
        is DataBusBridgeResult.Available -> result.value.takeIf { it >= 0L } ?: -1L
        is DataBusBridgeResult.Unavailable -> -1L
    }

    fun writeLease(category: String, key: String, content: String): Boolean {
        val result = reportUnavailable(
            "writeLease:$category",
            callDataBusCallback(callback, "writeLease:$category") {
                it.writeDataBusLease(category, key, content)
            },
        )
        return result is DataBusBridgeResult.Available && result.value
    }

    fun writeSnapshot(name: String, content: String): Boolean {
        if (name !in hookWritableSnapshots) {
            Log.w(TAG, "Rejected hook snapshot write: $name")
            return false
        }
        val result = reportUnavailable(
            "writeSnapshot:$name",
            callDataBusCallback(callback, "writeSnapshot:$name") {
                it.writeDataBusSnapshot(name, content)
            },
        )
        return result is DataBusBridgeResult.Available && result.value
    }

    fun signal(name: String): Boolean {
        if (name !in hookWritableSignals) {
            Log.w(TAG, "Rejected hook signal write: $name")
            return false
        }
        val result = reportUnavailable(
            "signal:$name",
            callDataBusCallback(callback, "signal:$name") { it.signalDataBus(name) },
        )
        return result is DataBusBridgeResult.Available && result.value
    }

    private fun <T> reportUnavailable(
        operation: String,
        result: DataBusBridgeResult<T>,
    ): DataBusBridgeResult<T> {
        if (result is DataBusBridgeResult.Unavailable) {
            Log.w(TAG, "$operation unavailable: ${result.reason}")
        }
        return result
    }
}
