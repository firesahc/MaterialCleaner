package me.gm.cleaner.runtime.server

/** 单个快照及其对应的变更信号。列表顺序同时定义写入和通知顺序。 */
internal data class SnapshotPublication(
    val snapshotName: String,
    val content: String,
    val signalName: String,
)

internal data class SnapshotBatchResult(
    val snapshotsWritten: Boolean,
    val signalsDelivered: Boolean,
) {
    val successful: Boolean
        get() = snapshotsWritten && signalsDelivered
}

/**
 * 多快照批次的提交边界。
 *
 * 文件系统无法原子替换多个文件，因此先逐个原子写入全部快照，再发送任何信号。
 * 写入阶段失败时不发送信号；通知阶段即使单个信号失败，也继续通知其余消费者。
 */
internal object SnapshotBatchCommitter {
    fun commit(
        publications: List<SnapshotPublication>,
        writeSnapshot: (String, String) -> Boolean,
        signal: (String) -> Boolean,
    ): SnapshotBatchResult {
        if (publications.isEmpty()) {
            return SnapshotBatchResult(
                snapshotsWritten = false,
                signalsDelivered = false,
            )
        }
        for (publication in publications) {
            if (!writeSnapshot(publication.snapshotName, publication.content)) {
                return SnapshotBatchResult(
                    snapshotsWritten = false,
                    signalsDelivered = false,
                )
            }
        }

        var signalsDelivered = true
        for (publication in publications) {
            if (!signal(publication.signalName)) {
                signalsDelivered = false
            }
        }
        return SnapshotBatchResult(
            snapshotsWritten = true,
            signalsDelivered = signalsDelivered,
        )
    }
}
