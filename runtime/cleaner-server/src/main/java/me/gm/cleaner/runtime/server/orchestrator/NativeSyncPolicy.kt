package me.gm.cleaner.runtime.server.orchestrator

/**
 * Native 挂载点同步裁决（P2，纯函数）。
 *
 * 职责：只根据观测事实判定“当前配置是否已被 Native 持有 / 是否正在收敛 /
 * 已过期 / 平台不支持”，不触碰 DataBus、Binder 或时间来源之外的状态。
 * 事实采集归 [me.gm.cleaner.runtime.server.orchestrator.NativeHookLayerReporter]
 * 与 hook 侧 NativeHookStatus。
 *
 * 设计约束：每个裁决都必须有可验证证据，禁止用单一时间戳冒充进展。
 * - 身份：attempt 必须绑定当前快照的 publisherEpoch 与 generation，
 *   旧 epoch 的尝试不能当作新配置的进展（generation 数值不可跨 epoch 比较）；
 * - 执行：CONVERGING 必须证明“当前仍在执行”（applicationState == APPLYING），
 *   仅有 attempt 历史不够——失败后状态落到 STALE/PENDING/APPLIED 时，
 *   近期时间戳不能冒充进展；
 * - 终态：UNSUPPORTED 是平台明确不支持，持续重试也不得报恢复中；
 * - 时间：attemptAt 必须 sane（0 < at <= now），时钟回拨不得被解释为新鲜。
 */
internal object NativeSyncPolicy {

    enum class Verdict {
        /** 当前 epoch 已成功应用且 generation 达标。 */
        SYNCED,

        /** 针对当前配置身份的 attempt 正在执行且证据新鲜。 */
        CONVERGING,

        /** 无新鲜的同身份 attempt 证据，或应用已失败/未在执行。 */
        STALE,

        /** 平台明确不支持，终态。 */
        UNSUPPORTED,
    }

    data class Input(
        val snapshotEpoch: String,
        val snapshotGen: Long,
        /** Native 已确认持有的 epoch（仅成功应用时更新）。 */
        val appliedEpoch: String,
        val appliedGen: Long,
        val lastApplySuccess: Boolean,
        /** 最近一次 attempt 的目标身份与开始时间。 */
        val attemptEpoch: String,
        val attemptGen: Long,
        val attemptAt: Long,
        /** hook 侧 applicationState：APPLYING / APPLIED / STALE / UNSUPPORTED / PENDING / NO_RULE。 */
        val applicationState: String,
        val now: Long,
        /** mount points 快照读取 freshness；未知或过期时不能判 SYNCED。 */
        val snapshotFreshnessState: String = "CURRENT",
    )

    fun evaluate(i: Input): Verdict {
        // UNSUPPORTED 优先于一切：平台能力终态。旧成功记录不得掩盖它——
        // hook 侧 markMountPointsApplyUnsupported 会同步清除 success 记录，
        // 因此该组合本不可达；此处仍按终态优先排序，使策略对任意输入总计。
        if (i.applicationState == "UNSUPPORTED") {
            return Verdict.UNSUPPORTED
        }
        if (i.snapshotFreshnessState != "CURRENT") {
            return Verdict.STALE
        }
        // epoch 身份必须可验证才能比较 generation：snapshot 与 applied 均有值且相等时，
        // 才属于同一身份域，允许继续比较 generation。snapshotEpoch 为空时不再放行，
        // 避免旧 epoch 的高 generation 被误判为当前配置已同步。
        val epochConsistent = i.snapshotEpoch.isNotBlank() &&
                i.appliedEpoch == i.snapshotEpoch
        val genSatisfied = i.snapshotGen <= 0L || i.appliedGen >= i.snapshotGen
        if (i.lastApplySuccess && epochConsistent && genSatisfied) {
            return Verdict.SYNCED
        }
        // CONVERGING 必须证明“当前仍在执行”。仅有 attempt 历史不足以证明进展：
        // 失败后 applicationState 落到 STALE/PENDING，或上一次成功后尚未发起新尝试，
        // 此时近期时间戳只是历史记录，不是执行证据。
        if (i.applicationState == "APPLYING" &&
            attemptTargetsCurrentConfig(i) && isFreshAttempt(i)) {
            return Verdict.CONVERGING
        }
        return Verdict.STALE
    }

    /** attempt 目标必须与当前快照同一身份，无法证明身份一致时不得接受。 */
    private fun attemptTargetsCurrentConfig(i: Input): Boolean {
        // snapshot 已知 epoch 而 attempt 无 epoch（旧版 hook 状态）：身份未证明，
        // 不得仅凭 generation 数值接受为当前配置的进展。
        if (i.snapshotEpoch.isNotBlank() && i.attemptEpoch.isBlank()) return false
        // 双方均为旧协议（都无 epoch）：按 generation 降级比较。
        if (i.snapshotEpoch.isBlank() && i.attemptEpoch.isBlank()) {
            return i.snapshotGen <= 0L || i.attemptGen >= i.snapshotGen
        }
        if (i.attemptEpoch != i.snapshotEpoch) return false
        return i.snapshotGen <= 0L || i.attemptGen >= i.snapshotGen
    }

    /**
     * 时间戳必须 sane 且新鲜：
     * - attemptAt > 0（从未尝试 → 无证据）；
     * - attemptAt <= now（晚于 now 说明时钟回拨或异常来源，负差值不得视为新鲜）；
     * - 落在收敛窗口内。
     */
    private fun isFreshAttempt(i: Input): Boolean {
        if (i.attemptAt <= 0L || i.attemptAt > i.now) return false
        return i.now - i.attemptAt < CONVERGENCE_WINDOW_MS
    }

    /** 与 hook 侧刷新调度节拍匹配的收敛窗口：一次新鲜 attempt 即视为正在推进。 */
    const val CONVERGENCE_WINDOW_MS = 60_000L
}
