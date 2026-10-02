package me.gm.cleaner.core.storage.redirect.domain

/**
 * 运行时行为策略：记录、提示与包过滤。
 *
 * P7 收敛：VFS store 不再直接解释记录开关与名单的组合语义，
 * 判定收归此处纯函数；VFS 只做视图委托。发布仍走聚合快照，字段名不变，
 * DataBus JSON 与 Hook 解析不受影响。
 */
data class RuntimeBehaviorPolicy(
    val deniedPackages: Set<String> = emptySet(),
    val recordSharedStorage: Boolean = false,
    val recordExternalAppSpecificStorage: Boolean = false,
    val aggressivelyPromptForReadingMediaFiles: Boolean = false,
    val upsertRecords: Boolean = true,
) {
    /** 外部应用专属存储是否纳入记录：总开关开且包不在名单内。 */
    fun shouldRecordExternal(packageName: String): Boolean =
        recordExternalAppSpecificStorage && packageName !in deniedPackages

    companion object {
        fun project(snapshot: RedirectPolicySnapshot): RuntimeBehaviorPolicy =
            RuntimeBehaviorPolicy(
                deniedPackages = snapshot.denylist,
                recordSharedStorage = snapshot.recordSharedStorage,
                recordExternalAppSpecificStorage = snapshot.recordExternalAppSpecificStorage,
                aggressivelyPromptForReadingMediaFiles = snapshot.aggressivelyPromptForReadingMediaFiles,
                upsertRecords = snapshot.upsertRecords,
            )
    }
}
