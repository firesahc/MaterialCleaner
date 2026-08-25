package me.gm.cleaner.core.common.err

/**
 * 结构化错误事件：全项目错误上报的最小标准载体。
 *
 * 设计约束：
 * - 纯 Kotlin 数据类，不携带任何序列化依赖；JSON 化由各进程侧消费方自行完成
 *   （hook 侧并入 [me.gm.cleaner.runtime.mediaprovider.hook.NativeHookStatus] 快照段，
 *   server 侧由 DiagnosticArchive / OrchestratedStatus 聚合导出）。
 * - [pathDigest] 是路径的 SHA-256 前 8 字节十六进制摘要而非明文，
 *     使错误事件可以安全进入快照与诊断包；明文路径仅允许出现在 debug 日志。
 * - [atElapsed] 必须取自单调时钟（SystemClock.elapsedRealtime 或等价物），
 *     规避墙钟回拨导致的事件排序错乱。
 */
data class ErrorEvent(
    /** [ErrorCodes] 注册表常量，机器可读的分类键。 */
    val code: String,
    /** 原生 errno；0 表示不适用。 */
    val errno: Int = 0,
    /** 故障主体：packageName 或 "uid:<n>" / "pid:<n>"；无主体时为 null。 */
    val subject: String? = null,
    /** 相关路径的脱敏摘要；无关联路径时为 null。 */
    val pathDigest: String? = null,
    /** 发生时已知的策略代际；0 表示尚无代际概念。 */
    val generation: Long = 0L,
    /** 单调时钟时间戳（毫秒）。 */
    val atElapsed: Long,
    /** 受控自由文本补充（规则 source→target、失败下标列表等）。 */
    val detail: String? = null,
) {
    init {
        require(code.isNotEmpty()) { "error code must not be empty" }
        require(atElapsed >= 0L) { "atElapsed must be non-negative" }
    }

    /**
     * 供日志与 journal 使用的一行紧凑表示。
     * 不含明文路径，可直接写入持久化载体。
     */
    fun toCompactString(): String = buildString {
        append(code)
        if (errno != 0) append(" errno=").append(errno)
        subject?.let { append(" subject=").append(it) }
        pathDigest?.let { append(" path=").append(it) }
        if (generation > 0L) append(" gen=").append(generation)
        detail?.let { append(" detail=").append(it) }
    }
}
