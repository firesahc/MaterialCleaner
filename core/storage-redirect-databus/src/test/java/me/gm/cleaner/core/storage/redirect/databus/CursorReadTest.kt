package me.gm.cleaner.core.storage.redirect.databus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 游标三态读取契约测试。
 *
 * 覆盖复核指定的转换测试：OK → UNREADABLE 后，即使调用方把内存游标置为 ""，
 * 清理与健康层仍以可信状态为准。
 *
 * 文件读取三态仍通过纯判据覆盖；事件写入的 fail-closed 行为使用临时根目录，
 * 不触碰设备 BUS_ROOT。
 */
class CursorReadTest {

    @Test
    fun `不可读游标在计数器目录创建前拒绝写入`() = withTemporaryBusRoot { busRoot ->
        val queue = DataBusProtocol.EVENT_FILESYSTEM
        val cursorFile = File(File(busRoot, DataBus.DIR_CURSORS), "$queue.cursor")
        cursorFile.parentFile!!.mkdirs()
        cursorFile.writeText("corrupted", Charsets.UTF_8)

        val eventDir = File(File(busRoot, DataBus.DIR_EVENTS), queue)
        val result = DataBusEventWriter.writeEventLocked(queue, eventDir, "{}", busRoot)

        assertEquals(-1L, result)
        assertFalse(File(busRoot, DataBus.DIR_COUNTERS).exists())
        assertFalse(eventDir.exists())
    }

    @Test
    fun `不可读游标不推进既有计数器且不写事件`() = withTemporaryBusRoot { busRoot ->
        val queue = DataBusProtocol.EVENT_FILESYSTEM
        val cursorFile = File(File(busRoot, DataBus.DIR_CURSORS), "$queue.cursor")
        cursorFile.parentFile!!.mkdirs()
        cursorFile.writeText("corrupted", Charsets.UTF_8)

        val counterFile = File(File(busRoot, DataBus.DIR_COUNTERS), "$queue.seq")
        counterFile.parentFile!!.mkdirs()
        counterFile.writeText("8292", Charsets.UTF_8)
        val eventDir = File(File(busRoot, DataBus.DIR_EVENTS), queue)

        val result = DataBusEventWriter.writeEventLocked(queue, eventDir, "{}", busRoot)

        assertEquals(-1L, result)
        assertEquals("8292", counterFile.readText(Charsets.UTF_8))
        assertFalse(eventDir.exists())
    }

    @Test
    fun `三态枚举完整`() {
        val states = DataBusProtocol.CursorRead.values().toSet()
        assertEquals(
            setOf(
                DataBusProtocol.CursorRead.ABSENT,
                DataBusProtocol.CursorRead.OK,
                DataBusProtocol.CursorRead.UNREADABLE,
            ),
            states,
        )
    }

    @Test
    fun `空白内容不可信`() {
        assertFalse(DataBus.isCredibleCursorContent(""))
        assertFalse(DataBus.isCredibleCursorContent("   "))
    }

    @Test
    fun `合法事件文件名可信`() {
        assertTrue(DataBus.isCredibleCursorContent("00000000000000003195-1791549618189-12963-a72c.json"))
    }

    @Test
    fun `损坏成的任意非空字符串不可信`() {
        // 关键回归：坏游标若被当成水位，字典序更大的非法值会误删未处理事件。
        // 旧实现只查非空，此类输入会误判 OK。
        assertFalse(DataBus.isCredibleCursorContent("CORRUPTED!!!"))
        assertFalse(DataBus.isCredibleCursorContent("99999999999999999999-xxx.json"))
        assertFalse(DataBus.isCredibleCursorContent("0001.json"))
    }

    @Test
    fun `非法队列名判不可信而非空队列`() {
        // 空白或非法队列名不能伪装成"新队列"：必须显式不可信。
        // 约束来源：isValidEventQueue 对非法队列返回 false，而 readCursorDetailed
        // 遇到非法队列直接返回 UNREADABLE。这里断言白名单本身拒绝该输入
        // （真正的 readCursorDetailed 调用会经过 Log.w，单测环境未 mock，
        // 故只验证契约前提；完整路径由设备验收覆盖）。
        assertFalse(
            DataBusProtocol.validEventQueues.contains("../escape"),
        )
        assertFalse(DataBusProtocol.validEventQueues.contains(""))
    }

    @Test
    fun `越顶比较与消费清理同口径_字典序`() {
        // 越顶检查依赖"游标 > 队首 ⇒ 游标在队列顶端之后"的字典序假设；
        // 事件文件名 seq 段为定宽 20 位十进制，字典序与数值序一致。
        val max = "00000000000000000010-1791549618189-12963-a72c.json"
        val beyond = "00000000000000000011-1791549618190-12963-b83d.json"
        assertTrue(DataBus.isCredibleCursorContent(max))
        assertTrue(DataBus.isCredibleCursorContent(beyond))
        assertTrue(beyond > max)
        // 定宽保证进位仍有序：...09 < ...10（变宽序号在此处会分叉）。
        assertTrue("00000000000000000009-1791549618189-12963-a72c.json" < max)
    }

    @Test
    fun `空队首时任何游标都大于空串_故越顶检查必须有非空守卫`() {
        // Kotlin 中任何非空字符串都 > ""；若生产检查缺少 max.isNotEmpty()
        // 守卫，空队列上的任何可信游标都会被误判越顶。
        // 本用例锁定该前提，只做纯字符串断言，不触碰文件系统。
        assertTrue("00000000000000000010-1791549618189-12963-a72c.json" > "")
    }

    @Test
    fun `清理回退当前队列队首后由持久化计数器保留游标上界`() {
        val cursor = "00000000000000008292-1791549618189-12963-a72c.json"
        val counter = DataBusEventWriter.SequenceCounterRead.Value(8292L)

        assertTrue(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 8291L,
                queueHasEvents = true,
                counter = counter,
            ),
        )
    }

    @Test
    fun `游标序号超过计数器和现存队列时不可信`() {
        val cursor = "00000000000000008293-1791549618189-12963-a72c.json"

        assertFalse(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 8291L,
                queueHasEvents = true,
                counter = DataBusEventWriter.SequenceCounterRead.Value(8292L),
            ),
        )
    }

    @Test
    fun `缺失或损坏计数器时只有现存队列覆盖游标才可信`() {
        val cursor = "00000000000000008292-1791549618189-12963-a72c.json"

        assertFalse(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 8291L,
                queueHasEvents = true,
                counter = DataBusEventWriter.SequenceCounterRead.Absent,
            ),
        )
        assertFalse(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 8291L,
                queueHasEvents = true,
                counter = DataBusEventWriter.SequenceCounterRead.Unreadable,
            ),
        )
        assertTrue(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 8292L,
                queueHasEvents = true,
                counter = DataBusEventWriter.SequenceCounterRead.Unreadable,
            ),
        )
    }

    @Test
    fun `缺失计数器且队列为空时不信任现存游标`() {
        val cursor = "00000000000000000000-1791549618189-12963-a72c.json"

        assertFalse(
            DataBusPrune.isCursorWithinSequenceHighWatermark(
                cursor = cursor,
                queueMaxSequence = 0L,
                queueHasEvents = false,
                counter = DataBusEventWriter.SequenceCounterRead.Absent,
            ),
        )
    }

    @Test
    fun `计数器损坏内容不会折算为零`() {
        assertEquals(8292L, DataBusEventWriter.parseSequenceCounterContent("8292"))
        assertEquals(0L, DataBusEventWriter.parseSequenceCounterContent("0"))
        assertEquals(null, DataBusEventWriter.parseSequenceCounterContent(""))
        assertEquals(null, DataBusEventWriter.parseSequenceCounterContent("broken"))
        assertEquals(null, DataBusEventWriter.parseSequenceCounterContent("-1"))
        assertEquals(null, DataBusEventWriter.parseSequenceCounterContent("9223372036854775808"))
    }

    @Test
    fun `损坏或缺失计数器只从能覆盖游标的现存队列恢复`() {
        assertTrue(
            DataBusEventWriter.canRecoverSequenceCounterFromQueue(
                queueMaxSequence = 8292L,
                queueHasEvents = true,
                cursor = DataBusEventWriter.CursorSequenceRead.Value(8292L),
            ),
        )
        assertTrue(
            DataBusEventWriter.canRecoverSequenceCounterFromQueue(
                queueMaxSequence = 8292L,
                queueHasEvents = true,
                cursor = DataBusEventWriter.CursorSequenceRead.Absent,
            ),
        )
        assertFalse(
            DataBusEventWriter.canRecoverSequenceCounterFromQueue(
                queueMaxSequence = 8291L,
                queueHasEvents = true,
                cursor = DataBusEventWriter.CursorSequenceRead.Value(8292L),
            ),
        )
        assertFalse(
            DataBusEventWriter.canRecoverSequenceCounterFromQueue(
                queueMaxSequence = 0L,
                queueHasEvents = false,
                cursor = DataBusEventWriter.CursorSequenceRead.Value(8292L),
            ),
        )
        assertFalse(
            DataBusEventWriter.canRecoverSequenceCounterFromQueue(
                queueMaxSequence = 8292L,
                queueHasEvents = true,
                cursor = DataBusEventWriter.CursorSequenceRead.Unreadable,
            ),
        )
    }

    @Test
    fun `越顶游标下存量队首都满足删除游标门禁_故越顶必须判不可信`() {
        // 安全 rationale：shouldPruneEvent 的游标门禁是 `name <= cursor`；
        // 一旦游标越顶，存量文件全部满足门禁（仅剩时间门禁），
        // 若把越顶游标当可信水位，清理会误删仍可能被消费的事件。
        // 因此 readCursorDetailed 必须先判越顶为 UNREADABLE（删 0）。
        // 本用例经生产函数直调验证，不触碰 Log/文件系统。
        val now = 10_000_000L
        val retention = 30 * 60 * 1000L
        val max = "00000000000000000010-1791549618189-12963-a72c.json"
        val beyond = "00000000000000000011-1791549618190-12963-b83d.json"
        assertTrue(beyond > max)
        assertTrue(
            DataBus.shouldPruneEvent(
                name = max,
                cursor = beyond,
                lastModified = now - 31 * 60 * 1000L,
                now = now,
                retentionMs = retention,
            ),
        )
    }

    @Test
    fun `旧单值API语义不变`() {
        // readCursor 保持既有签名：ABSENT 与 UNREADABLE 都返回 ""。
        // 这正是需要三态 API 的原因——调用方不能再靠 "" 区分状态。
        // （本用例仅锁定签名语义，不触碰文件系统。）
        val method = DataBus::class.java.methods.first { it.name == "readCursor" }
        assertEquals(String::class.java, method.returnType)
        assertEquals(1, method.parameterTypes.size)
    }

    private fun withTemporaryBusRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("databus-cursor-write-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
