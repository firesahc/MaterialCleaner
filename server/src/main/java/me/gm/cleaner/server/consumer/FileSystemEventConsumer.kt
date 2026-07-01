package me.gm.cleaner.server.consumer

import android.util.Log
import me.gm.cleaner.dao.policy.DataBus
import me.gm.cleaner.server.CleanerServer
import me.gm.cleaner.server.observer.FileSystemObserver
import me.gm.cleaner.server.observer.ObserverManager
import org.json.JSONObject

/**
 * 文件系统事件消费者。
 *
 * 从 [DataBus] events/filesystem 队列读取事件，
 * 转发给 [FileSystemObserver.onEvent]，并记录已消费序号。
 *
 * ## 去重策略
 * 仅消费 seq > lastConsumedSeq 的事件。
 * 消费失败的事件不会被重复消费（at-most-once）。
 *
 * ## 冷启动
 * 首次消费从 seq=0 开始，读取所有已有事件。
 */
object FileSystemEventConsumer {
    private const val TAG = "FileSystemEventConsumer"

    @Volatile
    private var lastConsumedSeq: Long = -1L

    /**
     * 从 DataBus 拉取并消费所有未处理的事件。
     * 应由 [LayerOrchestrator] 周期性调用。
     */
    fun pollAndConsume() {
        val events = DataBus.readEvents(DataBus.EVENT_FILESYSTEM, lastConsumedSeq)
        if (events.isEmpty()) return

        val observer = ObserverManager.fastGetObserver(FileSystemObserver::class.java)
        if (observer == null) {
            Log.w(TAG, "FileSystemObserver not available, skipping ${events.size} events")
            lastConsumedSeq += events.size  // skip unprocessable events
            return
        }

        var consumed = 0
        for (eventJson in events) {
            try {
                val event = JSONObject(eventJson)
                val timeMillis = event.optLong("timeMillis", System.currentTimeMillis())
                val packageName = event.optString("packageName", "")
                val path = event.optString("path", "")
                val flags = event.optInt("flags", 0)

                if (packageName.isEmpty() || path.isEmpty()) {
                    Log.w(TAG, "Skipping event with empty packageName or path")
                    lastConsumedSeq++
                    continue
                }

                observer.onEvent(timeMillis, packageName, path, flags)
                lastConsumedSeq++
                consumed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume event", e)
                lastConsumedSeq++  // skip broken events
            }
        }

        if (consumed > 0) {
            Log.d(TAG, "Consumed $consumed events, lastSeq=$lastConsumedSeq")
        }
    }
}
