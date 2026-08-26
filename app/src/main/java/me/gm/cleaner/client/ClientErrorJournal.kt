package me.gm.cleaner.client

import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.common.err.ErrorJournal

/**
 * App 进程侧的错误事件日志。
 *
 * 与 server 进程的 ServerErrorJournal 分属不同进程、互不共享内存：
 * 服务监督域（launch 失败、假死强停等）的事实产生于 App 进程，
 * 在此独立留痕；跨进程汇聚属未来 IPC 扩展范畴。
 */
object ClientErrorJournal {
    const val DEFAULT_CAPACITY: Int = 50

    private val journal = ErrorJournal(capacity = DEFAULT_CAPACITY)

    /** 记录一条错误事件；环形缓冲满时自动淘汰最旧条目，永不抛出。 */
    fun record(event: ErrorEvent) = journal.record(event)

    /** 按时间升序返回当前缓冲快照。 */
    fun snapshot(): List<ErrorEvent> = journal.snapshot()

    /** 清空缓冲。 */
    fun clear() = journal.clear()
}
