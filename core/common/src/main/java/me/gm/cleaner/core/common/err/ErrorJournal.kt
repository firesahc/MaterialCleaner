package me.gm.cleaner.core.common.err

/**
 * 线程安全的错误事件环形缓冲。
 *
 * server 进程内聚合同类事件的唯一写入口：
 * - [record] 永不抛出、永不阻塞，缓冲满时淘汰最旧事件；
 * - [snapshot] 返回按时间升序的只读副本，供 OrchestratedStatus 发布与
 *   DiagnosticArchive 导出 errors/journal.jsonl；
 * - 容量上限防止罕见风暴场景下的内存无界增长。
 *
 * 可选的 [sink] 为持久化输出通道：每次 [record] 后被调用一次，用于把事件
 * 追加写入磁盘载体，使根因流水跨进程死亡与设备重启后仍可导出。sink 的调用
 * 在缓冲锁之外执行（文件 IO 不占用锁），其失败由 sink 自身消化。
 */
class ErrorJournal(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val sink: ((ErrorEvent) -> Unit)? = null,
) {

    init {
        require(capacity in 1..MAX_CAPACITY) {
            "capacity must be within [1, $MAX_CAPACITY]"
        }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<ErrorEvent>(capacity)

    /** 记录一条错误事件；满容量时静默淘汰最旧条目。 */
    fun record(event: ErrorEvent) {
        synchronized(lock) {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(event)
        }
        // 持久化输出在缓冲锁之外执行：文件 IO 不占用锁，也不阻塞并发 record。
        try {
            sink?.invoke(event)
        } catch (t: Throwable) {
            // 持久化失败不影响内存留痕与业务流程。
        }
    }

    /** 当前缓冲的按时间升序只读快照；调用方持有副本，后续写入不影响其内容。 */
    fun snapshot(): List<ErrorEvent> = synchronized(lock) { buffer.toList() }

    /** 清空缓冲；server 冷启动或用户显式清除诊断数据时使用。 */
    fun clear() = synchronized(lock) { buffer.clear() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 50
        const val MAX_CAPACITY: Int = 1000
    }
}
