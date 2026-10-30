package me.gm.cleaner.runtime.mediaprovider.hook.policy

/**
 * 可能的关键 FUSE Java 方法候选清单。并非所有设备都必备，
 * 最终适用集合由运行时扫描结果与本清单取交集决定。
 */
internal val CANDIDATE_CRITICAL_METHODS: Set<String> = setOf(
    "openWithFuse",
    "renameForFuse",
    "isDirAccessAllowedForFuse",
    "isDirectoryCreationOrDeletionAllowedForFuse",
    "insertFileIfNecessaryForFuse",
    "deleteFileForFuse",
)

/**
 * 基于 FuseJavaGate 运行时扫描结果，计算当前设备实际适用的关键方法集合。
 * 仅当方法出现在设备扫描到的四类方法（hooked/unknown/uidSkipped/failed）中时，
 * 才认为该方法在当前设备上存在并应参与覆盖判定；设备上根本不存在的方法不列入。
 */
internal fun applicableCriticalMethods(
    hookedMethods: List<String>,
    unknownMethods: List<String>,
    uidSkippedMethods: List<String>,
    failedMethods: List<String>,
): Set<String> {
    val discovered = (hookedMethods + unknownMethods + uidSkippedMethods + failedMethods)
        .map { it.trim().substringBefore(' ').substringBefore('(') }.toSet()
    return CANDIDATE_CRITICAL_METHODS.intersect(discovered)
}

/**
 * FUSE Java Gate 覆盖状态摘要，由 NativeHookStatus 持有并序列化进快照。
 */
internal data class FuseJavaGateStatus(
    val discoveredCount: Int = 0,
    val hookedMethods: List<String> = emptyList(),
    val unknownMethods: List<String> = emptyList(),
    val uidSkippedMethods: List<String> = emptyList(),
    val failedMethods: List<String> = emptyList(),
    val scanComplete: Boolean = false,
    val applicableCriticalMethods: Set<String> = emptySet(),
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("scanComplete", scanComplete)
        put("discoveredCount", discoveredCount)
        put("hookedCount", hookedMethods.size)
        put("unknownCount", unknownMethods.size)
        put("uidSkippedCount", uidSkippedMethods.size)
        put("failedCount", failedMethods.size)
        put("partialCoverage", scanComplete && hookedMethods.size < discoveredCount)
        put("hookedMethods", org.json.JSONArray(hookedMethods))
        put("unknownMethods", org.json.JSONArray(unknownMethods))
        put("uidSkippedMethods", org.json.JSONArray(uidSkippedMethods))
        put("failedMethods", org.json.JSONArray(failedMethods))
        put("applicableCriticalMethods", org.json.JSONArray(applicableCriticalMethods.toList()))
    }
}
