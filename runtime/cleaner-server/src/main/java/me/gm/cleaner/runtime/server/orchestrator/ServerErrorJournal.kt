package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.common.err.ErrorJournal

/**
 * server 进程内共享的错误事件日志。
 *
 * 各层（VFS/Mounter、Hook 恢复、DataBus、控制面）在产生结构化失败事实时
 * 调用 [record] 写入；[OrchestratedStatus] 的 JSON 输出与 DiagnosticArchive
 * 的 errors/journal.jsonl 均由此读取，保证"看状态卡、导诊断包"看到同一份事实。
 */
object ServerErrorJournal {
    const val DEFAULT_CAPACITY: Int = 50

    private val journal = ErrorJournal(capacity = DEFAULT_CAPACITY)

    /** 记录一条错误事件；环形缓冲满时自动淘汰最旧条目，永不抛出。 */
    fun record(event: ErrorEvent) = journal.record(event)

    /** 按时间升序返回当前缓冲快照。 */
    fun snapshot(): List<ErrorEvent> = journal.snapshot()

    /** 清空缓冲（server 冷启动或用户清除诊断数据）。 */
    fun clear() = journal.clear()
}
