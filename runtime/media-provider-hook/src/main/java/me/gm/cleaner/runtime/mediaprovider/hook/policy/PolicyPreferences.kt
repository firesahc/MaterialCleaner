package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject

/**
 * 偏好标记域：随 redirect_policy 快照演进，独立 holder 发布。
 * 仅做透传，不按 ROM 做条件改写；缺席容忍为 false。
 */
internal data class PreferencesHolder(
    val recordExternalAppSpecificStorage: Boolean = false,
    // 真相归偏好层：本字段只做透传，不按 ROM 做条件改写。
    val fuseBpfBlockAll: Boolean = false,
    val aggressivelyPromptForReadingMediaFiles: Boolean = false,
    /**
     * 媒体库写入审计总开关。随 redirect_policy 快照演进，独立 holder 发布。
     * 仅做透传，不按 ROM 做条件改写；缺席容忍为 false。
     */
    val auditMediaInsert: Boolean = false,
    /**
     * 媒体库写入拒绝总开关。随 redirect_policy 快照演进，独立 holder 发布。
     * 仅做透传，不按 ROM 做条件改写；缺席容忍为 false。
     */
    val denyMediaInsertWrites: Boolean = false,
    val generation: Long = 0L,
    val publisherEpoch: String = "",
)

/** 偏好解析工具：只负责从 redirect_policy JSON 构建 PreferencesHolder。 */
internal object PolicyPreferencesParser {
    fun parse(json: String): PreferencesHolder {
        val root = JSONObject(json)
        return PreferencesHolder(
            recordExternalAppSpecificStorage = root.optBoolean("recordExternalAppSpecificStorage", false),
            // 只透传偏好层真相：缺席容忍为 false，不按 ROM 做条件改写。
            fuseBpfBlockAll = root.optBoolean("fuseBpfBlockAll", false),
            aggressivelyPromptForReadingMediaFiles =
                root.optBoolean("aggressivelyPromptForReadingMediaFiles", false),
            auditMediaInsert = root.optBoolean("auditMediaInsert", false),
            denyMediaInsertWrites = root.optBoolean("denyMediaInsertWrites", false),
            generation = root.optLong("generation", 0L),
            publisherEpoch = root.optString("publisherEpoch", ""),
        )
    }
}