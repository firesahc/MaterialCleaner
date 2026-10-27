package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NativeSyncPolicy 纯函数单测：每个裁决都必须有可验证证据。
 *
 * 覆盖历史缺陷：
 * - generation 跨 epoch 比较导致误判同步；
 * - 失败 attempt 的时间戳被当作收敛证据（永久 RECOVERING）；
 * - 旧 epoch 的 attempt 被当作新配置进展；
 * - 时钟回拨使负差值被视为新鲜。
 */
class NativeSyncPolicyTest {

    private val now = 10_000_000L

    private fun base() = NativeSyncPolicy.Input(
        snapshotEpoch = "epoch-new",
        snapshotGen = 7L,
        appliedEpoch = "epoch-new",
        appliedGen = 7L,
        lastApplySuccess = true,
        attemptEpoch = "epoch-new",
        attemptGen = 7L,
        attemptAt = now - 1_000L,
        applicationState = "APPLIED",
        now = now,
    )

    @Test
    fun `已应用当前epoch且generation达标判SYNCED`() {
        assertEquals(NativeSyncPolicy.Verdict.SYNCED, NativeSyncPolicy.evaluate(base()))
    }

    @Test
    fun `Bridge读取故障时即使保留旧成功应用也不得判SYNCED`() {
        val input = base().copy(snapshotFreshnessState = "STALE")
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `旧状态缺少freshness证据时不得判SYNCED`() {
        val input = base().copy(snapshotFreshnessState = "UNKNOWN")
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `旧epoch generation更大也不得判SYNCED`() {
        // 最初的事故：旧 epoch 下 nativeGen=12 > 新 snapshotGen=7，但 Native 持有旧配置。
        // 关键断言是“不得 SYNCED”；此处存在针对当前配置的执行中 attempt，故判收敛中。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 12L, lastApplySuccess = true,
            applicationState = "APPLYING",
        )
        assertEquals(NativeSyncPolicy.Verdict.CONVERGING, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `旧epoch generation更大且无新attempt判STALE`() {
        // 同上场景但没有当前配置的 attempt 证据：无进展证据，必须过期。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 12L, lastApplySuccess = true,
            attemptEpoch = "epoch-old", attemptGen = 12L, attemptAt = 0L,
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `同身份新鲜attempt且正在应用判CONVERGING`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "APPLYING",
        )
        assertEquals(NativeSyncPolicy.Verdict.CONVERGING, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `attempt已失败落到STALE不得判收敛中`() {
        // 核心漏洞：attempt 失败后 state=STALE，但时间戳仍新鲜、身份仍一致，
        // 旧实现会判 CONVERGING，把"已经失败"描述成"正在恢复"。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "STALE",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `PENDING非进行态不得判收敛中`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "PENDING",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `APPLIED但尚未发起新attempt不得判收敛中`() {
        // 上一次已成功（旧配置），快照已更新但新 attempt 未启动：
        // 近期 attempt 时间是旧配置的历史，不是当前配置的执行证据。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "APPLIED",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `NO_RULE状态按未同步处理`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "NO_RULE",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `snapshot已知epoch而attempt无epoch不得接受`() {
        // 兼容边界：旧版 hook 状态没有 attemptEpoch 字段。snapshot 已知 epoch 时
        // 无法证明 attempt 服务的是当前配置，不得仅凭 generation 接受。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            attemptEpoch = "", attemptGen = 7L, applicationState = "APPLYING",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `双方均无epoch时按generation降级判断`() {
        // 旧协议降级路径仍可用（snapshot 与 attempt 都无 epoch）
        val input = base().copy(
            snapshotEpoch = "", appliedEpoch = "", attemptEpoch = "",
            appliedGen = 3L, lastApplySuccess = false,
            applicationState = "APPLYING",
        )
        assertEquals(NativeSyncPolicy.Verdict.CONVERGING, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `attempt已失败置UNSUPPORTED不得报收敛中`() {
        // 旧实现只要 attempt 时间新鲜就 RECOVERING，平台不支持时永久转圈。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "UNSUPPORTED",
        )
        assertEquals(NativeSyncPolicy.Verdict.UNSUPPORTED, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `UNSUPPORTED优先于新鲜attempt`() {
        val input = base().copy(
            lastApplySuccess = false, applicationState = "UNSUPPORTED",
        )
        assertEquals(NativeSyncPolicy.Verdict.UNSUPPORTED, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `attempt目标是旧epoch不得当作当前进展`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            attemptEpoch = "epoch-old", attemptGen = 3L, applicationState = "APPLIED",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `attempt时间晚于now不得视为新鲜`() {
        // 时钟回拨或异常来源时间戳：负差值过去会被 < WINDOW 命中。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            attemptAt = now + 60_000L, applicationState = "APPLYING",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `从未尝试判STALE`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            attemptAt = 0L, applicationState = "PENDING",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `attempt超过收敛窗口判STALE`() {
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            attemptAt = now - NativeSyncPolicy.CONVERGENCE_WINDOW_MS - 1L,
            applicationState = "APPLIED",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `UNSUPPORTED优先于旧成功记录`() {
        // 契约封闭：纯策略必须对任意输入总计。hook 侧 markMountPointsApplyUnsupported
        // 会同步清除 success 记录，故该组合本不可达；仍按终态优先排序，
        // 防止旧成功记录掩盖平台能力终态。
        val input = base().copy(
            lastApplySuccess = true, applicationState = "UNSUPPORTED",
        )
        assertEquals(NativeSyncPolicy.Verdict.UNSUPPORTED, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `applicationState未知时按未同步处理`() {
        // 无法证明正在执行 → 不得判收敛中。
        val input = base().copy(
            appliedEpoch = "epoch-old", appliedGen = 3L, lastApplySuccess = false,
            applicationState = "",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `snapshotEpoch未知时不得以generation比较判SYNCED`() {
        // snapshotEpoch 为空、appliedEpoch 为旧 epoch：身份无法证明，
        // 不得仅凭 generation 比较判定当前配置已同步。
        val input = base().copy(
            snapshotEpoch = "", snapshotGen = 7L,
            appliedEpoch = "epoch-old", appliedGen = 12L, lastApplySuccess = true,
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `generation落后且无attempt证据判STALE`() {
        val input = base().copy(appliedGen = 6L, attemptAt = 0L)
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `generation落后但有当前attempt判CONVERGING`() {
        // 上次成功应用 gen=6、快照 gen=7，且存在针对 gen=7 的执行中 attempt：
        // 同步机制正在处理当前配置，收敛中是正确的。
        val input = base().copy(appliedGen = 6L, applicationState = "APPLYING")
        assertEquals(NativeSyncPolicy.Verdict.CONVERGING, NativeSyncPolicy.evaluate(input))
    }

    @Test
    fun `双侧epoch均为空且旧成功记录generation更大时不得判SYNCED`() {
        // 双侧 epoch 均为空时，身份无法证明，不能仅凭 generation 比较判定当前配置已同步。
        // 即使 lastApplySuccess 为 true 且 appliedGen > snapshotGen，也必须返回 STALE。
        val input = base().copy(
            snapshotEpoch = "", snapshotGen = 7L,
            appliedEpoch = "", appliedGen = 12L, lastApplySuccess = true,
            applicationState = "APPLIED",
        )
        assertEquals(NativeSyncPolicy.Verdict.STALE, NativeSyncPolicy.evaluate(input))
    }
}
