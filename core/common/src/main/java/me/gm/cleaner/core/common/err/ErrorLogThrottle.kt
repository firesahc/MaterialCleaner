package me.gm.cleaner.core.common.err

/**
 * 按错误码节流的日志放行器。
 *
 * 同一 [code] 在 [windowMillis] 窗口内只放行首个事件，窗口结束后自动重置，
 * 防止高频故障（挂载 EBUSY 循环、Binder 死亡风暴）刷屏掩盖其他错误信号。
 *
 * 与熔断器无关：这里不做任何功能降级决策，仅控制可观测性输出密度。
 */
class ErrorLogThrottle(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    init {
        require(windowMillis > 0L) { "windowMillis must be positive" }
    }

    private val lock = Any()
    private val lastEmittedAt = HashMap<String, Long>()

    /**
     * 判断该码当前是否允许对外输出。
     * @return true 表示窗口外首个事件，调用方应完整记录；false 表示被节流。
     */
    fun tryAcquire(code: String, nowElapsed: Long): Boolean = synchronized(lock) {
        val last = lastEmittedAt[code]
        if (last == null || nowElapsed - last >= windowMillis) {
            lastEmittedAt[code] = nowElapsed
            true
        } else {
            false
        }
    }

    /** 事件恢复正常后由调用方主动复位，避免下一次真实故障被旧窗口吞掉。 */
    fun reset(code: String) = synchronized(lock) { lastEmittedAt.remove(code) }

    companion object {
        const val DEFAULT_WINDOW_MILLIS: Long = 30_000L
    }
}
