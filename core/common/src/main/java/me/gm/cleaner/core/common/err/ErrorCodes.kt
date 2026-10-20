package me.gm.cleaner.core.common.err

/**
 * 统一错误码注册表。
 *
 * 全项目唯一的错误分类真源，覆盖五个域：bind mount、MediaProvider Java Hook、
 * FUSE Native Hook、DataBus 与 Root supervisor。
 *
 * 规则：
 * 1. 码为 `<域>.<子系统>.<原因>` 点分大写字符串；新增码必须声明为本对象 `const val`，
 *     由 [ErrorCodesTest] 校验格式与全局唯一性，禁止在任何调用点内联书写裸码字符串。
 * 2. 自由文本仅允许写入 [ErrorEvent.detail]；分类语义一律引用本表常量，
 *     使 UI、诊断包与统计能够机器可读地归并同类故障。
 * 3. **注册不等于接入**：本表是失败语义的分类契约，允许包含当前没有生产
 *     产出点的码——它们表示"若该失败发生则以此码归类"，而不是"该失败已被
 *     观测并上报"。当前无产出点的码集中列在文件末尾的预留分区，并在
 *     [RESERVED_CODES] 中登记；[ErrorCodesTest] 校验两者的同步。
 *     排障时不可把"码已注册"误读为"该类失败已被覆盖"。
 */
object ErrorCodes {
    // ---------------------------------------------------------------- bind mount 域

    /** 挂载请求参数非法（数组长度不一致、nonce 缺失等）。 */
    const val MOUNT_ARGS_INVALID = "MOUNT.ARGS.INVALID"

    /** setns 进入目标命名空间失败。 */
    const val MOUNT_SETNS_FAILED = "MOUNT.SETNS.FAILED"

    /** 恢复 /storage 基线视图时卸载失败。 */
    const val MOUNT_BASELINE_UMOUNT_FAILED = "MOUNT.BASELINE.UMOUNT_FAILED"

    /** 恢复 /storage 基线视图时重挂失败。 */
    const val MOUNT_BASELINE_REMOUNT_FAILED = "MOUNT.BASELINE.REMOUNT_FAILED"

    /** 规则挂载失败（errno 记录于 [ErrorEvent.errno]，规则记录于 subject/detail）。 */
    const val MOUNT_RULE_FAILED = "MOUNT.RULE.FAILED"

    /** FUSE bypass 失败已降级为无 bypass 重试，用户规则仍按正常路径应用（Issue #3）。 */
    const val MOUNT_BYPASS_SKIPPED = "MOUNT.BYPASS.SKIPPED"

    /** 回滚本身失败，目标命名空间被标记为脏。 */
    const val MOUNT_ROLLBACK_FAILED = "MOUNT.ROLLBACK.FAILED"

    /** 等待目标进程就绪超时。 */
    const val MOUNT_ZYGOTE_WAIT_TIMEOUT = "MOUNT.ZYGOTE.WAIT_TIMEOUT"

    /** 子事务进程结果超时（含 setns 卡死被看门狗终止）。 */
    const val MOUNT_PROC_TIMEOUT = "MOUNT.PROC.TIMEOUT"

    /** 进程内基础设施故障（fork/socketpair 失败、JNI 调用异常）。 */
    const val MOUNT_INTERNAL_FAILED = "MOUNT.INTERNAL.FAILED"

    /** 目标进程身份复核失败：start_time 与采集时不一致，疑似 PID 已被系统复用。 */
    const val MOUNT_IDENTITY_MISMATCH = "MOUNT.IDENTITY.MISMATCH"

    /** namespace 污染且无法回滚，已按安全策略停止目标应用。 */
    const val MOUNT_SAFETY_STOP = "MOUNT.SAFETY.STOP"

    /** 共享进程内各包的挂载签名不一致，无法按包隔离，已拒绝挂载并恢复 baseline。 */
    const val MOUNT_SHARED_PROCESS_CONFLICT = "MOUNT.SHARED.PROCESS_CONFLICT"

    // ---------------------------------------------------- MediaProvider Java Hook 域

    /** Xposed 入口加载目标类失败。 */
    const val HOOK_JAVA_LOAD_CLASS_NOT_FOUND = "HOOK.JAVA.LOAD.CLASS_NOT_FOUND"

    /** Xposed 入口初始化失败。 */
    const val HOOK_JAVA_LOAD_FAILED = "HOOK.JAVA.LOAD.FAILED"

    /** server callback binder 为空导致注册跳过。 */
    const val HOOK_JAVA_REG_SERVICE_NULL = "HOOK.JAVA.REG.SERVICE_NULL"

    /** Binder 注册失败。 */
    const val HOOK_JAVA_REG_FAILED = "HOOK.JAVA.REG.FAILED"

    /** server callback binder 死亡。 */
    const val HOOK_JAVA_BINDER_DEAD = "HOOK.JAVA.BINDER.DEAD"

    /** 重注册重试耗尽进入冷却。 */
    const val HOOK_JAVA_REG_EXHAUSTED = "HOOK.JAVA.REG.EXHAUSTED"

    /** 策略快照从未到达或被清理。 */
    const val HOOK_JAVA_CACHE_SNAPSHOT_MISSING = "HOOK.JAVA.CACHE.SNAPSHOT_MISSING"

    /** 策略快照解析失败。 */
    const val HOOK_JAVA_CACHE_PARSE_FAILED = "HOOK.JAVA.CACHE.PARSE_FAILED"

    /** 提交 native 挂载计划失败。 */
    const val HOOK_JAVA_CACHE_COMMIT_NATIVE_FAILED = "HOOK.JAVA.CACHE.COMMIT_NATIVE_FAILED"

    /** 受防护 hook 方法熔断打开。 */
    const val HOOK_JAVA_GUARD_CIRCUIT_OPENED = "HOOK.JAVA.GUARD.CIRCUIT_OPENED"

    /** 受防护 hook 方法熔断恢复。 */
    const val HOOK_JAVA_GUARD_CIRCUIT_RECOVERED = "HOOK.JAVA.GUARD.CIRCUIT_RECOVERED"

    // ------------------------------------------------------------ FUSE Native Hook 域

    /** libfuse_jni 动态库加载失败。 */
    const val HOOK_FUSE_LIB_LOAD_FAILED = "HOOK.FUSE.LIB.LOAD_FAILED"

    /** 目标符号在宿主库中未找到。 */
    const val HOOK_FUSE_SYMBOL_MISSING = "HOOK.FUSE.SYMBOL.MISSING"

    /** GOT 条目改写失败。 */
    const val HOOK_FUSE_GOT_PATCH_FAILED = "HOOK.FUSE.GOT.PATCH_FAILED"

    /** 平台 FUSE 能力探测不可用。 */
    const val HOOK_FUSE_CAPABILITY_UNAVAILABLE = "HOOK.FUSE.CAPABILITY.UNAVAILABLE"

    // ------------------------------------------------------------------- supervisor 域

    /** root 服务进程死亡。 */
    const val SUP_PROC_DEAD = "SUP.PROC.DEAD"

    /** root 服务拉起失败。 */
    const val SUP_START_FAILED = "SUP.START.FAILED"

    /** watchdog 判定 root 服务假死，已强制终止并重启。 */
    const val SUP_WATCHDOG_RESTART = "SUP.WATCHDOG.RESTART"

    // ------------------------------------------------------------------- DataBus 域

    // DataBus 域当前全部为预留码：队列与快照的异常状态由 databus/health.json
    // 与快照自身状态承载，未落 ErrorEvent。

    // ================================================================ 预留码分区
    //
    // 以下码已注册但当前没有生产产出点，表示"若该失败发生则以此码归类"的
    // 语义契约，而不是"该类失败已被观测上报"。保留它们是为了让失败分类
    // 保持完整；实现对应失败分支时直接引用即可，无需重新注册。
    //
    // 清单同步：[RESERVED_CODES] 登记全部预留码，由 [ErrorCodesTest] 校验
    // 与本分区保持一致。某个码接入生产路径后，应从本分区与 [RESERVED_CODES]
    // 中一并移出。

    /** 打开目标进程 mount namespace fd 失败（当前直接返回失败，未落结构化记录）。 */
    const val MOUNT_SETNS_OPEN_FAILED = "MOUNT.SETNS.OPEN_FAILED"

    /** 批量规则部分成功部分失败（当前由 commitStructured 的 PARTIAL 结构承载）。 */
    const val MOUNT_RULE_PARTIAL = "MOUNT.RULE.PARTIAL"

    /** 回滚执行了但无法确认完全恢复（当前回滚失败统一记 MOUNT.ROLLBACK.FAILED）。 */
    const val MOUNT_ROLLBACK_INCOMPLETE = "MOUNT.ROLLBACK.INCOMPLETE"

    /** 挂载后证据校验发现期望挂载点缺失（当前未做挂载后校验）。 */
    const val MOUNT_VERIFY_MISSING = "MOUNT.VERIFY.MISSING"

    /** 目标进程在事务期间退出（当前归入 MOUNT.PROC.TIMEOUT 与 IDENTITY.MISMATCH）。 */
    const val MOUNT_TARGET_GONE = "MOUNT.TARGET.GONE"

    /** 目标进程卡死被看门狗终止（当前归入 MOUNT.PROC.TIMEOUT）。 */
    const val MOUNT_TARGET_STUCK_KILLED = "MOUNT.TARGET.STUCK_KILLED"

    /** 快照 publisher epoch 与当前发布者不匹配（hook 侧对 epoch 变化为接受，无失败分支）。 */
    const val HOOK_JAVA_CACHE_EPOCH_MISMATCH = "HOOK.JAVA.CACHE.EPOCH_MISMATCH"

    /** bundle 被拒绝：重放或分叉（bundle 概念在 hook 模块不存在）。 */
    const val HOOK_JAVA_CACHE_BUNDLE_REJECTED = "HOOK.JAVA.CACHE.BUNDLE_REJECTED"

    /** 影子提升（promote）失败（promote 概念在 hook 模块不存在）。 */
    const val HOOK_JAVA_CACHE_PROMOTE_FAILED = "HOOK.JAVA.CACHE.PROMOTE_FAILED"

    /** 宿主抛出的预期异常被静默吞掉（高频计数事件，由 guardedHooks.swallowedHostExceptions 承载）。 */
    const val HOOK_JAVA_GUARD_SWALLOWED_HOST_EXCEPTION =
        "HOOK.JAVA.GUARD.SWALLOWED_HOST_EXCEPTION"

    /** hook 进程向 DataBus 写事件失败（高频事件，不属于 Hook 不可用的成因）。 */
    const val HOOK_JAVA_BUS_WRITE_FAILED = "HOOK.JAVA.BUS.WRITE_FAILED"

    /** JNI 方法注册失败（当前 JNI 注册随库加载一并失败，记 LIB.LOAD_FAILED）。 */
    const val HOOK_FUSE_LIB_JNI_REGISTER_FAILED = "HOOK.FUSE.LIB.JNI_REGISTER_FAILED"

    /** PLT/GOT patch 流程中途放弃（当前统一记 GOT.PATCH_FAILED）。 */
    const val HOOK_FUSE_PLT_ABORTED = "HOOK.FUSE.PLT.ABORTED"

    /** 重定位段格式不支持（缺失 JMPREL、对齐异常等）。 */
    const val HOOK_FUSE_PLT_FORMAT_UNSUPPORTED = "HOOK.FUSE.PLT.FORMAT_UNSUPPORTED"

    /** GOT 页 mprotect 失败。 */
    const val HOOK_FUSE_GOT_MPROTECT_FAILED = "HOOK.FUSE.GOT.MPROTECT_FAILED"

    /** 库遍历提前终止（含已遍历库数量于 detail）。 */
    const val HOOK_FUSE_ITER_ABORTED = "HOOK.FUSE.ITER.ABORTED"

    /** 快照或信号写入被拒绝。 */
    const val BUS_WRITE_REJECTED = "BUS.WRITE.REJECTED"

    /** 事件队列满，新事件被背压拒绝。 */
    const val BUS_QUEUE_FULL = "BUS.QUEUE.FULL"

    /** 快照内容损坏或校验失败。 */
    const val BUS_SNAPSHOT_CORRUPT = "BUS.SNAPSHOT.CORRUPT"

    /** supervisor token 校验不匹配（当前未实现该校验）。 */
    const val SUP_TOKEN_MISMATCH = "SUP.TOKEN.MISMATCH"

    /**
     * 当前无生产产出点的预留码集合。
     *
     * 与文件末尾预留分区一一对应，由 [ErrorCodesTest] 校验二者同步：
     * 某码接入生产路径后应同时从该集合与预留分区移出。
     */
    val RESERVED_CODES: Set<String> = setOf(
        MOUNT_SETNS_OPEN_FAILED,
        MOUNT_RULE_PARTIAL,
        MOUNT_ROLLBACK_INCOMPLETE,
        MOUNT_VERIFY_MISSING,
        MOUNT_TARGET_GONE,
        MOUNT_TARGET_STUCK_KILLED,
        HOOK_JAVA_CACHE_EPOCH_MISMATCH,
        HOOK_JAVA_CACHE_BUNDLE_REJECTED,
        HOOK_JAVA_CACHE_PROMOTE_FAILED,
        HOOK_JAVA_GUARD_SWALLOWED_HOST_EXCEPTION,
        HOOK_JAVA_BUS_WRITE_FAILED,
        HOOK_FUSE_LIB_JNI_REGISTER_FAILED,
        HOOK_FUSE_PLT_ABORTED,
        HOOK_FUSE_PLT_FORMAT_UNSUPPORTED,
        HOOK_FUSE_GOT_MPROTECT_FAILED,
        HOOK_FUSE_ITER_ABORTED,
        BUS_WRITE_REJECTED,
        BUS_QUEUE_FULL,
        BUS_SNAPSHOT_CORRUPT,
        SUP_TOKEN_MISMATCH,
    )
}
