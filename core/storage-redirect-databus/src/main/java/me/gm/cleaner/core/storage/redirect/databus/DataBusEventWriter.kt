package me.gm.cleaner.core.storage.redirect.databus

import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

/**
 * DataBus 事件发布：序号分配与原子写入。
 *
 * 职责边界：本对象拥有**事件序号计数器与文件落盘**；
 * 队列名校验、目录准备等基础原语复用 [DataBus] 的模块内可见成员。
 *
 * 序号单调性来源：持久化序号计数器为主，队列现存最大序号用于迁移/复核，
 * 进程内序号用于兜底。计数器会保留已清理事件的序号上界。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）。[DataBus.writeEvent]
 * 保留公开委托，跨模块调用方无需改动。
 */
internal object DataBusEventWriter {

    private const val TAG = "DataBusEventWriter"

    internal sealed interface SequenceCounterRead {
        data object Absent : SequenceCounterRead
        data object Unreadable : SequenceCounterRead
        data class Value(val sequence: Long) : SequenceCounterRead
    }

    internal sealed interface CursorSequenceRead {
        data object Absent : CursorSequenceRead
        data object Unreadable : CursorSequenceRead
        data class Value(val sequence: Long) : CursorSequenceRead
    }

    // 进程内序号下界；真实事件序号会通过 counters/ 持久化分配。
    private val eventSeqCounter = AtomicLong(0)

    @Synchronized
    fun writeEventLocked(
        queue: String,
        eventDir: File,
        content: String,
        busRoot: File = File(DataBus.BUS_ROOT),
    ): Long {
        // 游标不可读时必须在准备或打开计数器前失败，不能把它折算为空游标，
        // 否则可能发出低于最后已提交消费位置的序号。
        val cursor = readCursorSequenceForSequenceFloor(queue, busRoot)
        if (cursor == CursorSequenceRead.Unreadable) return -1L

        val counterDir = File(busRoot, DataBus.DIR_COUNTERS)
        if (!DataBus.prepareDirectory(counterDir)) return -1L

        val counterFile = File(counterDir, "$queue.seq")
        val counterPath = counterFile.toPath()
        return try {
            val counterExisted = Files.exists(counterPath, LinkOption.NOFOLLOW_LINKS)
            if (counterExisted && !isRegularCounterFile(counterPath)) {
                Log.e(TAG, "Refusing to replace invalid sequence counter: $queue")
                return -1L
            }

            RandomAccessFile(counterFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    channel.lock().use {
                        // 锁住稳定的计数器文件 inode。若在持锁时原子替换文件，
                        // 其他进程可能锁到新 inode，从而绕过当前锁。
                        if (!isRegularCounterFile(counterPath)) {
                            Log.e(TAG, "Sequence counter changed while opening: $queue")
                            return -1L
                        }
                        val storedSequence = readCounterValue(raf)
                        val queueSequence = maxEventSequence(queue, busRoot)
                        if (queueSequence == null) {
                            Log.e(TAG, "Cannot determine sequence floor for $queue")
                            return -1L
                        }
                        val cursorSequence = (cursor as? CursorSequenceRead.Value)?.sequence ?: 0L
                        val queueHasEvents = hasEventFiles(queue, busRoot)
                        if (queueHasEvents == null) {
                            Log.e(TAG, "Cannot inspect event queue while assigning sequence: $queue")
                            return -1L
                        }
                        val baseSequence = when {
                            storedSequence != null -> storedSequence
                            !counterExisted && raf.length() == 0L &&
                                    cursor == CursorSequenceRead.Absent && !queueHasEvents -> 0L
                            canRecoverSequenceCounterFromQueue(
                                queueSequence,
                                queueHasEvents,
                                cursor,
                            ) -> {
                                if (counterExisted) {
                                    Log.w(TAG, "Recovering invalid sequence counter from current queue: $queue")
                                }
                                queueSequence
                            }
                            else -> {
                                // Missing counters may be initialized from visible event history
                                // only. A cursor beyond that history is ambiguous and must not be
                                // promoted into a new trusted upper bound.
                                Log.e(TAG, "Sequence counter is corrupt or its history is unknown: $queue")
                                return -1L
                            }
                        }
                        if (cursorSequence > maxOf(baseSequence, queueSequence)) {
                            Log.e(TAG, "Refusing to issue sequence below persisted cursor: $queue")
                            return -1L
                        }
                        val next = nextEventSequence(baseSequence, queueSequence)
                        if (next <= 0L) {
                            Log.e(TAG, "Sequence counter overflow for $queue")
                            return -1L
                        }
                        writeCounterValue(raf, next)
                        DataBus.makeWorldAccessible(counterFile, executable = false, writable = true)
                        if (writeEventFile(queue, eventDir, content, next)) {
                            eventSeqCounter.updateAndGet { current -> maxOf(current, next) }
                            next
                        } else {
                            -1L
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event with locked sequence for $queue", e)
            -1L
        }
    }

    private fun nextEventSequence(
        storedSeq: Long,
        queuedSeq: Long,
    ): Long {
        val processSeq = eventSeqCounter.incrementAndGet()
        val floor = maxOf(storedSeq, queuedSeq)
        if (floor == Long.MAX_VALUE) return -1L
        return maxOf(floor + 1L, processSeq)
    }

    /**
     * 读取计数器时区分“文件缺失”和“文件存在但不可读/损坏”。
     *
     * 共享文件锁与写端对同一稳定 inode 的独占锁配对，避免读到 truncate/write
     * 中间态；@Synchronized 同时避免同一 JVM 内出现重叠 FileLock。
     */
    @Synchronized
    fun readSequenceCounterDetailed(queue: String): SequenceCounterRead {
        if (!DataBus.isValidEventQueue(queue)) return SequenceCounterRead.Unreadable
        val counterDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_COUNTERS}").toPath()
        val path = counterDir.resolve("$queue.seq")
        val file = path.toFile()
        return try {
            val directoryAttributes = try {
                Files.readAttributes(counterDir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return SequenceCounterRead.Absent
            }
            if (!directoryAttributes.isDirectory) return SequenceCounterRead.Unreadable
            val counterAttributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return SequenceCounterRead.Absent
            }
            if (!counterAttributes.isRegularFile) return SequenceCounterRead.Unreadable
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    channel.lock(0L, Long.MAX_VALUE, true).use {
                        if (!isRegularCounterFile(path)) {
                            SequenceCounterRead.Unreadable
                        } else {
                            readCounterValue(raf)?.let(SequenceCounterRead::Value)
                                ?: SequenceCounterRead.Unreadable
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sequence counter: $queue", e)
            SequenceCounterRead.Unreadable
        }
    }

    /** 纯解析函数：损坏、空白、负数及溢出都不折算为 0。 */
    internal fun parseSequenceCounterContent(content: String): Long? {
        val normalized = content.trim()
        if (normalized.isEmpty() || normalized.any { !it.isDigit() }) return null
        return normalized.toLongOrNull()?.takeIf { it >= 0L }
    }

    /**
     * 当前队列只能在确实覆盖已提交游标时作为损坏/缺失计数器的恢复依据。
     * 游标缺失表示尚无提交状态；存在但不可读或高于队列顶部时均不能恢复。
     */
    internal fun canRecoverSequenceCounterFromQueue(
        queueMaxSequence: Long?,
        queueHasEvents: Boolean,
        cursor: CursorSequenceRead,
    ): Boolean {
        if (!queueHasEvents || queueMaxSequence == null) return false
        return when (cursor) {
            CursorSequenceRead.Absent -> true
            CursorSequenceRead.Unreadable -> false
            is CursorSequenceRead.Value -> cursor.sequence <= queueMaxSequence
        }
    }

    /** 游标文件名首段为定宽十进制序号；仅此序号用于水位比较。 */
    internal fun parseEventSequence(name: String): Long? =
        DataBusProtocol.EVENT_FILE_NAME_PATTERN.matchEntire(name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    /**
     * 读取游标只为拒绝发号回退。缺失计数器的恢复必须由队列事件本身证明；
     * 若游标高于现存事件，发号方会 fail-closed，不从该游标重建计数器。
     */
    private fun readCursorSequenceForSequenceFloor(queue: String, busRoot: File): CursorSequenceRead {
        val cursorDir = File(busRoot, DataBus.DIR_CURSORS).toPath()
        val path = cursorDir.resolve("$queue.cursor")
        val file = path.toFile()
        return try {
            val directoryAttributes = try {
                Files.readAttributes(cursorDir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return CursorSequenceRead.Absent
            }
            if (!directoryAttributes.isDirectory) return CursorSequenceRead.Unreadable
            val cursorAttributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return CursorSequenceRead.Absent
            }
            if (!cursorAttributes.isRegularFile) {
                return CursorSequenceRead.Unreadable
            }
            val content = file.readText(Charsets.UTF_8).trim()
            if (!DataBusPrune.isCredibleCursorContent(content)) return CursorSequenceRead.Unreadable
            parseEventSequence(content)?.let(CursorSequenceRead::Value) ?: CursorSequenceRead.Unreadable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cursor for sequence floor: $queue", e)
            CursorSequenceRead.Unreadable
        }
    }

    private fun readCounterValue(raf: RandomAccessFile): Long? {
        if (raf.length() <= 0L || raf.length() > 64L) return null
        raf.seek(0)
        val content = ByteArray(raf.length().toInt())
        raf.readFully(content)
        return parseSequenceCounterContent(content.toString(Charsets.UTF_8))
    }

    private fun writeCounterValue(raf: RandomAccessFile, value: Long) {
        raf.setLength(0)
        raf.seek(0)
        raf.write(value.toString().toByteArray(Charsets.UTF_8))
        raf.fd.sync()
    }

    private fun writeEventFile(queue: String, eventDir: File, content: String, seq: Long): Boolean {
        val now = System.currentTimeMillis()
        val pid = Process.myPid()
        val rand = ((Math.random() * 0xFFFF).toInt() and 0xFFFF)
        val filename = String.format("%020d-%d-%d-%04x.json", seq, now, pid, rand)

        val tmpFile = try {
            DataBus.createTempFileIn(eventDir, "$filename-", ".tmp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create event temp file: $queue/$filename", e)
            return false
        }
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
                return false
            }
            DataBus.makeWorldAccessible(targetFile, executable = false, writable = false)
            Log.d(TAG, "Event written: $queue/$filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event to $queue", e)
            tmpFile.delete()
            false
        }
    }

    private fun maxEventSequence(queue: String, busRoot: File): Long? {
        val eventDir = File(File(busRoot, DataBus.DIR_EVENTS), queue)
        return try {
            val attributes = try {
                Files.readAttributes(eventDir.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return 0L
            }
            if (!attributes.isDirectory) return null
            val entries = eventDir.listFiles() ?: return null
            var maxSequence = 0L
            for (entry in entries) {
                if (!DataBusPrune.isEventFile(entry)) continue
                val sequence = parseEventSequence(entry.name) ?: return null
                maxSequence = maxOf(maxSequence, sequence)
            }
            maxSequence
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect event sequence floor for $queue", e)
            null
        }
    }

    private fun hasEventFiles(queue: String, busRoot: File): Boolean? {
        val eventDir = File(File(busRoot, DataBus.DIR_EVENTS), queue)
        return try {
            val attributes = try {
                Files.readAttributes(eventDir.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return false
            }
            if (!attributes.isDirectory) return null
            val entries = eventDir.listFiles() ?: return null
            entries.any(DataBusPrune::isEventFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect event queue for $queue", e)
            null
        }
    }

    private fun isRegularCounterFile(path: java.nio.file.Path): Boolean =
        !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
}
