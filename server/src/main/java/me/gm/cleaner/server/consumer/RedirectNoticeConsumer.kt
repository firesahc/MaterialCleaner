package me.gm.cleaner.server.consumer

import android.util.Log
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.dao.policy.DataBus
import me.gm.cleaner.server.CleanerServer
import org.json.JSONObject

/**
 * 重定向提示事件消费者。
 *
 * 从 [DataBus] events/redirect_notice 队列读取事件并记录日志。
 * 实际的 UI 广播仍由 Binder 路径（[me.gm.cleaner.server.CleanerServerCallback.getMountedPath]）处理。
 *
 * DataBus 路径提供补偿能力：当 Binder 不可用时，事件被持久化；
 * server 恢复后可重新消费积压事件。
 *
 * ## TTL
 * 超过 5 分钟的提示事件直接跳过（过期丢弃）。
 */
object RedirectNoticeConsumer {
    private const val TAG = "RedirectNoticeConsumer"
    private const val EVENT_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    @Volatile
    private var lastConsumedSeq: Long = -1L

    @Volatile
    private var server: CleanerServer? = null

    fun bind(server: CleanerServer) {
        this.server = server
    }

    /**
     * 从 DataBus 拉取并消费所有未处理的提示事件。
     * 当前仅做日志记录和 denylist 校验，UI 广播由 Binder 路径负责。
     */
    fun pollAndConsume() {
        if (server == null) return
        val events = DataBus.readEvents(DataBus.EVENT_REDIRECT_NOTICE, lastConsumedSeq)
        if (events.isEmpty()) return

        var consumed = 0
        var skipped = 0
        for (eventJson in events) {
            try {
                lastConsumedSeq++
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

                if (packageName.isEmpty()) {
                    skipped++
                    continue
                }

                // denylist 检查
                if (ServicePreferences.denylist.contains(packageName)) {
                    skipped++
                    continue
                }

                // 记录事件（UI 广播由 CleanerServerCallback Binder 路径处理）
                Log.i(TAG, "redirect_notice: pkg=$packageName original=$originalPath mounted=$mountedPath")
                consumed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consume redirect notice", e)
            }
        }

        if (consumed > 0 || skipped > 0) {
            Log.d(TAG, "Consumed $consumed events, skipped $skipped, lastSeq=$lastConsumedSeq")
        }
    }
}
