package me.gm.cleaner.core.storage.redirect.databus

import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件系统数据总线。
 *
 * 承载跨进程、跨层共享的持久化事实。
 *
 * ## 目录结构
 * ```
 * /data/local/tmp/cleaner/bus/
 *   snapshots/
 *     redirect_policy.json
 *     read_only.json
 *     configured_mount_points.json
 *   signals/
 *     redirect_policy_changed
 *     read_only_changed
 *     configured_mount_points_changed
 *   events/
 *     filesystem/     ← MediaProvider 进程写入，server 进程读取
 *     redirect_notice/ ← MediaProvider 进程写入，server 进程读取
 *     consumed/       ← 已消费事件归档
 *   cursors/          ← 消费者游标持久化
 *   tmp/
 * ```
 *
 * ## 原子写协议（快照 & 事件共享）
 * 1. 写入 tmp/ 下的临时文件
 * 2. flush + fsync 确保落盘
 * 3. rename 到目标文件（POSIX 原子性）
 * 4. touch signal 通知消费者
 *
 * ## 事件格式
 * 每个事件为一个独立 JSON 文件，命名规则：
 *   `<seq-20位>-<timestamp>-<pid>-<rand-hex>.json`
 * 消费者按文件名排序读取，确保时序性。
 *
 * ## 权限
 * - snapshots/signals：server (root) 写，MediaProvider 读
 * - events：MediaProvider 写，server 读/消费
 * - 目录权限设为 0777（允许跨进程访问）
 */
object DataBus {
    private const val TAG = "DataBus"

    const val BUS_ROOT = "/data/local/tmp/cleaner/bus"

    private const val DIR_SNAPSHOTS = "snapshots"
    private const val DIR_SIGNALS = "signals"
    private const val DIR_EVENTS = "events"
    private const val DIR_CURSORS = "cursors"
    private const val DIR_CONSUMED = "consumed"
    private const val DIR_TMP = "tmp"

    // ── 快照文件名 ──
    const val SNAPSHOT_REDIRECT_POLICY = "redirect_policy.json"
    const val SNAPSHOT_READ_ONLY = "read_only.json"
    const val SNAPSHOT_CONFIGURED_MOUNT_POINTS = "configured_mount_points.json"

    // ── 信号文件名 ──
    const val SIGNAL_REDIRECT_POLICY_CHANGED = "redirect_policy_changed"
    const val SIGNAL_READ_ONLY_CHANGED = "read_only_changed"
    const val SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED = "configured_mount_points_changed"

    // ── 事件子目录 ──
    const val EVENT_FILESYSTEM = "filesystem"
    const val EVENT_REDIRECT_NOTICE = "redirect_notice"

    @Volatile
    private var initialized = false

    // 每进程事件序列号
    private val eventSeqCounter = AtomicLong(0)

    /**
     * 确保总线目录结构存在，设置跨进程可访问权限。
     * 幂等，可在 server 或 MediaProvider 进程中调用。
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true

        try {
            val dirs = listOf(
                BUS_ROOT,
                "$BUS_ROOT/$DIR_SNAPSHOTS",
                "$BUS_ROOT/$DIR_SIGNALS",
                "$BUS_ROOT/$DIR_EVENTS/$EVENT_FILESYSTEM",
                "$BUS_ROOT/$DIR_EVENTS/$EVENT_REDIRECT_NOTICE",
                "$BUS_ROOT/$DIR_CURSORS",
                "$BUS_ROOT/$DIR_TMP",
            )
            for (dir in dirs) {
                val f = File(dir)
                if (!f.exists()) {
                    if (!f.mkdirs()) {
                        // 如果 mkdirs 失败，检查是否已被其他进程创建
                        if (!f.exists()) {
                            Log.e(TAG, "Failed to create directory: $dir")
                            return false
                        }
                    }
                    // 设置跨进程可读写权限
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                    f.setExecutable(true, false)
                }
            }
            initialized = true
            Log.i(TAG, "DataBus initialized at $BUS_ROOT")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DataBus", e)
            return false
        }
    }

    // ── 快照读写 ──

    fun writeSnapshot(name: String, content: String): Boolean {
        val targetFile = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        val tmpFile = File("$BUS_ROOT/$DIR_TMP/$name.tmp")

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.w(TAG, "rename failed for $name, fallback to copy")
                targetFile.delete()
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            // 确保 MediaProvider 进程可读取
            targetFile.setReadable(true, false)
            Log.d(TAG, "Snapshot written: $name (${content.length} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapshot: $name", e)
            tmpFile.delete()
            false
        }
    }

    fun readSnapshot(name: String): String? {
        val file = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        return try {
            if (!file.exists()) null else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snapshot: $name", e)
            null
        }
    }

    // ── 信号 ──

    fun signal(name: String): Boolean {
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return try {
            if (!signalFile.exists()) signalFile.createNewFile()
            else signalFile.setLastModified(System.currentTimeMillis())
            signalFile.setReadable(true, false)
            Log.d(TAG, "Signal sent: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signal: $name", e)
            false
        }
    }

    fun getSignalTimestamp(name: String): Long {
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return if (signalFile.exists()) signalFile.lastModified() else 0L
    }

    // ── 事件队列（单文件原子写） ──

    /**
     * 原子写入一个事件。
     *
     * 每个事件为单独的 JSON 文件：
     *   `events/<queue>/<seq20>-<ts>-<pid>-<rand>.json`
     *
     * 写入流程：tmp → fsync → rename → 目标文件
     * 多进程安全：每进程独立 seq 计数器 + pid/rand 确保文件名唯一。
     *
     * @param queue 事件队列子目录名
     * @param content JSON 字符串
     * @return 事件序列号（成功），-1（失败）
     */
    fun writeEvent(queue: String, content: String): Long {
        ensureInitialized()
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists() && !eventDir.mkdirs()) {
            Log.e(TAG, "Failed to create event dir: $queue")
            return -1L
        }

        val seq = eventSeqCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        val pid = Process.myPid()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = String.format("%020d-%d-%d-%04x.json", seq, now, pid, rand)

        val tmpFile = File(eventDir, "$filename.tmp")
        val targetFile = File(eventDir, filename)

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                Log.e(TAG, "Event rename failed: $filename")
                tmpFile.delete()
                return -1L
            }
            targetFile.setReadable(true, false)
            Log.d(TAG, "Event written: $queue/$filename")
            seq
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event to $queue", e)
            tmpFile.delete()
            -1L
        }
    }

    /**
     * 读取游标之后的所有事件。
     *
     * @param queue 事件队列子目录名
     * @param afterCursor 游标值（上次消费的最后文件名），"" 表示从头开始
     * @return 事件 JSON 字符串列表（按文件名排序）
     */
    fun readEvents(queue: String, afterCursor: String): List<String> {
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return emptyList()

        return try {
            eventDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && it.name > afterCursor }
                ?.sortedBy { it.name }
                ?.map { it.readText(Charsets.UTF_8) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events from $queue", e)
            emptyList()
        }
    }

    /**
     * 获取事件队列中最后一个事件的文件名（用作游标）。
     */
    fun getLastEventFilename(queue: String): String {
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists()) return ""
        return eventDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.maxByOrNull { it.name }
            ?.name ?: ""
    }

    // ── 消费游标持久化 ──

    /**
     * 持久化消费游标。
     * 原子写：tmp → fsync → rename。
     */
    fun writeCursor(queue: String, cursor: String): Boolean {
        val cursorDir = File("$BUS_ROOT/$DIR_CURSORS")
        if (!cursorDir.exists()) cursorDir.mkdirs()

        val cursorFile = File(cursorDir, "$queue.cursor")
        val tmpFile = File(cursorDir, "$queue.cursor.tmp")

        return try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(cursor.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(cursorFile)) {
                cursorFile.delete()
                tmpFile.copyTo(cursorFile, overwrite = true)
                tmpFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cursor: $queue", e)
            tmpFile.delete()
            false
        }
    }

    /**
     * 读取持久化消费游标。
     * @return 游标值（上次消费的最后文件名），"" 表示未消费过
     */
    fun readCursor(queue: String): String {
        val cursorFile = File("$BUS_ROOT/$DIR_CURSORS/$queue.cursor")
        return try {
            if (cursorFile.exists()) cursorFile.readText(Charsets.UTF_8).trim() else ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cursor: $queue", e)
            ""
        }
    }
}
