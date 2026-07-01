package me.gm.cleaner.dao.policy

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 文件系统数据总线。
 *
 * 负责承载跨进程、跨层共享的持久化事实——快照（Snapshot）、信号（Signal）、事件队列（Event）。
 *
 * 目录结构：
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
 *     filesystem/
 *     redirect_notice/
 *   tmp/
 * ```
 *
 * ## 原子写协议
 * 1. 写入 tmp/ 下的临时文件
 * 2. flush + fsync 确保落盘
 * 3. rename 到目标文件（POSIX 保证原子性）
 * 4. touch 对应 signal 文件通知消费者
 *
 * ## 权限
 * server 进程（root）负责创建目录和写快照。
 * MediaProvider 进程只需读取 snapshots/ 和 signals/ 目录。
 */
object DataBus {
    private const val TAG = "DataBus"

    /** 总线根目录 */
    const val BUS_ROOT = "/data/local/tmp/cleaner/bus"

    private const val DIR_SNAPSHOTS = "snapshots"
    private const val DIR_SIGNALS = "signals"
    private const val DIR_EVENTS = "events"
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

    /**
     * 确保总线目录结构存在，幂等。
     * 仅在 server 进程（root）中调用。
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true

        try {
            val dirs = listOf(
                "$BUS_ROOT/$DIR_SNAPSHOTS",
                "$BUS_ROOT/$DIR_SIGNALS",
                "$BUS_ROOT/$DIR_EVENTS/$EVENT_FILESYSTEM",
                "$BUS_ROOT/$DIR_EVENTS/$EVENT_REDIRECT_NOTICE",
                "$BUS_ROOT/$DIR_TMP",
            )
            for (dir in dirs) {
                val f = File(dir)
                if (!f.exists() && !f.mkdirs()) {
                    Log.e(TAG, "Failed to create directory: $dir")
                    return false
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

    /**
     * 原子写入快照。
     *
     * @param name 快照文件名（不含路径）
     * @param content JSON 字符串
     */
    fun writeSnapshot(name: String, content: String): Boolean {
        val targetFile = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        val tmpFile = File("$BUS_ROOT/$DIR_TMP/$name.tmp")

        return try {
            // 1. 写入临时文件
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }

            // 2. 原子 rename
            if (!tmpFile.renameTo(targetFile)) {
                // rename 可能跨文件系统失败（通常不会，因为在同一分区），
                // 但 Android 上 /data 内部几乎总是同一文件系统
                Log.w(TAG, "rename failed for $name, fallback to copy")
                targetFile.delete()
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }

            Log.d(TAG, "Snapshot written: $name (${content.length} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapshot: $name", e)
            tmpFile.delete()
            false
        }
    }

    /**
     * 读取快照内容。
     *
     * @param name 快照文件名
     * @return 文件内容，或 null（文件不存在/读取失败）
     */
    fun readSnapshot(name: String): String? {
        val file = File("$BUS_ROOT/$DIR_SNAPSHOTS/$name")
        return try {
            if (!file.exists()) null
            else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snapshot: $name", e)
            null
        }
    }

    // ── 信号 ──

    /**
     * 发送信号。
     * 通过 touch 信号文件通知消费者快照/事件已更新。
     */
    fun signal(name: String): Boolean {
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return try {
            if (!signalFile.exists()) {
                signalFile.createNewFile()
            } else {
                signalFile.setLastModified(System.currentTimeMillis())
            }
            Log.d(TAG, "Signal sent: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send signal: $name", e)
            false
        }
    }

    /**
     * 获取信号最后触发时间戳。
     *
     * @return 时间戳（毫秒），如果信号文件不存在返回 0
     */
    fun getSignalTimestamp(name: String): Long {
        val signalFile = File("$BUS_ROOT/$DIR_SIGNALS/$name")
        return if (signalFile.exists()) signalFile.lastModified() else 0L
    }

    // ── 事件队列 ──

    /**
     * 追加写入事件。
     * 每行一个 JSON 对象，以换行符分隔。
     *
     * @param queue 事件队列子目录名（如 "filesystem"）
     * @param content JSON 字符串（单行）
     * @return 事件序列号（自增），失败返回 -1
     */
    @Synchronized
    fun writeEvent(queue: String, content: String): Long {
        val eventDir = File("$BUS_ROOT/$DIR_EVENTS/$queue")
        if (!eventDir.exists() && !eventDir.mkdirs()) {
            Log.e(TAG, "Failed to create event dir: $queue")
            return -1L
        }

        val eventFile = File(eventDir, "events.jsonl")
        return try {
            // 获取当前行数作为 seq
            val currentLines = if (eventFile.exists()) {
                eventFile.readLines(Charsets.UTF_8).size
            } else {
                0
            }

            // 追加一行
            RandomAccessFile(eventFile, "rw").use { raf ->
                raf.seek(eventFile.length())
                raf.write((content + "\n").toByteArray(Charsets.UTF_8))
            }

            val seq = currentLines.toLong()
            Log.d(TAG, "Event written to $queue: seq=$seq")
            seq
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event to $queue", e)
            -1L
        }
    }

    /**
     * 读取指定序列号之后的事件。
     *
     * @param queue 事件队列子目录名
     * @param afterSeq 起始序列号（不含），传 -1 读取全部
     * @return 事件 JSON 字符串列表（每行一个事件）
     */
    fun readEvents(queue: String, afterSeq: Long): List<String> {
        val eventFile = File("$BUS_ROOT/$DIR_EVENTS/$queue/events.jsonl")
        return try {
            if (!eventFile.exists()) return emptyList()

            val lines = eventFile.readLines(Charsets.UTF_8)
            val startIndex = (afterSeq + 1).coerceAtLeast(0).toInt()
            if (startIndex >= lines.size) return emptyList()

            lines.subList(startIndex, lines.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read events from $queue", e)
            emptyList()
        }
    }
}
