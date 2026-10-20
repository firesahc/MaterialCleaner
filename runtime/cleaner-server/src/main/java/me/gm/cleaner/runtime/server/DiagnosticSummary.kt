package me.gm.cleaner.runtime.server

import org.json.JSONObject

/**
 * 诊断包摘要中"失败事实"的渲染。
 *
 * 职责边界：只把已存在于快照中的失败事实渲染成可读文本，
 * 不做状态裁决（裁决归 Reporter），也不负责取数（取数归快照发布方）。
 *
 * 与 [DiagnosticArchive] 分离的动因：摘要渲染本身是独立职责
 * （英文版 / 中文版成对呈现），且随诊断面扩展会持续增长；
 * 放在编排类里会让其规模与"包的整体编排"耦合。
 *
 * 函数置于顶层 internal，故既有调用点无需改动。
 */

/**
 * 展示 Hook 层本次会话记录的结构化失败事实。
 *
 * 无此段说明该次快照生成于字段引入之前（或 Hook 侧尚未记录），
 * 两种情况都按"无 Hook 侧失败记录"陈述，不臆断原因。
 */
internal fun StringBuilder.appendHookErrorsSummary(root: JSONObject) {
    val hookErrors = root.optJSONObject("hookErrors") ?: return
    val count = hookErrors.optInt("count", 0)
    val dropped = hookErrors.optInt("droppedCount", 0)
    val items = hookErrors.optJSONArray("items")
    appendLine("- hookErrors: count=$count, dropped=$dropped")
    if (items != null) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val code = item.optString("code", "")
            val detail = item.optString("detail", "")
            appendLine("    [$code] ${detail}")
        }
    }
}

/**
 * 中文版 Hook 失败事实摘要，语义与英文版一致。
 */
internal fun StringBuilder.appendHookErrorsSummaryZhCn(root: JSONObject) {
    val hookErrors = root.optJSONObject("hookErrors") ?: return
    val count = hookErrors.optInt("count", 0)
    val dropped = hookErrors.optInt("droppedCount", 0)
    val items = hookErrors.optJSONArray("items")
    appendLine("- hookErrors：条数=$count，已丢弃=$dropped")
    if (items != null) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val code = item.optString("code", "")
            val detail = item.optString("detail", "")
            appendLine("    [$code] ${detail}")
        }
    }
}
