package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.observer.FileSystemObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 文件系统事件消费者（游标持久化版）。
 *
 * 从 [DataBus] events/filesystem 读取单文件事件，转发给 [FileSystemObserver]。
 * 消费游标持久化到 DataBus cursors/，server 重启后可续消费。
 */
object FileSystemEventConsumer {
    private const val TAG = "FileSystemEventConsumer"

    /** consumed/ 目录归档保留时长：24 小时 */
    private const val CONSUMED_TTL_MS = 24 * 60 * 60 * 1000L

    /** consumed/ 目录最大文件数上限，超过时触发清理最旧文件 */
    private const val CONSUMED_MAX_FILES = 10000

    @Volatile
    private var cursor: String = ""

    /** 最近一次检查到的 signal 时间戳，用于熔断无事件轮询 */
    @Volatile
    private var lastSignalTimestamp: Long = 0L

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
        // 信号熔断：signal 未变化表示无新事件，跳过文件系统扫描（listFiles）以节省 tmpfs I/O
        val signalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) return 0
        lastSignalTimestamp = signalTime

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

                // 归档到 consumed/ 目录，保留事件记录供审计
                // 直接文件写入（不含序列号），避免浪费事件序列号计数器
                archiveEvent(eventJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume event", e)
            }
        }

        advanceCursor()

        if (consumed > 0) {
            Log.d(TAG, "Consumed $consumed events, cursor='$cursor'")
        }

        // 定期清理过期归档事件
        cleanupConsumed()
        return consumed
    }

    /**
     * 清理 consumed/ 目录中超过 TTL 的归档事件文件，避免 tmpfs 空间占满。
     * 阈值双重控制：过期时间（CONSUMED_TTL_MS）+ 最大文件数（CONSUMED_MAX_FILES）。
     */
    private fun cleanupConsumed() {
        val consumedDir = File(DataBus.BUS_ROOT, "events/consumed")
        if (!consumedDir.exists()) return

        val files = consumedDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name }  // 按文件名排序（内含时间戳）
            ?: return

        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        val thresholdTime = now - CONSUMED_TTL_MS

        var deleted = 0
        // 第一遍：删除超过 TTL 的文件
        for (file in files) {
            if (file.lastModified() < thresholdTime && file.delete()) {
                deleted++
            }
        }
        // 第二遍：如果仍超过最大文件数，从最旧的开始删除
        val remaining = files.size - deleted
        if (remaining > CONSUMED_MAX_FILES) {
            val excess = remaining - CONSUMED_MAX_FILES
            val sorted = files.filter { it.exists() }.sortedBy { it.name }
            for (i in 0 until minOf(excess, sorted.size)) {
                if (sorted[i].delete()) deleted++
            }
        }

        if (deleted > 0) {
            Log.d(TAG, "cleanupConsumed: deleted $deleted files, remaining=${files.size - deleted}")
        }
    }

    private fun advanceCursor() {
        val lastFile = DataBus.getLastEventFilename(DataBus.EVENT_FILESYSTEM)
        if (lastFile.isNotEmpty()) {
            cursor = lastFile
            DataBus.writeCursor(DataBus.EVENT_FILESYSTEM, cursor)
        }
    }

    /**
     * 归档已消费事件到 consumed/ 目录。
     * 使用时间戳+随机数命名文件（不含事件序列号），避免浪费 DataBus 全局序列号计数器。
     */
    private fun archiveEvent(content: String) {
        val consumedDir = File(DataBus.BUS_ROOT, "events/consumed")
        if (!consumedDir.exists() && !consumedDir.mkdirs()) return

        val now = System.currentTimeMillis()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = "$now-$rand.json"
        val tmpFile = File(consumedDir, "$filename.tmp")
        val targetFile = File(consumedDir, filename)

        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Failed to rename consumed archive: $filename")
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive consumed event", e)
            tmpFile.delete()
        }
    }
}
