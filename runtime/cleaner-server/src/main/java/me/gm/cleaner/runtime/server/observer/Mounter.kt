package me.gm.cleaner.runtime.server.observer

import android.app.ActivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.core.os.postDelayed
import com.google.common.collect.Multimaps
import com.google.common.collect.SetMultimap
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.common.err.ErrorEvent
import me.gm.cleaner.core.common.err.ErrorLogThrottle
import me.gm.cleaner.core.storage.redirect.domain.MountRules
import me.gm.cleaner.runtime.server.orchestrator.ServerErrorJournal
import api.SystemService
import me.gm.cleaner.runtime.server.VfsRuntimeConfigStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class Mounter {
    private val thread: HandlerThread = HandlerThread("Mounter").apply { start() }
    private val handler: Handler = Handler(thread.looper)
    private val lock: Any = Object()

    private val pidRecords: SetMultimap<String, Int> =
        Multimaps.newSetMultimap(ArrayMap()) { ArraySet() }
    private val mountFailedPids: MutableSet<Int> = mutableSetOf()
    private val mkdirRecords: SetMultimap<String, String> =
        Multimaps.newSetMultimap(ArrayMap()) { ArraySet() }

    private val rmdirPackages: MutableSet<String> = mutableSetOf()
    private var rmdirQueueSize: Int = 0

    /** mount 尝试总次数（含重试），用于 collectStatus metrics */
    val totalAttempts: AtomicInteger = AtomicInteger(0)
    /** mount 失败总次数（含重试耗尽），用于 collectStatus metrics */
    val failureCount: AtomicInteger = AtomicInteger(0)
    /** 每 pid 重试计数，达到 MAX_MOUNT_RETRIES 后放弃 */
    private val mountRetryCount = mutableMapOf<Int, Int>()
    private var lastMountFailure: MountFailure? = null

    /**
     * 错误日志节流：同一错误码 30 秒内只完整输出一次，
     * 防止挂载风暴（如 EBUSY 循环）刷屏掩盖其他层故障信号。
     */
    private val errorLogThrottle = ErrorLogThrottle()

    fun mountForAllPackages(): Boolean =
        VfsRuntimeConfigStore.shouldMountForAllPackages()

    fun bindMountAsync(packageName: String, pid: Int, uid: Int) {
        handler.post {
            synchronized(lock) { bindMountLocked(packageName, pid, uid) }
        }
    }

    fun bindMount(packageName: String, pid: Int, uid: Int): Boolean = synchronized(lock) {
        bindMountLocked(packageName, pid, uid)
    }

    internal val isFuseBpfEnabled: Boolean
        get() = VfsRuntimeConfigStore.isFuseBpfEnabled()

    private fun getMkdirList(rules: MountRules): List<String> = rules.mountPoint + rules.sources

    private fun bindMountLocked(packageName: String, pid: Int, uid: Int): Boolean {
        totalAttempts.incrementAndGet()
        Log.i("MC_REDIRECT", "[Mounter] bindMountLocked pkg=$packageName pid=$pid uid=$uid " +
                "attempt=${totalAttempts.get()}")
        val userId = uid.toUserId()
        val recordExternalAppSpecificStorage =
            VfsRuntimeConfigStore.shouldRecordExternalAppSpecificStorage(packageName)
        val rules = VfsRuntimeConfigStore.getMountRules(packageName, userId)

        if (rules == null || rules.isEmpty()) {
            val result = RuntimeFileUtils.bind_mount_result(
                pid, uid,
                !isFuseBpfEnabled && recordExternalAppSpecificStorage, false,
                emptyArray(), emptyArray()
            )
            Log.i("MC_REDIRECT", "[Mounter] bindMount result=${result.success} " +
                    "pkg=$packageName pid=$pid detail=${result.reason}")
            return handleBindMountResultLocked(packageName, pid, uid, result)
        }

        pidRecords.put(packageName, pid)
        if (!mkdirRecords.containsKey(packageName)) {
            val mkdirRecord = mutableSetOf<String>()
            getMkdirList(rules).forEach { record(mkdirRecord, it) }
            if (RuntimeFileUtils.auto_prepare_dirs(mkdirRecord.toTypedArray(), uid)) {
                mkdirRecords.putAll(packageName, mkdirRecord)
            }
        }
        val result = RuntimeFileUtils.bind_mount_result(
            pid, uid,
            !isFuseBpfEnabled && recordExternalAppSpecificStorage, isFuseBpfEnabled,
            rules.sources.toTypedArray(), rules.targets.toTypedArray()
        )
        Log.i("MC_REDIRECT", "[Mounter] bindMount result=${result.success} pkg=$packageName " +
                "pid=$pid sources=${rules.sources} targets=${rules.targets} detail=${result.reason}")
        return handleBindMountResultLocked(packageName, pid, uid, result)
    }

    private fun handleBindMountResultLocked(
        packageName: String,
        pid: Int,
        uid: Int,
        result: RuntimeFileUtils.BindMountResult
    ): Boolean {
        if (result.success) {
            mountFailedPids.remove(pid)
            mountRetryCount.remove(pid)
            return true
        }
        mountFailedPids.add(pid)
        failureCount.incrementAndGet()

        // 处置分类：污染 / 永久失败 / 可重试失败 三分支。
        val disposition = MountFailureRetryPolicy.classify(
            stage = result.stage,
            errno = result.errno,
            namespaceDirty = result.namespaceDirty,
            targetTerminated = result.targetTerminated,
        )
        val safetyStop = if (disposition.forceStopTargetPackage) {
            // namespace 已污染且 native 未能终止目标：由 server 补充强停，
            // 防止应用继续运行在脏挂载视图上产生不可预期的文件访问。
            val userId = uid.toUserId()
            val stopped = SystemService.forceStopPackageNoThrow(packageName, userId)
            if (stopped) {
                Log.w("MC_REDIRECT", "[Mounter] safety stop applied pkg=$packageName pid=$pid")
            } else {
                Log.e("MC_REDIRECT", "[Mounter] safety stop FAILED pkg=$packageName pid=$pid")
            }
            stopped
        } else {
            false
        }

        recordMountFailureLocked(packageName, pid, uid, result, disposition, safetyStop)
        lastMountFailure = MountFailure(
            timeMillis = System.currentTimeMillis(),
            packageName = packageName,
            pid = pid,
            uid = uid,
            reason = result.reason,
            stage = result.stage,
            errno = result.errno,
            failedIndex = result.failedIndex,
            source = result.source,
            target = result.target,
            phaseName = result.phaseName,
            namespaceDirty = result.namespaceDirty,
            retryable = disposition.retryable,
            targetTerminated = result.targetTerminated,
            forceStopAttempted = disposition.forceStopTargetPackage,
            forceStopSucceeded = safetyStop,
        )
        if (disposition.retryable) {
            scheduleMountRetryLocked(packageName, pid, uid)
        } else {
            mountRetryCount.remove(pid)
            Log.w(
                "MC_REDIRECT",
                "[Mounter] permanent mount failure, no retry pkg=$packageName " +
                        "stage=${result.stage} dirty=${result.namespaceDirty}"
            )
        }
        return false
    }

    /**
     * 将 native 挂载失败结果归一化为 [ErrorEvent]：
     * 结构化字段（stage→错误码映射）保证机器可读，规则坐标仅入事件与诊断包。
     */
    private fun recordMountFailureLocked(
        packageName: String,
        pid: Int,
        uid: Int,
        result: RuntimeFileUtils.BindMountResult,
        disposition: MountFailureDisposition,
        forceStopSucceeded: Boolean,
    ) {
        // 错误码优先级：污染处置 > 身份复核 > 阶段映射。
        val code = when {
            result.namespaceDirty && disposition.forceStopTargetPackage ->
                ErrorCodes.MOUNT_SAFETY_STOP
            result.namespaceDirty -> ErrorCodes.MOUNT_ROLLBACK_FAILED
            result.stage == "target_identity" -> ErrorCodes.MOUNT_IDENTITY_MISMATCH
            else -> errorCodeForStage(result.stage)
        }
        val event = ErrorEvent(
            code = code,
            errno = result.errno,
            subject = "$packageName/pid:$pid",
            pathDigest = if (result.failedIndex >= 0) {
                // 规则坐标以索引形式进入事件；明文路径保留在 MountFailure 供诊断包使用。
                "rule#${result.failedIndex}"
            } else {
                null
            },
            atElapsed = SystemClock.elapsedRealtime(),
            detail = buildString {
                append("stage=").append(result.stage)
                if (result.phase >= 0) append(" phase=").append(result.phaseName)
                if (result.namespaceDirty) append(" dirty=true")
                if (result.targetTerminated) append(" targetTerminated=true")
                if (disposition.forceStopTargetPackage) {
                    append(" safetyStop=").append(if (forceStopSucceeded) "ok" else "failed")
                }
                if (!disposition.retryable && !result.namespaceDirty) append(" permanent=true")
                if (result.error.isNotBlank()) append(" os=").append(result.error)
            },
        )
        if (errorLogThrottle.tryAcquire(event.code, event.atElapsed)) {
            Log.w("MC_REDIRECT", "[Mounter] mount failed pkg=$packageName ${event.toCompactString()}")
        }
        ServerErrorJournal.record(event)
    }

    /** native 失败阶段到统一错误码的映射。 */
    private fun errorCodeForStage(stage: String): String = when (stage) {
        "invalid_args", "invalid_source", "invalid_target" -> ErrorCodes.MOUNT_ARGS_INVALID
        "wait_zygote" -> ErrorCodes.MOUNT_ZYGOTE_WAIT_TIMEOUT
        "setns" -> ErrorCodes.MOUNT_SETNS_FAILED
        "target_identity" -> ErrorCodes.MOUNT_IDENTITY_MISMATCH
        "unmount_storage", "baseline_recovery_failed" ->
            ErrorCodes.MOUNT_BASELINE_UMOUNT_FAILED
        "remount_storage", "remount_storage_slave", "mount_storage_self",
        "fuse_bypass_data_source", "fuse_bypass_obb_source",
        "fuse_bypass_data_target", "fuse_bypass_obb_target",
        -> ErrorCodes.MOUNT_BASELINE_REMOUNT_FAILED
        "unmount_data_restriction_fuse", "unmount_data_restriction_storage" ->
            ErrorCodes.MOUNT_BASELINE_UMOUNT_FAILED
        "mount_rule" -> ErrorCodes.MOUNT_RULE_FAILED
        "conflicting_process_rules" -> ErrorCodes.MOUNT_SHARED_PROCESS_CONFLICT
        "child_result_timeout", "namespace_transaction_timeout" ->
            ErrorCodes.MOUNT_PROC_TIMEOUT
        "namespace_rollback_failed" -> ErrorCodes.MOUNT_ROLLBACK_FAILED
        else -> ErrorCodes.MOUNT_INTERNAL_FAILED
    }

    /**
     * 调度 mount 失败后的退避重试。
     *
     * 延迟策略：2s → 5s → 15s，达到 [MAX_MOUNT_RETRIES] 后放弃。
     * 使用现有的 Mounter HandlerThread，不增加新线程。
     */
    private fun scheduleMountRetryLocked(packageName: String, pid: Int, uid: Int) {
        val attempt = mountRetryCount.getOrDefault(pid, 0)
        if (attempt >= MAX_MOUNT_RETRIES) {
            mountRetryCount.remove(pid)
            Log.w("MC_REDIRECT", "[Mounter] mount retry exhausted for pid=$pid pkg=$packageName")
            return
        }
        mountRetryCount[pid] = attempt + 1
        val delay = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        Log.i("MC_REDIRECT", "[Mounter] scheduling mount retry #${attempt + 1} for pid=$pid " +
                "pkg=$packageName in ${delay}ms")
        handler.postDelayed(delay) {
            synchronized(lock) {
                if (shouldRunMountRetryLocked(packageName, pid)) {
                    bindMountLocked(packageName, pid, uid)
                }
            }
        }
    }

    private fun shouldRunMountRetryLocked(packageName: String, pid: Int): Boolean {
        if (!mountRetryCount.containsKey(pid)) {
            return false
        }
        if (File("/proc", pid.toString()).exists()) {
            return true
        }
        Log.i("MC_REDIRECT", "[Mounter] skip mount retry for dead pid=$pid pkg=$packageName")
        notifyProcessKilledLocked(packageName, pid)
        return false
    }

    private fun record(mkdirRecord: MutableSet<String>, dir: String) {
        if (RuntimeFileUtils.childOf(RuntimeFileUtils.externalStorageDirParent, dir)) {
            mkdirRecord.add(dir)
            val parent = File(dir).parent ?: return
            record(mkdirRecord, parent)
        }
    }

    fun forProcListAsync(
        procList: List<ActivityManager.RunningAppProcessInfo>,
        checkMountState: Boolean,
        remount: Boolean
    ) {
        handler.post {
            forProcList(procList, checkMountState, remount)
        }
    }

    fun forProcList(
        procList: List<ActivityManager.RunningAppProcessInfo>,
        checkMountState: Boolean,
        remount: Boolean
    ) {
        synchronized(lock) {
            if (checkMountState) {
                val remountProcList = checkAndRecordLocked(procList)
                if (remount) {
                    remountLocked(remountProcList)
                }
            } else if (remount) {
                remountLocked(procList)
            }
        }
    }

    private fun checkAndRecordLocked(procList: List<ActivityManager.RunningAppProcessInfo>)
            : List<ActivityManager.RunningAppProcessInfo> {
        val remountPackages = mutableSetOf<String>()
        for (procInfo in procList) {
            procInfo.pkgList.forEach { packageName ->
                val rules = VfsRuntimeConfigStore.getMountRules(
                    packageName,
                    procInfo.uid.toUserId(),
                ) ?: return@forEach
                val targets = rules.targets
                val mountedIndices =
                    RuntimeFileUtils.check_mounts(procInfo.pid, targets.toTypedArray())
                if (mountedIndices == null) {
                    // unknown mount status
                } else if (targets.size == mountedIndices.size && mountedIndices.all { it != -1 }) {
                    // record pid
                    pidRecords.put(packageName, procInfo.pid)
                    if (!mkdirRecords.containsKey(packageName)) {
                        mkdirRecords.putAll(packageName, getMkdirList(rules))
                    }
                } else {
                    remountPackages += packageName
                }
            }
        }
        val remountProcList = procList.filter { procInfo ->
            procInfo.pkgList.any { remountPackages.contains(it) }
        }
        return remountProcList
    }

    private fun remountLocked(procList: List<ActivityManager.RunningAppProcessInfo>) {
        for (procInfo in procList) {
            procInfo.pkgList.forEach { packageName ->
                removeMountDirsLocked(packageName)
            }
        }
        for (procInfo in procList) {
            val userId = procInfo.uid.toUserId()
            // bind_mount 会重建整个进程的 /storage namespace，不能对 pkgList 逐包调用，
            // 否则最后一个未配置包会清除前面已经应用的规则。
            val selection = ProcessMountSelectionPolicy.resolve(
                packageNames = procInfo.pkgList,
                redirectRuleSignature = { packageName ->
                    VfsRuntimeConfigStore.getMountRules(packageName, userId)?.let { rules ->
                        rules.sources.zip(rules.targets)
                    }
                },
                shouldUnmountDataRestriction = { packageName ->
                    !isFuseBpfEnabled &&
                            VfsRuntimeConfigStore
                                .shouldRecordExternalAppSpecificStorage(packageName)
                },
            )
            when (selection) {
                ProcessMountSelectionPolicy.Selection.None -> Unit
                is ProcessMountSelectionPolicy.Selection.Conflict ->
                    handleProcessRuleConflictLocked(procInfo, selection.packageNames)
                is ProcessMountSelectionPolicy.Selection.Selected -> {
                    val equivalentPackages = procInfo.pkgList
                        .filter { it != selection.packageName }
                    equivalentPackages.forEach { packageName ->
                        clearProcessPackageStateLocked(packageName, procInfo.pid)
                    }
                    if (bindMountLocked(selection.packageName, procInfo.pid, procInfo.uid)) {
                        // 等价包与代表包共享同一 namespace，登记相同的 pid 与目录。
                        equivalentPackages.forEach { packageName ->
                            val rules = VfsRuntimeConfigStore.getMountRules(packageName, userId)
                                ?: return@forEach
                            pidRecords.put(packageName, procInfo.pid)
                            if (!mkdirRecords.containsKey(packageName)) {
                                mkdirRecords.putAll(packageName, getMkdirList(rules))
                            }
                        }
                    }
                }
            }
        }
    }

    /** 清理等价包在该进程上的状态登记；目录登记由统一的 removeMountDirsLocked 管理。 */
    private fun clearProcessPackageStateLocked(packageName: String, pid: Int) {
        pidRecords.remove(packageName, pid)
        mountRetryCount.remove(pid)
    }

    /**
     * 共享进程内各包挂载签名冲突的显式降级：
     * 拒绝挂载任何一方，执行空规则事务恢复 baseline 清干净现状，
     * 并产出 MOUNT.SHARED.PROCESS_CONFLICT 错误事件供状态卡与诊断包定位。
     */
    private fun handleProcessRuleConflictLocked(
        procInfo: ActivityManager.RunningAppProcessInfo,
        conflictingPackages: Set<String>,
    ) {
        procInfo.pkgList.forEach { packageName ->
            pidRecords.remove(packageName, procInfo.pid)
            mountRetryCount.remove(procInfo.pid)
        }
        totalAttempts.incrementAndGet()
        val reset = RuntimeFileUtils.bind_mount_result(
            pid = procInfo.pid,
            uid = procInfo.uid,
            unmountDataRestriction = false,
            fuseBypass = false,
            sources = emptyArray(),
            targets = emptyArray(),
        )
        failureCount.incrementAndGet()
        val packageSummary = conflictingPackages.sorted().joinToString(",")
        val resetSummary = if (reset.success) "baseline restored" else "reset=${reset.reason}"
        lastMountFailure = MountFailure(
            timeMillis = System.currentTimeMillis(),
            packageName = packageSummary,
            pid = procInfo.pid,
            uid = procInfo.uid,
            reason = "conflicting shared-process rules; $resetSummary",
            stage = "conflicting_process_rules",
            errno = reset.errno,
        )
        ServerErrorJournal.record(
            ErrorEvent(
                code = ErrorCodes.MOUNT_SHARED_PROCESS_CONFLICT,
                errno = reset.errno,
                subject = "$packageSummary/pid:${procInfo.pid}",
                atElapsed = SystemClock.elapsedRealtime(),
                detail = resetSummary,
            )
        )
        Log.e(
            "MC_REDIRECT",
            "[Mounter] rejected conflicting rules for shared pid=${procInfo.pid} " +
                    "packages=$packageSummary, $resetSummary",
        )
    }

    fun notifyProcessKilled(packageName: String, pid: Int) {
        handler.post {
            synchronized(lock) {
                notifyProcessKilledLocked(packageName, pid)
            }
        }
    }

    private fun notifyProcessKilledLocked(packageName: String, pid: Int) {
        pidRecords.remove(packageName, pid)
        mountFailedPids.remove(pid)
        mountRetryCount.remove(pid)
        if (pidRecords.containsKey(packageName)) {
            rmdirPackages.add(packageName)
            ++rmdirQueueSize
            handler.postDelayed(
                min(VALIDATE_PID_DELAY_PER_PACKAGE * rmdirPackages.size, VALIDATE_PID_DELAY_MAX)
            ) {
                synchronized(lock) {
                    if (--rmdirQueueSize == 0 && rmdirPackages.isNotEmpty()) {
                        validatePidRecordsLocked()
                    }
                }
            }
        } else {
            rmdirPackages.remove(packageName)
            removeMountDirsLocked(packageName)
        }
    }

    private fun validatePidRecordsLocked() {
        rmdirPackages.clear()
        // /proc
        val proc = CharArray(0x5)

        proc[0x0] = '-'
        proc[0x1] = 'p'
        proc[0x2] = 's'
        proc[0x3] = 'm'
        proc[0x4] = 'c'

        for (i in 0..0x4) {
            proc[i] = (proc[i].code xor (i + 0x5) % 3).toChar()
        }
        pidRecords.keySet().toList().forEach { packageName ->
            val validValue = pidRecords[packageName].filter { pid ->
                File(String(proc), pid.toString()).exists()
            }
            val oldValues = pidRecords.replaceValues(packageName, validValue)
            mountFailedPids.removeAll(oldValues - validValue.toSet())
            if (validValue.isEmpty()) {
                removeMountDirsLocked(packageName)
            }
        }
    }

    private fun removeMountDirsLocked(packageName: String) {
        if (!mkdirRecords.containsKey(packageName)) {
            return
        }
        val others = mkdirRecords.asMap()
            .filterKeys { it != packageName }
            .values
            .flatten()
            .toSet()
        val dirs = mkdirRecords[packageName]
            .filter { !others.contains(it) }
            .sortedByDescending { it.length }
        mkdirRecords.removeAll(packageName)
        dirs.forEach { dir ->
            RuntimeFileUtils.rm_dir(dir)
        }
    }

    fun getRecordedPids(packageName: String): Set<Int> = synchronized(lock) {
        pidRecords[packageName].toSet()
    }

    fun getAllRecordedPids(): Set<Int> = synchronized(lock) {
        pidRecords.values().toSet()
    }

    fun getMountFailedPids(): Set<Int> = synchronized(lock) {
        mountFailedPids.toSet()
    }

    fun getMountedPackages(): Set<String> = synchronized(lock) {
        mkdirRecords.keySet().toSet()
    }

    fun getMountedDirs(): List<String> = synchronized(lock) {
        mkdirRecords.values().toList()
    }

    fun resetAllMounts() = synchronized(lock) {
        val records = pidRecords.asMap()
            .flatMap { (packageName, pids) -> pids.map { packageName to it } }
        records.forEach { (packageName, pid) ->
            val uid = runCatching { RuntimeFileUtils.read_uid(pid) }.getOrDefault(-1)
            if (uid < 0) {
                Log.w("MC_REDIRECT", "[Mounter] skip reset for dead pid=$pid pkg=$packageName")
                return@forEach
            }
            val result = RuntimeFileUtils.bind_mount_result(
                pid, uid,
                unmountDataRestriction = false,
                fuseBypass = false,
                sources = emptyArray(),
                targets = emptyArray()
            )
            if (!result.success) {
                Log.w(
                    "MC_REDIRECT",
                    "[Mounter] reset mount failed pkg=$packageName pid=$pid detail=${result.reason}"
                )
            }
        }

        mountFailedPids.clear()
        mountRetryCount.clear()
        rmdirPackages.clear()
        rmdirQueueSize = 0
        pidRecords.clear()
        mkdirRecords.keySet().toList().forEach { removeMountDirsLocked(it) }
    }

    fun getTotalAttempts(): Int = totalAttempts.get()

    fun getFailureCount(): Int = failureCount.get()

    fun getLastMountFailure(): MountFailure? = synchronized(lock) {
        lastMountFailure
    }

    /** 最近一次挂载失败对应的统一错误码；无失败时返回空串。 */
    fun getLastMountErrorCode(): String = synchronized(lock) {
        lastMountFailure?.let { errorCodeForStage(it.stage) } ?: ""
    }

    fun onDestroy() {
        thread.quit()
    }

    companion object {
        private const val VALIDATE_PID_DELAY_PER_PACKAGE: Long = 500L
        private const val VALIDATE_PID_DELAY_MAX: Long = 3000L

        /** mount 失败重试最大次数 */
        private const val MAX_MOUNT_RETRIES = 3

        /** mount 失败重试延迟序列（毫秒）：2s → 5s → 15s */
        private val RETRY_DELAYS_MS = longArrayOf(2000, 5000, 15000)
    }

    data class MountFailure(
        val timeMillis: Long,
        val packageName: String,
        val pid: Int,
        val uid: Int,
        val reason: String,
        val stage: String = "",
        val errno: Int = 0,
        val failedIndex: Int = -1,
        val source: String = "",
        val target: String = "",
        /** native 事务阶段名，用于快速定位故障环节。 */
        val phaseName: String = "unknown",
        /** 目标命名空间可能残留部分效果。 */
        val namespaceDirty: Boolean = false,
        /** 处置分类：是否值得重试（污染/永久失败为 false）。 */
        val retryable: Boolean = false,
        /** native 侧已终止目标应用。 */
        val targetTerminated: Boolean = false,
        /** server 侧发起过安全停止。 */
        val forceStopAttempted: Boolean = false,
        /** 安全停止是否成功。 */
        val forceStopSucceeded: Boolean = false,
    )
}
