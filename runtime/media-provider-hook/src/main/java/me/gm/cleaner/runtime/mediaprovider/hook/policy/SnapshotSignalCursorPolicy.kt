package me.gm.cleaner.runtime.mediaprovider.hook.policy

import me.gm.cleaner.runtime.mediaprovider.hook.bridge.DataBusBridgeResult

internal data class SnapshotConsumeOutcome(
    val succeeded: Boolean,
    val changed: Boolean = false,
    val unavailableReason: String? = null,
)

internal data class SignalConsumeResult(
    val acknowledgedTimestamp: Long,
    val changed: Boolean,
    val unavailableReason: String? = null,
)

/**
 * 在读取快照前捕获 signal 水位，避免把消费期间到达的新 signal 错认成已处理。
 */
internal inline fun consumeAfterSignalCapture(
    currentAcknowledgedTimestamp: Long,
    captureSignalTimestamp: () -> DataBusBridgeResult<Long>,
    consumeSnapshot: () -> SnapshotConsumeOutcome,
): SignalConsumeResult {
    val observedBeforeRead = when (val signal = captureSignalTimestamp()) {
        is DataBusBridgeResult.Available -> signal.value
        is DataBusBridgeResult.Unavailable -> {
            return SignalConsumeResult(
                acknowledgedTimestamp = currentAcknowledgedTimestamp,
                changed = false,
                unavailableReason = "${signal.operation}:${signal.reason}",
            )
        }
    }
    val outcome = consumeSnapshot()
    return SignalConsumeResult(
        acknowledgedTimestamp = if (outcome.succeeded) {
            maxOf(currentAcknowledgedTimestamp, observedBeforeRead)
        } else {
            currentAcknowledgedTimestamp
        },
        changed = outcome.succeeded && outcome.changed,
        unavailableReason = outcome.unavailableReason,
    )
}
