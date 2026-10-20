package me.gm.cleaner.runtime.server

/**
 * 本项目关注的 logcat tag 集合。
 *
 * 职责：本文件是"本项目需要哪些 logcat tag"的**唯一真相源**。
 * 持续采集（[AutoLogging]）与诊断导出（[DiagnosticArchive]）都从这里取过滤串，
 * 避免两处各自维护清单而随代码演进漂移。
 *
 * logcat 过滤语义（实测确认，勿改）：
 * - filterSpec 只写 `<tag>:*` **不会**抑制其它 tag，必须以 `*:S` 结尾才真正过滤；
 * - 缺少 `*:S` 时会退化为全量输出，采集量将回到未过滤水平。
 *
 * 维护约定：新增本项目日志 tag 时必须同步登记到此清单，
 * 否则持续采集与诊断聚焦日志都会漏掉该 tag。
 */
object LogTagFilter {
    /**
     * 本项目全部日志 tag。
     *
     * 覆盖 server 侧（[me.gm.cleaner.runtime.server]、cleaner_server）、app 侧
     * （me.gm.cleaner、MC_ 与 MC/ 前缀）、Hook 侧（跑在 MediaProvider 进程内的
     * MC_REDIRECT 等）、native 侧（me.gm.cleaner、xhook）以及启动器（starter）。
     */
    private val TAGS = listOf(
        // 生命周期与状态机
        "MC_BOOT", "MC_EVENT", "MC_STATE", "MC_DIAG",
        "MC/Test", "MC/StateMachine", "MC/BootState", "MC/XposedState",
        "MC/MoreOptions", "MC/Launcher",
        // 重定向与挂载
        "MC_REDIRECT",
        // 组件级
        "DataBus", "DiagnosticArchive", "CleanerService",
        "LayerOrchestrator", "SnapshotPublisher", "HookRecoveryCoordinator",
        "MediaProviderRecoveryStrategy", "NativeHookStatus", "HookPolicyCache",
        "FuseNativePolicyAdapter", "HookDataBusBridge", "EventConsumerScheduler",
        "FileSystemEventConsumer", "RedirectNoticeConsumer", "QuerySessionLeaseConsumer",
        "ActivityManagerLogsObserver", "AMLogs",
        // server / app 进程
        "me.gm.cleaner", "me.gm.cleaner.runtime.server", "cleaner_server",
        // native 与启动器
        "xhook", "starter",
        // 仅 debug 构建产出
        "CleanerTest",
    )

    /**
     * 供 logcat 使用的过滤串，**必须**以 `*:S` 结尾。
     */
    val FILTER_SPEC: String = TAGS.joinToString(separator = " ") { "$it:*" } + " *:S"
}
