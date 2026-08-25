package me.gm.cleaner.runtime.mediaprovider.hook

/** MediaProvider 向 App Bridge 注册 Binder 时使用的有界断路器策略。 */
object BridgeRegistrationRetryPolicy {
    const val MAX_BURST_ATTEMPTS = 6
    const val COOLDOWN_MILLIS = 5 * 60_000L

    @JvmStatic
    fun delayMillis(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 5)
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }

    @JvmStatic
    fun isBurstExhausted(failedAttempts: Int): Boolean =
        failedAttempts >= MAX_BURST_ATTEMPTS
}
