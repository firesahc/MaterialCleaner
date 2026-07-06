package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import org.json.JSONObject

/**
 * 重定向提示事件消费者（边界修正 + 游标持久化版）。
 *
 * - 只负责解析、TTL 检查、denylist 过滤、诊断日志
 * - 实际的 UI 广播通过控制面方法 [CleanerServer.showRedirectNotice] 触发
 * - 不直接操作 android.content.Intent（避免 Kotlin stub 遮蔽问题）
 * - 消费游标持久化，server 重启后可续消费
 */
object RedirectNoticeConsumer {
    private const val TAG = "RedirectNoticeConsumer"
    private const val EVENT_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var server: CleanerServer? = null

    @Volatile
    private var cursor: String = ""

    @Volatile
    private var lastSignalTimestamp: Long = 0L

    fun bind(server: CleanerServer) {
        this.server = server
    }

    fun loadCursor() {
        cursor = DataBus.readCursor(DataBus.EVENT_REDIRECT_NOTICE)
        Log.d(TAG, "loadCursor: cursor='$cursor'")
    }

    /**
     * 拉取并消费未处理的提示事件。
     * @return 消费的事件数量
     */
    fun pollAndConsume(): Int {
        val srv = server ?: return 0
        val signalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED)
        if (signalTime <= lastSignalTimestamp && lastSignalTimestamp > 0) return 0
        lastSignalTimestamp = signalTime

        val events = DataBus.readEventFiles(DataBus.EVENT_REDIRECT_NOTICE, cursor)
        if (events.isEmpty()) return 0

        var consumed = 0
        var skipped = 0
        var failed = false
        for (eventFile in events) {
            try {
                val eventJson = eventFile.content
                val event = JSONObject(eventJson)
                val timeMillis = event.optLong("timeMillis", 0L)

                // TTL 检查
                if (timeMillis > 0 && System.currentTimeMillis() - timeMillis > EVENT_TTL_MS) {
                    skipped++
                    advanceCursor(eventFile)
                    continue
                }

                val packageName = event.optString("packageName", "")
                val originalPath = event.optString("originalPath", "")
                val mountedPath = event.optString("mountedPath", "")
                val reason = event.optString("reason", "REDIRECTED_TO_INTERNAL")
                val type = event.optString("type", "")

                if (packageName.isEmpty()) {
                    skipped++
                    advanceCursor(eventFile)
                    continue
                }

                // denylist 检查
                if (ServicePreferences.denylist.contains(packageName)) {
                    skipped++
                    advanceCursor(eventFile)
                    continue
                }

                // 通过控制面方法触发 UI 广播（Java 侧，避免 Kotlin stub Intent 问题）
                when (reason) {
                    "MEDIA_NOT_FOUND", "MEDIA_NOT_FOUND_AGGRESSIVE" -> {
                        val path = originalPath.ifBlank { mountedPath }
                        if (path.isBlank()) {
                            skipped++
                            advanceCursor(eventFile)
                            continue
                        }
                        srv.showMediaNotFoundNotice(
                            packageName,
                            path,
                            reason == "MEDIA_NOT_FOUND_AGGRESSIVE",
                        )
                    }
                    else -> {
                        // 如果 mountedPath 已作为目录存在，跳过保存提示（文件已可访问）
                        if (mountedPath.isNotEmpty() && java.io.File(mountedPath).isDirectory) {
                            skipped++
                            advanceCursor(eventFile)
                            continue
                        }
                        srv.showRedirectNotice(packageName, originalPath, mountedPath, type)
                    }
                }
                consumed++
                advanceCursor(eventFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume redirect notice ${eventFile.name}, keeping cursor", e)
                failed = true
                break
            }
        }
        if (failed) {
            lastSignalTimestamp = 0L
        }

        if (consumed > 0 || skipped > 0) {
            Log.d(TAG, "Consumed $consumed, skipped $skipped, cursor='$cursor'")
        }
        return consumed
    }

    private fun advanceCursor(event: DataBus.EventFile) {
        cursor = event.name
        DataBus.writeCursorToEvent(DataBus.EVENT_REDIRECT_NOTICE, event)
    }
}
