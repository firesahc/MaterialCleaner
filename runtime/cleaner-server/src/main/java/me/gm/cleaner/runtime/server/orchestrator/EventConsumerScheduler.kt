package me.gm.cleaner.runtime.server.orchestrator

import android.util.Log
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.consumer.FileSystemEventConsumer
import me.gm.cleaner.runtime.server.consumer.QuerySessionLeaseConsumer
import me.gm.cleaner.runtime.server.consumer.RedirectNoticeConsumer

/**
 * DataBus 事件消费者调度器。
 *
 * 负责绑定、加载游标、补偿消费和定时轮询，避免三层编排器直接持有事件队列细节。
 */
class EventConsumerScheduler(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "EventConsumerScheduler"
        private const val CONSUMER_POLL_INTERVAL_MS = 2_000L
    }

    var onHeartbeat: (() -> Unit)? = null

    private var running = false

    fun prepare() {
        RedirectNoticeConsumer.bind(server)
        FileSystemEventConsumer.loadCursor()
        RedirectNoticeConsumer.loadCursor()
    }

    fun pollOnce() {
        FileSystemEventConsumer.pollAndConsume()
        RedirectNoticeConsumer.pollAndConsume()
        QuerySessionLeaseConsumer.pollAndApply()
    }

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "started (interval=${CONSUMER_POLL_INTERVAL_MS}ms)")
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!running) return
        server.handler.postDelayed({
            pollOnce()
            onHeartbeat?.invoke()
            scheduleNext()
        }, CONSUMER_POLL_INTERVAL_MS)
    }
}
