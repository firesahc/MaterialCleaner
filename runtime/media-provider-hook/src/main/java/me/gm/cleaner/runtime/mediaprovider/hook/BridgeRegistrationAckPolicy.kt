package me.gm.cleaner.runtime.mediaprovider.hook

/** App Provider 新版本返回显式确认；旧版本无确认字段时保持协议兼容。 */
object BridgeRegistrationAckPolicy {
    @JvmStatic
    fun isAccepted(
        resultAvailable: Boolean,
        acknowledgmentPresent: Boolean,
        registered: Boolean,
    ): Boolean = resultAvailable && (!acknowledgmentPresent || registered)
}
