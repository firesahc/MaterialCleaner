package me.gm.cleaner.client.ui

/**
 * 四版本号对账结果。
 *
 * 更新后未重启时，已安装版大于任一运行中组件版（App 进程、Root 服务、
 * Zygote 内 Hook 只有重启手机能统一）。0 表示读不到，按未知处理，
 * 未知永不判 stale，避免误报打扰用户。
 */
enum class VersionStaleness {
    OK,
    STALE_APP,
    STALE_SERVER,
    STALE_HOOK,
}

/**
 * 纯函数：版本号对账。已安装版由 App 经 PackageManager 读取后传入
 * （server 编译桩无版本字段，不经 Binder 传）。
 */
fun evaluateVersionStaleness(
    appRunning: Long,
    installed: Long,
    serverRunning: Long,
    hookRunning: Long,
    hookAvailable: Boolean,
): VersionStaleness {
    if (installed <= 0L) return VersionStaleness.OK
    if (appRunning > 0L && appRunning != installed) return VersionStaleness.STALE_APP
    if (serverRunning > 0L && serverRunning != installed) return VersionStaleness.STALE_SERVER
    if (!hookAvailable) return VersionStaleness.OK
    if (hookRunning > 0L && hookRunning != installed) return VersionStaleness.STALE_HOOK
    return VersionStaleness.OK
}

/**
 * 四个版本号构成的组合指纹。
 *
 * 组合而非单个版本号的原因：stale 是**持续状态**（Hook 住在 Zygote，更新 APK
 * 后一直落后直到重启手机），单看"是否 stale"无法区分"同一次不一致的第 N 次
 * 检测"与"又一次新的不一致"；而组合变化能同时表达两者。
 */
data class VersionFingerprint(
    val appRunning: Long,
    val installed: Long,
    val serverRunning: Long,
    val hookRunning: Long,
)

/**
 * 纯函数：是否需要就"版本过期，请重启手机"发出通知。
 *
 * 语义：
 * - [current] 为 null（server 不可达，取不到版本号）：不提示。"服务未运行"
 *   属于控制面状态，不能误报成"版本不一致"；
 * - [previous] 为 null（首次发现版本过期）：提示；
 * - 组合相对上次发生变化：提示（新的不一致，如 stale 期间又更新了 APK）；
 * - 组合未变：抑制（同一次 stale，不重复打扰）。
 *
 * 去重范围是**调用方进程内**：App 冷启动后上次记忆丢失，会再次提示一次，
 * 属既定行为（换来不引入跨进程持久化状态面）。
 *
 * 是否处于 stale 由 [evaluateVersionStaleness] 判定，本函数只回答"要不要提示"。
 */
fun shouldNotifyStale(
    previous: VersionFingerprint?,
    current: VersionFingerprint?,
): Boolean {
    if (current == null) return false
    if (previous == null) return true
    return previous != current
}
