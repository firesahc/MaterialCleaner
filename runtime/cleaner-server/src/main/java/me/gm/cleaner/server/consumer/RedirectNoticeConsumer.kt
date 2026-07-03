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
        val events = DataBus.readEvents(DataBus.EVENT_REDIRECT_NOTICE, cursor)
        if (events.isEmpty()) return 0

        var consumed = 0
        var skipped = 0
        for (eventJson in events) {
            try {
                val event = JSONObject(eventJson)
                val timeMillis = event.optLong("timeMillis", 0L)

                // TTL 检查
                if (timeMillis > 0 && System.currentTimeMillis() - timeMillis > EVENT_TTL_MS) {
                    skipped++
                    continue
                }

                val packageName = event.optString("packageName", "")
                val originalPath = event.optString("originalPath", "")
                val mountedPath = event.optString("mountedPath", "")
                val reason = event.optString("reason", "REDIRECTED_TO_INTERNAL")

                if (packageName.isEmpty()) {
                    skipped++
                    continue
                }

                // denylist 检查
                if (ServicePreferences.denylist.contains(packageName)) {
                    skipped++
                    continue
                }

                // 通过控制面方法触发 UI 广播（Java 侧，避免 Kotlin stub Intent 问题）
                srv.showRedirectNotice(packageName, originalPath, mountedPath, reason)
                consumed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume redirect notice", e)
            }
        }

        // 推进游标并持久化
        val lastFile = DataBus.getLastEventFilename(DataBus.EVENT_REDIRECT_NOTICE)
        if (lastFile.isNotEmpty()) {
            cursor = lastFile
            DataBus.writeCursor(DataBus.EVENT_REDIRECT_NOTICE, cursor)
        }

        if (consumed > 0 || skipped > 0) {
            Log.d(TAG, "Consumed $consumed, skipped $skipped, cursor='$cursor'")
        }
        return consumed
    }
}
