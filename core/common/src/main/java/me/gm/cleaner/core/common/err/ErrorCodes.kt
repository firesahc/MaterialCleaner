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
 * 2. native 侧 `app/src/main/cpp/include/error_ids.h` 的数字 ID 与此处的顺序分段一一对应，
 *     变更任一侧必须同步另一侧并保持双端一致性测试通过。
 * 3. 自由文本仅允许写入 [ErrorEvent.detail]；分类语义一律引用本表常量，
 *     使 UI、诊断包与统计能够机器可读地归并同类故障。
 */
object ErrorCodes {
    // ---------------------------------------------------------------- bind mount 域

    /** 挂载请求参数非法（数组长度不一致、nonce 缺失等）。 */
    const val MOUNT_ARGS_INVALID = "MOUNT.ARGS.INVALID"

    /** 打开目标进程 mount namespace fd 失败。 */
    const val MOUNT_SETNS_OPEN_FAILED = "MOUNT.SETNS.OPEN_FAILED"

    /** setns 进入目标命名空间失败。 */
    const val MOUNT_SETNS_FAILED = "MOUNT.SETNS.FAILED"

    /** 恢复 /storage 基线视图时卸载失败。 */
    const val MOUNT_BASELINE_UMOUNT_FAILED = "MOUNT.BASELINE.UMOUNT_FAILED"

    /** 恢复 /storage 基线视图时重挂失败。 */
    const val MOUNT_BASELINE_REMOUNT_FAILED = "MOUNT.BASELINE.REMOUNT_FAILED"

    /** 规则挂载失败（errno 记录于 [ErrorEvent.errno]，规则记录于 subject/detail）。 */
    const val MOUNT_RULE_FAILED = "MOUNT.RULE.FAILED"

    /** 批量规则部分成功部分失败（failed_indices 记录于 detail）。 */
    const val MOUNT_RULE_PARTIAL = "MOUNT.RULE.PARTIAL"

    /** 回滚执行了但无法确认完全恢复。 */
    const val MOUNT_ROLLBACK_INCOMPLETE = "MOUNT.ROLLBACK.INCOMPLETE"

    /** 回滚本身失败，目标命名空间被标记为脏。 */
    const val MOUNT_ROLLBACK_FAILED = "MOUNT.ROLLBACK.FAILED"

    /** 挂载后证据校验发现期望挂载点缺失。 */
    const val MOUNT_VERIFY_MISSING = "MOUNT.VERIFY.MISSING"

    /** 等待目标进程就绪超时。 */
    const val MOUNT_ZYGOTE_WAIT_TIMEOUT = "MOUNT.ZYGOTE.WAIT_TIMEOUT"

    /** 子事务进程结果超时（含 setns 卡死被看门狗终止）。 */
    const val MOUNT_PROC_TIMEOUT = "MOUNT.PROC.TIMEOUT"

    /** 进程内基础设施故障（fork/socketpair 失败、JNI 调用异常）。 */
    const val MOUNT_INTERNAL_FAILED = "MOUNT.INTERNAL.FAILED"

    /** 目标进程在事务期间退出。 */
    const val MOUNT_TARGET_GONE = "MOUNT.TARGET.GONE"

    /** 目标进程身份复核失败：start_time 与采集时不一致，疑似 PID 已被系统复用。 */
    const val MOUNT_IDENTITY_MISMATCH = "MOUNT.IDENTITY.MISMATCH"

    /** namespace 污染且无法回滚，已按安全策略停止目标应用。 */
    const val MOUNT_SAFETY_STOP = "MOUNT.SAFETY.STOP"

    /** 目标进程卡死被看门狗终止。 */
    const val MOUNT_TARGET_STUCK_KILLED = "MOUNT.TARGET.STUCK_KILLED"

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

    /** 快照 publisher epoch 与当前发布者不匹配。 */
    const val HOOK_JAVA_CACHE_EPOCH_MISMATCH = "HOOK.JAVA.CACHE.EPOCH_MISMATCH"

    /** bundle 被拒绝：重放或分叉。 */
    const val HOOK_JAVA_CACHE_BUNDLE_REJECTED = "HOOK.JAVA.CACHE.BUNDLE_REJECTED"

    /** 影子提升（promote）失败。 */
    const val HOOK_JAVA_CACHE_PROMOTE_FAILED = "HOOK.JAVA.CACHE.PROMOTE_FAILED"

    /** 提交 native 挂载计划失败。 */
    const val HOOK_JAVA_CACHE_COMMIT_NATIVE_FAILED = "HOOK.JAVA.CACHE.COMMIT_NATIVE_FAILED"

    /** 受防护 hook 方法熔断打开。 */
    const val HOOK_JAVA_GUARD_CIRCUIT_OPENED = "HOOK.JAVA.GUARD.CIRCUIT_OPENED"

    /** 受防护 hook 方法熔断恢复。 */
    const val HOOK_JAVA_GUARD_CIRCUIT_RECOVERED = "HOOK.JAVA.GUARD.CIRCUIT_RECOVERED"

    /** 宿主抛出的预期异常被静默吞掉（计数器事件）。 */
    const val HOOK_JAVA_GUARD_SWALLOWED_HOST_EXCEPTION =
        "HOOK.JAVA.GUARD.SWALLOWED_HOST_EXCEPTION"

    /** hook 进程向 DataBus 写事件失败。 */
    const val HOOK_JAVA_BUS_WRITE_FAILED = "HOOK.JAVA.BUS.WRITE_FAILED"

    // ------------------------------------------------------------ FUSE Native Hook 域

    /** libfuse_jni 动态库加载失败。 */
    const val HOOK_FUSE_LIB_LOAD_FAILED = "HOOK.FUSE.LIB.LOAD_FAILED"

    /** JNI 方法注册失败。 */
    const val HOOK_FUSE_LIB_JNI_REGISTER_FAILED = "HOOK.FUSE.LIB.JNI_REGISTER_FAILED"

    /** 目标符号在宿主库中未找到。 */
    const val HOOK_FUSE_SYMBOL_MISSING = "HOOK.FUSE.SYMBOL.MISSING"

    /** PLT/GOT patch 流程中途放弃。 */
    const val HOOK_FUSE_PLT_ABORTED = "HOOK.FUSE.PLT.ABORTED"

    /** 重定位段格式不支持（缺失 JMPREL、对齐异常等）。 */
    const val HOOK_FUSE_PLT_FORMAT_UNSUPPORTED = "HOOK.FUSE.PLT.FORMAT_UNSUPPORTED"

    /** GOT 页 mprotect 失败。 */
    const val HOOK_FUSE_GOT_MPROTECT_FAILED = "HOOK.FUSE.GOT.MPROTECT_FAILED"

    /** GOT 条目改写失败。 */
    const val HOOK_FUSE_GOT_PATCH_FAILED = "HOOK.FUSE.GOT.PATCH_FAILED"

    /** 库遍历提前终止（含已遍历库数量于 detail）。 */
    const val HOOK_FUSE_ITER_ABORTED = "HOOK.FUSE.ITER.ABORTED"

    /** 平台 FUSE 能力探测不可用。 */
    const val HOOK_FUSE_CAPABILITY_UNAVAILABLE = "HOOK.FUSE.CAPABILITY.UNAVAILABLE"

    // ---------------------------------------------------------------------- DataBus 域

    /** 快照或信号写入被拒绝。 */
    const val BUS_WRITE_REJECTED = "BUS.WRITE.REJECTED"

    /** 事件队列满，新事件被背压拒绝。 */
    const val BUS_QUEUE_FULL = "BUS.QUEUE.FULL"

    /** 快照内容损坏或校验失败。 */
    const val BUS_SNAPSHOT_CORRUPT = "BUS.SNAPSHOT.CORRUPT"

    // ------------------------------------------------------------------- supervisor 域

    /** supervisor token 校验不匹配。 */
    const val SUP_TOKEN_MISMATCH = "SUP.TOKEN.MISMATCH"

    /** root 服务进程死亡。 */
    const val SUP_PROC_DEAD = "SUP.PROC.DEAD"

    /** root 服务拉起失败。 */
    const val SUP_START_FAILED = "SUP.START.FAILED"
}
