package me.gm.cleaner.core.storage.redirect.domain

/**
 * 单条存储重定向规则。
 *
 * 语义定义（必须严格遵守）：
 * - [source]：重定向后实际承载内容的位置。这是文件系统上真正存放数据的地方。
 * - [target]：App 或 MediaProvider 原本访问、会被 mount 覆盖的位置。
 *
 * 验证用例：
 * ```
 * target = /storage/emulated/0/DCIM
 * source = /storage/emulated/0/Android/media/pkg/DCIM
 * path   = /storage/emulated/0/DCIM/a.jpg
 * → mountedPath = /storage/emulated/0/Android/media/pkg/DCIM/a.jpg
 * ```
 */
data class RedirectRule(
    /** 重定向后实际承载内容的位置（文件真实存放处） */
    val source: String,
    /** App 原本访问、会被 mount 覆盖的位置（对 App 透明的挂载点） */
    val target: String,
)
