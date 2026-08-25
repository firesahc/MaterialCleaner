package me.gm.cleaner.runtime.server.hookbridge

/**
 * App 进程被冻结或重启时，限制无边界的 Bridge Binder 查询频率。
 * 协调器自身仍负责更长周期的退避和冷却。
 */
object HookBridgeReconnectThrottlePolicy {
    const val MIN_ATTEMPT_INTERVAL_MILLIS = 5_000L

    fun shouldAttempt(nowUptimeMs: Long, lastAttemptUptimeMs: Long): Boolean =
        lastAttemptUptimeMs <= 0L ||
                nowUptimeMs - lastAttemptUptimeMs >= MIN_ATTEMPT_INTERVAL_MILLIS
}
