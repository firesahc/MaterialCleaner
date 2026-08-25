package me.gm.cleaner.runtime.mediaprovider.hook

internal data class SnapshotConsumeOutcome(
    val succeeded: Boolean,
    val changed: Boolean = false,
)

internal data class SignalConsumeResult(
    val acknowledgedTimestamp: Long,
    val changed: Boolean,
)

/**
 * 在读取快照前捕获 signal 水位，避免把消费期间到达的新 signal 错认成已处理。
 */
internal inline fun consumeAfterSignalCapture(
    currentAcknowledgedTimestamp: Long,
    captureSignalTimestamp: () -> Long,
    consumeSnapshot: () -> SnapshotConsumeOutcome,
): SignalConsumeResult {
    val observedBeforeRead = captureSignalTimestamp()
    val outcome = consumeSnapshot()
    return SignalConsumeResult(
        acknowledgedTimestamp = if (outcome.succeeded) {
            maxOf(currentAcknowledgedTimestamp, observedBeforeRead)
        } else {
            currentAcknowledgedTimestamp
        },
        changed = outcome.succeeded && outcome.changed,
    )
}
