package me.gm.cleaner.core.storage.redirect.databus

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * DataBus 事件保留与清理（游标读取三态 + 物理删除）。
 *
 * 职责边界：本对象拥有**事件文件的分类判定、游标可信读取与清理执行**；
 * 清理策略（保留期、何时触发）归消费侧，删除授权语义见各函数契约。
 * 低层目录常量与原子写原语复用 [DataBus] 的模块内可见成员。
 *
 * 自 [DataBus] 抽离以控制单文件粒度（G2 门禁）。[DataBus] 保留同名
 * 公开委托，跨模块调用方无需改动。
 */
internal object DataBusPrune {

    private const val TAG = "DataBusPrune"

    /**
     * 事件文件判定：**消费与清理共用的唯一口径**。
     *
     * 规则为「常规文件（不跟随软链）+ `.json` 后缀」。
     * `readEventFiles`（消费）、清理与 pending 统计都必须通过本函数判定，
     * 不得各自实现近似规则——否则清理会漏掉消费端能读到的文件，
     * 导致无界增长换个形式复发。
     *
     * `.tmp` 临时文件、游标文件、子目录均不满足本判定，因此不会被清理触及。
     */
    internal fun isEventFile(file: File): Boolean =
        isRegularFileNoFollow(file) && file.name.endsWith(".json")

    /**
     * 隔离证据文件判定：与源事件是两种东西，分开表达。
     *
     * 隔离文件名形如 `<源事件名>.quarantine.json`，仅本目录由 server 独占写入。
     * 源事件三处（读取/清理/pending）保持宽口径一致，不在此收紧——
     * 收紧消费侧是行为变更，需另配套"未知文件"计数，本轮不做。
     */
    internal fun isQuarantineEvidenceFile(file: File): Boolean =
        isRegularFileNoFollow(file) && file.name.endsWith(".quarantine.json")

    private fun isRegularFileNoFollow(file: File): Boolean =
        DataBus.isRegularFileNoFollow(file)

    /**
     * 读取持久化消费游标的可信状态与值。
     *
     * 不复用 `readRegularText`：该函数把「路径不存在」「路径存在但非常规文件」
     * 「读取抛异常」三种情况全部塌缩为 null，使 ABSENT 与 UNREADABLE 无法区分。
     * 这里必须自己判定存在性，才能把状态交还给清理与健康层。
     *
     * 内容可信判定见 [isCredibleCursorContent]：空白或格式非法一律不可信。
     */
    fun readCursorDetailed(queue: String): Pair<DataBusProtocol.CursorRead, String> {
        if (!DataBus.isValidEventQueue(queue)) {
            return DataBusProtocol.CursorRead.UNREADABLE to ""
        }
        val cursorFile = File("${DataBus.BUS_ROOT}/${DataBus.DIR_CURSORS}/$queue.cursor")
        return try {
            val path = cursorFile.toPath()
            val cursorAttributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                // 确定不存在：新队列
                return DataBusProtocol.CursorRead.ABSENT to ""
            }
            if (!cursorAttributes.isRegularFile) {
                // 存在但无法确认内容（软链/目录）：不可信
                Log.w(TAG, "Cursor is not a regular file: $queue")
                return DataBusProtocol.CursorRead.UNREADABLE to ""
            }
            val content = cursorFile.readText(Charsets.UTF_8).trim()
            if (!isCredibleCursorContent(content)) {
                Log.w(TAG, "Cursor unreadable (blank or illegal format): $queue")
                return DataBusProtocol.CursorRead.UNREADABLE to ""
            }
            // 队列当前最大事件会随合法清理回退，不能单独当作历史上界。
            // counters/{queue}.seq 保留已分配序号，游标只比较文件名中的序号前缀。
            // 没有可信计数器时，仅允许当前队列中的事件证明游标未越顶。
            val max = getLastEventFilenameStrict(queue)
            if (max == null) {
                Log.w(TAG, "Cursor credibility unknown (queue listing failed): $queue")
                return DataBusProtocol.CursorRead.UNREADABLE to ""
            }
            val queueHasEvents = max.isNotEmpty()
            val queueMaxSequence = if (queueHasEvents) {
                DataBusEventWriter.parseEventSequence(max)
            } else {
                0L
            }
            val counter = DataBusEventWriter.readSequenceCounterDetailed(queue)
            if (!isCursorWithinSequenceHighWatermark(
                    cursor = content,
                    queueMaxSequence = queueMaxSequence,
                    queueHasEvents = queueHasEvents,
                    counter = counter,
                )
            ) {
                Log.w(TAG, "Cursor beyond queue top, treating as unreadable: $queue")
                return DataBusProtocol.CursorRead.UNREADABLE to ""
            }
            DataBusProtocol.CursorRead.OK to content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cursor: $queue", e)
            DataBusProtocol.CursorRead.UNREADABLE to ""
        }
    }

    /**
     * 在区分真实缺失与不可读的前提下读取队列顶部。
     * 事件资格仍严格复用 [isEventFile]，与消费和清理使用同一口径。
     */
    private fun getLastEventFilenameStrict(queue: String): String? {
        val eventDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue")
        return try {
            val attributes = try {
                Files.readAttributes(
                    eventDir.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return ""
            }
            if (!attributes.isDirectory) return null
            val entries = eventDir.listFiles() ?: return null
            entries.asSequence()
                .filter(::isEventFile)
                .maxByOrNull { it.name }
                ?.name ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect event queue: $queue", e)
            null
        }
    }

    /**
     * 游标内容可信判定（纯函数）。
     *
     * 规则：非空 **且** 完整匹配事件文件名格式。游标由 tmp→fsync→rename
     * 原子写入正常事件文件名，空白或格式非法一律不可信——前者是异常产物，
     * 后者（如损坏成的任意字符串）若当作水位，可能误删尚未处理的事件。
     *
     * 抽取为纯函数的原因：文件存在性/常规性判定依赖真实文件系统，
     * JVM 单测无法隔离；内容判定是纯字符串逻辑，必须可测。
     */
    internal fun isCredibleCursorContent(content: String): Boolean {
        if (content.isEmpty()) return false
        return DataBusProtocol.EVENT_FILE_NAME_PATTERN.matchEntire(content) != null
    }

    /**
     * 游标可信上界：当前队列最大序号与持久化发号计数器的较大值。
     *
     * 缺失/损坏计数器不能折算成 0 后继续授权：只有当前队列存在可解析事件，
     * 且该事件的序号覆盖游标时，才可用现存队列作兼容性证明。队列空或游标
     * 高于现存队列时仍返回不可信，清理入口因此删除 0 个源事件。
     */
    internal fun isCursorWithinSequenceHighWatermark(
        cursor: String,
        queueMaxSequence: Long?,
        queueHasEvents: Boolean,
        counter: DataBusEventWriter.SequenceCounterRead,
    ): Boolean {
        val cursorSequence = DataBusEventWriter.parseEventSequence(cursor) ?: return false
        val upperBound = when (counter) {
            is DataBusEventWriter.SequenceCounterRead.Value ->
                maxOf(counter.sequence, queueMaxSequence ?: 0L)

            DataBusEventWriter.SequenceCounterRead.Absent,
            DataBusEventWriter.SequenceCounterRead.Unreadable -> {
                if (!queueHasEvents || queueMaxSequence == null) return false
                queueMaxSequence
            }
        }
        return cursorSequence <= upperBound
    }

    /**
     * 读取持久化消费游标。
     *
     * 既有签名与语义保持不变：ABSENT 与 UNREADABLE 都返回 ""。
     * 需要区分二者时使用 [readCursorDetailed]。
     */
    fun readCursor(queue: String): String =
        readCursorDetailed(queue).second

    /**
     * 清理队列中「已消费且超过保留期」的源事件文件。
     *
     * 安全前提（见 AGENTS.md 第 4~6 条）：
     * - 推进协议保证「cursor 前移 ⟹ 副作用完成 + 归档/隔离完成 + 游标原子写成功」，
     *   因此 `name <= cursor` 的事件必已被处理，删除不破坏 at-least-once；
     * - 游标不可信时删除 0 个文件，并把状态原样交还调用方；
     * - 已接受的保证边界：游标损坏后不承诺完整历史重放，清理会缩短可恢复历史。
     *
     * @param retentionMs 保留期；0 表示仅受游标约束、不受时间约束（测试用）
     * @param maxDeletePerRun 限制单轮**成功删除**数量（删除失败不阻断后续）。
     *   注意：目录枚举本身是全量的（`java.io.File` 无流式 API），本参数约束的是
     *   删除动作数而非扫描量；千级文件枚举成本可忽略，不伪装成扫描上限。
     */
    fun pruneQueueEvents(
        queue: String,
        retentionMs: Long,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult {
        if (!DataBus.isValidEventQueue(queue)) return DataBusProtocol.PruneResult.rejected()
        if (retentionMs < 0L || maxDeletePerRun <= 0) {
            return DataBusProtocol.PruneResult.rejected()
        }
        // 删除授权：只有可信游标才能作为水位。不可信时删 0 并暴露状态。
        val (cursorRead, cursor) = readCursorDetailed(queue)
        if (cursorRead != DataBusProtocol.CursorRead.OK) {
            return DataBusProtocol.PruneResult(0, 0, 0, cursorRead)
        }
        val eventDir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue")
        if (!eventDir.isDirectory) {
            return DataBusProtocol.PruneResult(0, 0, 0, cursorRead)
        }
        // 目录存在但无法枚举：不能伪装成空目录，否则失败不可观测。
        val entries = eventDir.listFiles()
            ?: return DataBusProtocol.PruneResult(0, 0, 1, cursorRead)
        val files = entries.filter { isEventFile(it) }
        val now = System.currentTimeMillis()
        var scanned = 0
        var deleted = 0
        var failed = 0
        for (file in files) {
            if (deleted >= maxDeletePerRun) break
            scanned++
            if (!shouldPruneEvent(file.name, cursor, file.lastModified(), now, retentionMs)) {
                continue
            }
            try {
                if (file.delete()) deleted++ else failed++
            } catch (e: Exception) {
                failed++
                LogPruneThrottle.warn("prune event delete failed: $queue/${file.name}")
            }
        }
        return DataBusProtocol.PruneResult(scanned, deleted, failed, cursorRead)
    }

    /**
     * 清理隔离目录中超过保留期的毒丸证据文件。
     *
     * 与源事件清理不同：隔离证据不依赖消费游标，只受保留期与文件规则约束，
     * 因此结果中的 cursorRead 恒为 null。
     */
    fun pruneQuarantine(
        queue: String,
        retentionMs: Long,
        maxDeletePerRun: Int = 500,
    ): DataBusProtocol.PruneResult {
        if (!DataBus.isValidEventQueue(queue)) return DataBusProtocol.PruneResult.rejected()
        if (retentionMs < 0L || maxDeletePerRun <= 0) {
            return DataBusProtocol.PruneResult.rejected()
        }
        val dir = File("${DataBus.BUS_ROOT}/${DataBus.DIR_EVENTS}/$queue.quarantine")
        if (!dir.isDirectory) {
            return DataBusProtocol.PruneResult(0, 0, 0, null)
        }
        val entries = dir.listFiles()
            ?: return DataBusProtocol.PruneResult(0, 0, 1, null)
        val files = entries.filter { isQuarantineEvidenceFile(it) }
        val threshold = System.currentTimeMillis() - retentionMs
        var scanned = 0
        var deleted = 0
        var failed = 0
        for (file in files) {
            if (deleted >= maxDeletePerRun) break
            scanned++
            if (file.lastModified() >= threshold) continue
            try {
                if (file.delete()) deleted++ else failed++
            } catch (e: Exception) {
                failed++
                LogPruneThrottle.warn("prune quarantine delete failed: $queue/${file.name}")
            }
        }
        return DataBusProtocol.PruneResult(scanned, deleted, failed, null)
    }

    /**
     * 源事件删除判定（纯函数，安全契约的可测载体）。
     *
     * 规则：
     * 1. `name > cursor` 一律不删——未越过提交点，即使超过保留期也不行；
     * 2. `name <= cursor` 才可能删除；游标为空（""）时任何非空文件名都 `> ""`，
     *    因此空游标天然删 0 条；
     * 3. `retentionMs == 0` 走显式特殊分支：仅受游标约束，不做时间比较。
     */
    internal fun shouldPruneEvent(
        name: String,
        cursor: String,
        lastModified: Long,
        now: Long,
        retentionMs: Long,
    ): Boolean {
        if (name > cursor) return false
        if (retentionMs == 0L) return true
        return lastModified < now - retentionMs
    }

    /** 清理日志限频：避免同一文件反复失败时刷屏。 */
    private object LogPruneThrottle {
        private var lastAt = 0L
        private const val INTERVAL_MS = 60_000L

        fun warn(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastAt < INTERVAL_MS) return
            lastAt = now
            Log.w(TAG, message)
        }
    }
}
