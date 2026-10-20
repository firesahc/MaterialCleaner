package me.gm.cleaner.runtime.server.orchestrator

import android.content.Context
import android.util.Log
import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.common.err.ErrorJournal
import java.io.File

/**
 * server 进程内共享的错误事件日志。
 *
 * 各层（VFS/Mounter、Hook 恢复、DataBus、控制面）在产生结构化失败事实时
 * 调用 [record] 写入；[OrchestratedStatus] 的 JSON 输出与 DiagnosticArchive
 * 的 errors/journal.jsonl 均由此读取，保证"看状态卡、导诊断包"看到同一份事实。
 *
 * 持久化模型：
 * - 内存环形缓冲保存本会话最近事件；
 * - [record] 同时以 JSON Lines 追加写入设备保护存储下的持久层文件，
 *   跨进程死亡与设备重启后仍可导出；
 * - [exportJsonL] 输出持久层全文，供诊断包 errors/journal.jsonl 条目使用，
 *   格式与 app 侧 client/errors/journal.jsonl 一致。
 *
 * server 侧此前只有内存缓冲，服务重启即丢失全部根因流水；挂载失败等事实
 * 主要在此产生，落盘后诊断包才能覆盖跨重启场景。
 */
object ServerErrorJournal {
    const val DEFAULT_CAPACITY: Int = 50
    private const val TAG = "ServerErrorJournal"
    private const val JOURNAL_FILE_NAME = "server_error_journal.jsonl"
    /** 持久层滚动阈值：超过后保留最近约一半内容，防无界增长。 */
    private const val MAX_FILE_BYTES: Int = 64 * 1024

    private val lock = Any()
    private val journal = ErrorJournal(capacity = DEFAULT_CAPACITY, sink = ::appendToStorage)

    @Volatile
    private var journalFile: File? = null

    /** 由 CleanerServer 构造时注入设备保护存储上下文。 */
    fun init(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        journalFile = File(dir, JOURNAL_FILE_NAME)
    }

    /** 记录一条错误事件：内存环形缓冲 + 持久层追加。永不抛出。 */
    fun record(event: ErrorEvent) = journal.record(event)

    /** 按时间升序返回当前会话的内存缓冲快照。 */
    fun snapshot(): List<ErrorEvent> = journal.snapshot()

    /** 清空内存缓冲与持久层文件。 */
    fun clear() {
        journal.clear()
        synchronized(lock) {
            runCatching { journalFile?.delete() }
                .onFailure { Log.w(TAG, "failed to delete journal file", it) }
        }
    }

    /** 持久层全文（JSON Lines，按时间升序）；文件不存在时返回空串。 */
    fun exportJsonL(): String = synchronized(lock) {
        val file = journalFile ?: return ""
        runCatching {
            if (file.exists()) file.readText() else ""
        }.getOrDefault("")
    }

    private fun appendToStorage(event: ErrorEvent) {
        val file = journalFile ?: return
        synchronized(lock) {
            runCatching {
                rollIfNeededLocked(file)
                file.appendText(event.toJson() + "\n")
            }.onFailure {
                // 持久化失败不阻断内存留痕与业务流程。
                Log.w(TAG, "failed to append error event to storage", it)
            }
        }
    }

    /** 超过字节上限时保留最近的约一半行数并重写文件。 */
    private fun rollIfNeededLocked(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val keep = file.readLines().takeLast(maxOf(1, readLineCount(file) / 2))
        file.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun readLineCount(file: File): Int =
        runCatching { file.readLines().size }.getOrDefault(0)
}
