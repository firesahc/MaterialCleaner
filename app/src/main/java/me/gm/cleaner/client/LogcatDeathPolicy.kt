package me.gm.cleaner.client

/**
 * logcat 观察器真死（serverException=2）的 App 侧监督决策纯函数。
 *
 * 背景：cleaner_server 进程活着但 logcat 观察线程已不可逆退出时，
 * Binder ping 依然成功，假死看门狗无法发现。此时 CleanerService.getServerException()
 * 返回 2，App 侧看门狗轮询命中后复用既有 kill+recover 闭环重启整个 server
 * 进程（server 内不另建 Observer 重建路径，与架构铁律一致）。
 *
 * 本对象不依赖 Android 框架，便于 JVM 单测覆盖轮询触发与豁免逻辑。
 */
object LogcatDeathPolicy {
    /** server 正常。 */
    const val EXCEPTION_NONE = 0

    /** logcat 观察器真死：ActivityManagerLogsObserver 线程已退出，不可逆。 */
    const val EXCEPTION_LOGOAT_SHUTDOWN = 2

    /** 启动期：尚未观测到 AmStart，不是故障，看门狗与通知必须豁免。 */
    const val EXCEPTION_NO_AM_START_YET = 3

    /** logcat 真死通知去重窗口：窗口内重复广播只提示一次。 */
    const val NOTIFY_DEDUP_MS = 10 * 60 * 1000L

    /**
     * 由单次轮询结果推进连续命中计数。
     *
     * - 2 → 计数加一；
     * - 3（启动期豁免）→ 复位为 0，不计数；
     * - 其他（含 0 正常与 null 未知）→ 复位为 0。
     */
    fun nextDeadCount(current: Int, exception: Int?): Int =
        if (exception == EXCEPTION_LOGOAT_SHUTDOWN) current + 1 else 0

    /** 连续命中达到阈值（沿用看门狗 5s 周期/连续 3 次基线）即触发重启。 */
    fun shouldRestart(deadCount: Int, threshold: Int = 3): Boolean =
        deadCount >= threshold

    /**
     * logcat 真死通知是否允许弹出。
     *
     * - 去重窗口内一律抑制；
     * - 3（启动期）与 0（已恢复）等明确非 2 的状态一律抑制，避免启动期误报；
     * - null（Binder 已不可用、状态未知）fail-open：server 广播本身即是证据，允许提示。
     */
    fun shouldNotify(
        exception: Int?,
        lastNotifiedElapsed: Long,
        nowElapsed: Long,
        dedupMs: Long = NOTIFY_DEDUP_MS,
    ): Boolean {
        if (nowElapsed - lastNotifiedElapsed < dedupMs) return false
        if (exception == null) return true
        return exception == EXCEPTION_LOGOAT_SHUTDOWN
    }
}
