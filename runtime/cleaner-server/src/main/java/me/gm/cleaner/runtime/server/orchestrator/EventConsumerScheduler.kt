package me.gm.cleaner.runtime.server.orchestrator

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.consumer.FileSystemEventConsumer
import me.gm.cleaner.runtime.server.consumer.QuerySessionLeaseConsumer
import me.gm.cleaner.runtime.server.consumer.RedirectNoticeConsumer

/**
 * DataBus 事件消费者调度器。
 *
 * 负责绑定、加载游标、补偿消费和定时轮询，避免三层编排器直接持有事件队列细节。
 *
 * 线程模型：消费循环运行在专用 [HandlerThread] 上，而非 server 主线程。
 * 事件消费链路包含 Room 写入（FileSystemRecordDao）等主线程禁止操作；
 * 单次轮询异常只记录并继续调度，不终止轮询链。
 */
class EventConsumerScheduler(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "EventConsumerScheduler"
        private const val CONSUMER_POLL_INTERVAL_MS = 2_000L
    }

    var onHeartbeat: (() -> Unit)? = null

    @Volatile
    private var running = false

    private var workerThread: HandlerThread? = null

    private var workerHandler: Handler? = null

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
        val thread = HandlerThread("CleanerEventConsumer").apply { start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)
        Log.i(TAG, "started (interval=${CONSUMER_POLL_INTERVAL_MS}ms, background thread)")
        scheduleNext()
    }

    fun stop() {
        if (!running) return
        running = false
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        Log.i(TAG, "stopped")
    }

    private fun scheduleNext() {
        if (!running) return
        val handler = workerHandler ?: return
        handler.postDelayed({
            if (!running) return@postDelayed
            try {
                pollOnce()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                // 单次失败不终止轮询链：事件保留在游标之前，下轮自然重试。
                Log.e(TAG, "poll cycle failed", failure)
            }
            try {
                onHeartbeat?.invoke()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                Log.e(TAG, "heartbeat callback failed", failure)
            }
            scheduleNext()
        }, CONSUMER_POLL_INTERVAL_MS)
    }
}
