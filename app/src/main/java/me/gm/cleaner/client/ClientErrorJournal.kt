package me.gm.cleaner.client

import android.content.Context
import android.util.Log
import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.common.err.ErrorJournal
import org.json.JSONObject
import java.io.File

/**
 * App 进程侧的错误事件日志。
 *
 * 与 server 进程的 ServerErrorJournal 分属不同进程、互不共享内存：
 * 服务监督域（launch 失败、假死强停等）的事实产生于 App 进程，
 * 在此独立留痕。
 *
 * 持久化模型：
 * - 内存环形缓冲保存本会话最近事件；
 * - [record] 同时以 JSON Lines 追加写入设备保护存储下的持久层文件，
 *   跨进程死亡与设备重启后仍可导出；
 * - [exportJsonL] 输出持久层全文，供诊断包 client/errors/journal.jsonl
 *   条目使用，格式与 server 端 errors/journal.jsonl 一致。
 */
object ClientErrorJournal {
    private const val TAG = "ClientErrorJournal"
    private const val JOURNAL_FILE_NAME = "client_error_journal.jsonl"
    const val DEFAULT_CAPACITY: Int = 50
    /** 持久层滚动阈值：超过后保留最近约一半内容，防无界增长。 */
    private const val MAX_FILE_BYTES: Int = 64 * 1024

    private val lock = Any()
    private val journal = ErrorJournal(capacity = DEFAULT_CAPACITY)

    @Volatile
    private var journalFile: File? = null

    /** 由 Application.onCreate 注入设备保护存储上下文。 */
    fun init(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        journalFile = File(dir, JOURNAL_FILE_NAME)
    }

    /** 记录一条错误事件：内存环形缓冲 + 持久层追加。永不抛出。 */
    fun record(event: ErrorEvent) {
        synchronized(lock) {
            journal.record(event)
            appendToStorageLocked(event)
        }
    }

    /** 内存缓冲快照（仅本会话）；跨会话历史请用 [exportJsonL]。 */
    fun snapshot(): List<ErrorEvent> = synchronized(lock) { journal.snapshot() }

    /** 清空内存缓冲与持久层文件。 */
    fun clear() = synchronized(lock) {
        journal.clear()
        runCatching { journalFile?.delete() }
            .onFailure { Log.w(TAG, "failed to delete journal file", it) }
        Unit
    }

    /** 持久层全文（JSON Lines，按时间升序）；文件不存在时返回空串。 */
    fun exportJsonL(): String = synchronized(lock) {
        val file = journalFile ?: return ""
        runCatching {
            if (file.exists()) file.readText() else ""
        }.getOrDefault("")
    }

    private fun appendToStorageLocked(event: ErrorEvent) {
        val file = journalFile ?: return
        runCatching {
            rollIfNeededLocked(file)
            file.appendText(eventToJson(event) + "\n")
        }.onFailure {
            // 持久化失败不阻断内存留痕与业务流程。
            Log.w(TAG, "failed to append error event to storage", it)
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

    /** 字段集与 server 端 errors/journal.jsonl 保持一致，便于离线工具统一归并。 */
    private fun eventToJson(event: ErrorEvent): String = JSONObject().apply {
        put("code", event.code)
        put("atElapsed", event.atElapsed)
        if (event.errno != 0) put("errno", event.errno)
        event.subject?.let { put("subject", it) }
        event.pathDigest?.let { put("pathDigest", it) }
        if (event.generation > 0L) put("generation", event.generation)
        event.detail?.let { put("detail", it) }
    }.toString()
}
