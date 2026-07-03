package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.observer.FileSystemObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
import org.json.JSONObject

/**
 * 文件系统事件消费者（游标持久化版）。
 *
 * 从 [DataBus] events/filesystem 读取单文件事件，转发给 [FileSystemObserver]。
 * 消费游标持久化到 DataBus cursors/，server 重启后可续消费。
 */
object FileSystemEventConsumer {
    private const val TAG = "FileSystemEventConsumer"

    @Volatile
    private var cursor: String = ""

    /** 从 DataBus 加载持久化游标 */
    fun loadCursor() {
        cursor = DataBus.readCursor(DataBus.EVENT_FILESYSTEM)
        Log.d(TAG, "loadCursor: cursor='$cursor'")
    }

    /**
     * 拉取并消费所有未处理事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        val events = DataBus.readEvents(DataBus.EVENT_FILESYSTEM, cursor)
        if (events.isEmpty()) return 0

        val observer = ObserverManager.fastGetObserver(FileSystemObserver::class.java)
        if (observer == null) {
            Log.w(TAG, "FileSystemObserver not available, skipping ${events.size} events")
            advanceCursor()
            return 0
        }

        var consumed = 0
        for (eventJson in events) {
            try {
                val event = JSONObject(eventJson)
                val timeMillis = event.optLong("timeMillis", System.currentTimeMillis())
                val packageName = event.optString("packageName", "")
                val path = event.optString("path", "")
                val flags = event.optInt("flags", 0)

                if (packageName.isEmpty() || path.isEmpty()) continue

                observer.onEvent(timeMillis, packageName, path, flags)
                consumed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume event", e)
            }
        }

        advanceCursor()

        if (consumed > 0) {
            Log.d(TAG, "Consumed $consumed events, cursor='$cursor'")
        }
        return consumed
    }

    private fun advanceCursor() {
        val lastFile = DataBus.getLastEventFilename(DataBus.EVENT_FILESYSTEM)
        if (lastFile.isNotEmpty()) {
            cursor = lastFile
            DataBus.writeCursor(DataBus.EVENT_FILESYSTEM, cursor)
        }
    }
}
