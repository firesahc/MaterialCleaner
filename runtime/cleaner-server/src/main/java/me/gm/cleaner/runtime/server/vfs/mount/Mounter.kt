package me.gm.cleaner.runtime.server.vfs.mount

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
import me.gm.cleaner.runtime.server.orchestrator.ServerErrorJournal
import api.SystemService
import me.gm.cleaner.runtime.server.VfsRuntimePolicy
import me.gm.cleaner.core.storage.redirect.domain.RuntimeMountPlan
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

    /** 当前 cleaner-server 受管理的根目录集合。每个元素如 "/storage/emulated/0"、"/storage/emulated/999"。 */
    private val managedRoots: MutableSet<String> = mutableSetOf<String>().apply {
        // 向后兼容：默认加入当前外部存储根，使单用户场景无需显式调用 registerManagedRoot 即可正常工作。
        val defaultRoot = RuntimeFileUtils.externalStorageDir.path
        if (defaultRoot.isNotBlank()) {
            add(defaultRoot)
        }
    }

    /** mount 尝试总次数（含重试），用于 collectStatus metrics */
    val totalAttempts: AtomicInteger = AtomicInteger(0)
    /** mount 失败总次数（含重试耗尽），用于 collectStatus metrics */
    val failureCount: AtomicInteger = AtomicInteger(0)
    /**
     * 目标门拒绝次数：目标 pid 已消失/被复用，或身份已变化，拒绝挂载。
     * 门拒是安全机制正常工作，不是失败，单独计数，不进 failureCount、
     * mountFailedPids 与 lastMountFailure。
     */
    val gateRefusalCount: AtomicInteger = AtomicInteger(0)
    /** 每 pid 重试计数，达到 MAX_MOUNT_RETRIES 后放弃 */
    private val mountRetryCount = mutableMapOf<Int, Int>()
    /**
     * 重投候选（包 + 用户复合键，内容哈希锚定）：无 pid 级状态。
     * 即时重试耗尽后，心跳按冷却经正常 remount 链重投；
     * 计划内容一变旧条目失配作废；take 语义（取出即删，无残留可漏）。
     */
    private val failedPackages = mutableMapOf<Pair<String, Int>, FailedPackage>()
    private var lastMountFailure: MountFailure? = null

    /**
     * 错误日志节流：同一错误码 30 秒内只完整输出一次，
     * 防止挂载风暴（如 EBUSY 循环）刷屏掩盖其他层故障信号。
     */
    private val errorLogThrottle = ErrorLogThrottle()

    fun mountForAllPackages(): Boolean =
        VfsRuntimePolicy.shouldMountForAllPackages()

    fun bindMountAsync(packageName: String, pid: Int, uid: Int) {
        handler.post {
            synchronized(lock) { bindMountLocked(packageName, pid, uid) }
        }
    }

    fun bindMount(packageName: String, pid: Int, uid: Int): Boolean = synchronized(lock) {
        bindMountLocked(packageName, pid, uid)
    }

    internal val isFuseBpfEnabled: Boolean
        get() = VfsRuntimePolicy.isFuseBpfEnabled()

    private fun bindMountLocked(packageName: String, pid: Int, uid: Int): Boolean {
        totalAttempts.incrementAndGet()
        // 代际标注：使 mount 与 FUSE apply（同代际快照）可在日志中直接配对。
        val policyGeneration = VfsRuntimePolicy.currentPolicy().generation
        Log.i("MC_REDIRECT", "[Mounter] bindMountLocked pkg=$packageName pid=$pid uid=$uid " +
                "attempt=${totalAttempts.get()} gen=$policyGeneration")
        val userId = uid.toUserId()
        val recordExternalAppSpecificStorage =
            VfsRuntimePolicy.shouldRecordExternalAppSpecificStorage(packageName)
        val plan = VfsRuntimePolicy.getMountPlan(packageName, userId)
        // 执行时刻计划内容哈希：失败登记用同一快照，无采样竞态；
        // 空计划（无规则）无收敛对象，记 null 使失败不建条目。
        val planHash: Int? = plan?.takeIf { !it.isEmpty() }
            ?.let { it.sources.zip(it.targets).hashCode() }

        if (plan == null || plan.isEmpty()) {
            val result = RuntimeFileUtils.bind_mount_result(
                pid, uid,
                !isFuseBpfEnabled && recordExternalAppSpecificStorage, false,
                emptyArray(), emptyArray()
            )
            Log.i("MC_REDIRECT", "[Mounter] bindMount result=${result.success} " +
                    "pkg=$packageName pid=$pid detail=${result.reason}")
            return handleBindMountResultLocked(packageName, pid, uid, result, planHash)
        }

        pidRecords.put(packageName, pid)
        if (!mkdirRecords.containsKey(packageName)) {
            val mkdirRecord = mutableSetOf<String>()
            plan.mkdirList.forEach { record(mkdirRecord, it) }
            // 交给 native 建目录的是**完整展开集**（含系统结构目录）—— 它们本来就存在，
            // mkdirs 对已存在目录是 no-op，让 native 看到父级反而更稳。
            if (!RuntimeFileUtils.auto_prepare_dirs(mkdirRecord.toTypedArray(), uid)) {
                // 目录准备失败，不继续挂载，避免脏目录或部分创建的目录无法回收。
                val result = RuntimeFileUtils.BindMountResult(
                    success = false,
                    stage = "dir_prepare_failed",
                    errno = 0,
                    error = "directory preparation failed",
                )
                return handleBindMountResultLocked(packageName, pid, uid, result, null)
            }
            // 而登记给**回收侧**的必须是滤掉结构目录后的集合，见 registerMkdirListLocked。
            registerMkdirListLocked(packageName, plan)
        }
        val result = RuntimeFileUtils.bind_mount_result(
            pid, uid,
            !isFuseBpfEnabled && recordExternalAppSpecificStorage, isFuseBpfEnabled,
            plan.sources.toTypedArray(), plan.targets.toTypedArray()
        )
        Log.i("MC_REDIRECT", "[Mounter] bindMount result=${result.success} pkg=$packageName " +
                "pid=$pid sources=${plan.sources} targets=${plan.targets} detail=${result.reason}")
        // Issue #3：fuse_bypass 只是 FUSE 私有目录拦截优化，失败时降级为无 bypass
        // 再试一次，不记失败、不调度 pid 重试。回滚已保证 namespace 干净，
        // 且 fuseBypass=false 时 native 直接跳过 bypass 块，不会二次触发。
        val effective = if (MountFailureRetryPolicy.shouldFallbackWithoutBypass(
                result.stage, result.namespaceDirty,
                result.targetTerminated, isFuseBpfEnabled)) {
            Log.w("MC_REDIRECT", "[Mounter] fuse bypass failed (${result.reason}), " +
                    "degraded retry without bypass pkg=$packageName pid=$pid")
            recordBypassSkippedLocked(packageName, pid, uid, result)
            val fallback = RuntimeFileUtils.bind_mount_result(
                pid, uid,
                !isFuseBpfEnabled && recordExternalAppSpecificStorage, false,
                plan.sources.toTypedArray(), plan.targets.toTypedArray()
            )
            Log.i("MC_REDIRECT", "[Mounter] degraded retry result=${fallback.success} " +
                    "pkg=$packageName pid=$pid detail=${fallback.reason}")
            fallback
        } else {
            result
        }
        return handleBindMountResultLocked(packageName, pid, uid, effective, planHash)
    }

    private fun handleBindMountResultLocked(
        packageName: String,
        pid: Int,
        uid: Int,
        result: RuntimeFileUtils.BindMountResult,
        planHash: Int?,
    ): Boolean {
        val packageUser = packageName to uid.toUserId()
        if (result.success) {
            // 成功不终结长期条目：条目只由 take 终结。
            // process 粒度的成功不得清 package/user 粒度的 obligation。
            mountFailedPids.remove(pid)
            mountRetryCount.remove(pid)
            return true
        }
        if (MountFailureRetryPolicy.isExpectedTargetDisappearance(
                result.stage,
                result.namespaceDirty,
            )) {
            // 仅确认的目标退出(target_gone)或身份不匹配(target_identity)在干净
            // namespace 下视为生命周期结果；读取/解析错误走下方常规分类。
            // 脏 namespace 不走此分支，必须保留下方 fail-closed 安全处置。
            // 且不留任何失败痕迹（失败集合残留会让 VFS 永久 DEGRADED）。
            gateRefusalCount.incrementAndGet()
            mountFailedPids.remove(pid)
            mountRetryCount.remove(pid)
            Log.i(
                "MC_REDIRECT",
                "[Mounter] target gate refused pkg=$packageName pid=$pid uid=$uid " +
                    "stage=${result.stage} detail=${result.reason}",
            )
            return false
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
        // 永久失败与空计划不建条目（重投必然复现或无收敛对象；
        // 配置修正后计划内容变化，下次失败自然建新条目）。
        if (planHash != null && disposition.retryable) {
            failedPackages[packageUser] = FailedPackage(
                // 单调时钟：NTP/用户改时间不得拉长或 Judas 冷却。
                lastFailedAtMs = SystemClock.elapsedRealtime(),
                failedPlanHash = planHash,
            )
        }
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
            else -> mountErrorCodeForStage(result.stage)
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

    /**
     * 记录 bypass 降级事件（Issue #3）：仅可观测，不计入 failureCount、
     * mountRetryCount 与 lastMountFailure，降级不是失败。
     */
    private fun recordBypassSkippedLocked(
        packageName: String,
        pid: Int,
        uid: Int,
        result: RuntimeFileUtils.BindMountResult,
    ) {
        val event = ErrorEvent(
            code = ErrorCodes.MOUNT_BYPASS_SKIPPED,
            errno = result.errno,
            subject = "$packageName/pid:$pid",
            atElapsed = SystemClock.elapsedRealtime(),
            detail = buildString {
                append("stage=").append(result.stage)
                if (result.phase >= 0) append(" phase=").append(result.phaseName)
                if (result.error.isNotBlank()) append(" os=").append(result.error)
            },
        )
        if (errorLogThrottle.tryAcquire(event.code, event.atElapsed)) {
            Log.w("MC_REDIRECT", "[Mounter] bypass skipped pkg=$packageName ${event.toCompactString()}")
        }
        ServerErrorJournal.record(event)
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
                if (shouldRunMountRetryLocked(packageName, pid, uid)) {
                    bindMountLocked(packageName, pid, uid)
                }
            }
        }
    }

    private fun shouldRunMountRetryLocked(packageName: String, pid: Int, expectedUid: Int): Boolean {
        if (!mountRetryCount.containsKey(pid)) {
            return false
        }
        if (!File("/proc", pid.toString()).exists()) {
            Log.i("MC_REDIRECT", "[Mounter] skip mount retry for dead pid=$pid pkg=$packageName")
            notifyProcessKilledLocked(packageName, pid)
            return false
        }
        // P0：PID 复用防护。延迟 2-15s 窗口内 pid 可能被新进程复用，
        // 仅查 /proc 存在会 setns 进错误进程。至少比对 uid，不一致则放弃重试。
        val observedUid = try {
            RuntimeFileUtils.read_uid(pid)
        } catch (_: Exception) {
            -1
        }
        if (observedUid != expectedUid) {
            Log.w(
                "MC_REDIRECT",
                "[Mounter] skip mount retry pid reused pid=$pid pkg=$packageName " +
                        "expectedUid=$expectedUid observedUid=$observedUid",
            )
            mountRetryCount.remove(pid)
            notifyProcessKilledLocked(packageName, pid)
            return false
        }
        return true
    }

    private fun record(mkdirRecord: MutableSet<String>, dir: String) {
        // 只接受位于任一受管理根目录之下的严格子路径（不含卷根本身）。
        // 支持跨用户重定向：source 与 target 可分别位于不同 userId 的存储根（如用户 0 → 用户 999）。
        // 卷根本身由 vold 管理，不建也不删。
        if (managedRoots.any { RuntimeFileUtils.childOf(it, dir) }) {
            mkdirRecord.add(dir)
            val parent = File(dir).parent ?: return
            // 递归父级时，只递归至所属 root 处，不越界进入其他 root。
            if (managedRoots.any { RuntimeFileUtils.childOf(it, parent) }) {
                record(mkdirRecord, parent)
            }
        }
    }

    /**
     * 统一的 mkdirList 登记入口：**三处调用点必须都走这里**。
     *
     * ## 为什么必须统一（第 51 批，6.0.2）
     *
     * `mkdirRecords` 有两个消费方，语义完全不同：
     * - `removeMountDirsLocked` 拿它做 **rmdir**（删除）；
     * - `getPreparedPackages` / `getMountedDirs` 拿它回答「目录建好了没」（诊断）。
     *
     * 旧实现三处写法不一致：
     * - `bindMountLocked` 用 `record()` 递归展开父级 ⇒ 集合含 /storage/emulated/0、
     *   /storage/emulated/0/Android、.../Android/data 这些**系统既有**目录；
     * - `checkAndRecordLocked` 与 `remountLocked` 直接 `putAll(plan.mkdirList)`
     *   ⇒ 集合只有挂载点和承载源。
     *
     * 于是「回收会不会去 rmdir 系统目录」取决于该包走了哪条路径 —— 同一份
     * 用户存储，两种行为。这条不一致本身就是缺陷（规则同源的失效形态）。
     *
     * 修法：统一走本方法，登记前先用 {@link ProtectedSystemDirs} 滤掉结构目录。
     * 卷根 / Android / Android/data / obb / media / sandbox 由 vold 与 MediaProvider
     * 管理，本模块既不建也不删。滤掉它们不影响功能：`auto_prepare_dirs` 的
     * `mkdirs()` 本身就会递归补齐缺失的父级（`FileUtils.cpp` 的 mkdirs 实现），
     * 不依赖 Java 侧先把父目录列出来。
     *
     * ## 跨用户支持
     *
     * `plan.mkdirList` 中的 source 与 target 可分别位于不同 userId 的存储根
     *（如用户 0 的文件夹重定向到用户 999）。本方法对每个受管理的根分别筛选
     *属于该 root 的条目，再展开为可回收集合并合并，从而支持跨根边界。
     */
    private fun registerMkdirListLocked(packageName: String, plan: RuntimeMountPlan) {
        if (mkdirRecords.containsKey(packageName)) {
            return
        }
        // 对每个受管理的根，分别筛选属于该 root 的 mkdirList 并展开为可回收集合，最后合并。
        // 支持跨用户：source 与 target 可分别位于不同 userId 的存储根。
        val reclaimable = managedRoots.flatMap { root ->
            plan.mkdirList.filter { it.startsWith(root) }
                .let { MountDirRecords.expandForReclaim(root, it) }
        }.toSet()
        mkdirRecords.putAll(packageName, reclaimable)
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
                val plan = VfsRuntimePolicy.getMountPlan(
                    packageName,
                    procInfo.uid.toUserId(),
                ) ?: return@forEach
                val targets = plan.targets
                val mountedIndices =
                    RuntimeFileUtils.check_mounts(procInfo.pid, targets.toTypedArray())
                if (mountedIndices == null) {
                    // unknown mount status
                } else if (targets.size == mountedIndices.size && mountedIndices.all { it != -1 }) {
                    // record pid
                    pidRecords.put(packageName, procInfo.pid)
                    registerMkdirListLocked(packageName, plan)
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
                    VfsRuntimePolicy.getMountPlan(packageName, userId)?.let { plan ->
                        plan.sources.zip(plan.targets)
                    }
                },
                shouldUnmountDataRestriction = { packageName ->
                    !isFuseBpfEnabled &&
                            VfsRuntimePolicy
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
                            val plan = VfsRuntimePolicy.getMountPlan(packageName, userId)
                                ?: return@forEach
                            pidRecords.put(packageName, procInfo.pid)
                            registerMkdirListLocked(packageName, plan)
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
        // P0：空规则 reset 同样可能污染 namespace（reset.namespaceDirty）或失败，
        // 不能只记事件就让进程继续跑在脏视图上。与 mount 失败同策略：脏且未终止则安全停止。
        if (reset.namespaceDirty && !reset.targetTerminated) {
            val userId = procInfo.uid.toUserId()
            conflictingPackages.forEach { pkg ->
                val stopped = SystemService.forceStopPackageNoThrow(pkg, userId)
                if (stopped) {
                    Log.w("MC_REDIRECT", "[Mounter] safety stop applied for conflict dirty pid=${procInfo.pid} pkg=$pkg")
                } else {
                    Log.e("MC_REDIRECT", "[Mounter] safety stop FAILED for conflict dirty pid=${procInfo.pid} pkg=$pkg")
                }
            }
        }
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
            // 结构目录永不回收：对每个受管理的根，判定该目录是否为结构目录。
            // 支持跨用户：不同 userId 的存储根有各自的结构目录保护（如 /storage/emulated/999/Android/data）。
            // 万一某个设备上结构目录恰好为空，删掉它会连累整个用户存储的 app 目录结构。
            // 只有真正的挂载点/承载源目录才由本模块回收。
            .filter { dir ->
                managedRoots.none { root -> ProtectedSystemDirs.isProtected(root, dir) }
            }
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

    /**
     * 目标目录（mkdir）已就绪的包集合。
     *
     * 语义边界：`mkdirRecords` 只在 `RuntimeFileUtils.auto_prepare_dirs()` **成功**时
     * 写入，所以这里回答的是「目录建好了没」，**不能**用来判断挂载是否成功
     * （挂载成功看 `check_mounts` / `vfsMountedPids`）。原名 getMountedPackages
     * 已在第 38、40 两批把排查方向带偏。
     */
    fun getPreparedPackages(): Set<String> = synchronized(lock) {
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
        failedPackages.clear()
        mountRetryCount.clear()
        rmdirPackages.clear()
        rmdirQueueSize = 0
        pidRecords.clear()
        mkdirRecords.keySet().toList().forEach { removeMountDirsLocked(it) }
    }

    fun getTotalAttempts(): Int = totalAttempts.get()

    fun getFailureCount(): Int = failureCount.get()

    fun getGateRefusalCount(): Int = gateRefusalCount.get()

    fun getLastMountFailure(): MountFailure? = synchronized(lock) {
        lastMountFailure
    }

    /** 最近一次挂载失败对应的统一错误码；无失败时返回空串。 */
    fun getLastMountErrorCode(): String = synchronized(lock) {
        lastMountFailure?.let { mountErrorCodeForStage(it.stage) } ?: ""
    }

    /**
     * 消费失败条目三岔：KEEP（冷却未到，原样保留）、TAKE（到期且哈希一致，
     * 取出去重投）、DISCARD（哈希失配或计划消失，旧条目作废）。
     * 消费按 (package,user) 键，执行按包去重（remount API 本来包级）；
     * 调用方经正常 remount 链重投（新鲜进程表 + 选择策略 + 隔离过滤 + 身份门）。
     */
    fun consumeDueFailedPackages(nowMs: Long = SystemClock.elapsedRealtime()): List<String> =
        synchronized(lock) {
            val takeKeys = mutableListOf<Pair<String, Int>>()
            val discardKeys = mutableListOf<Pair<String, Int>>()
            for ((packageUser, failed) in failedPackages) {
                val (packageName, userId) = packageUser
                val currentPlanHash = VfsRuntimePolicy.getMountPlan(packageName, userId)
                    ?.takeIf { !it.isEmpty() }
                    ?.let { it.sources.zip(it.targets).hashCode() }
                if (currentPlanHash == null || currentPlanHash != failed.failedPlanHash) {
                    discardKeys += packageUser
                } else if (MountFailureRetryPolicy.shouldRequeueFailedPackage(
                        nowMs = nowMs,
                        lastFailedAtMs = failed.lastFailedAtMs,
                        failedPlanHash = failed.failedPlanHash,
                        currentPlanHash = currentPlanHash,
                    )
                ) {
                    takeKeys += packageUser
                }
            }
            (discardKeys + takeKeys).forEach { failedPackages.remove(it) }
            takeKeys.map { it.first }.distinct()
        }

    /** 注册一个受管理的根目录（供跨用户场景调用，如 "/storage/emulated/999"）。 */
    fun registerManagedRoot(root: String) = synchronized(lock) {
        if (root.isNotBlank()) {
            managedRoots.add(root)
            Log.i("MC_REDIRECT", "[Mounter] registerManagedRoot root=$root")
        }
    }

    /** 移除一个受管理的根目录。 */
    fun unregisterManagedRoot(root: String) = synchronized(lock) {
        val removed = managedRoots.remove(root)
        if (removed) {
            Log.i("MC_REDIRECT", "[Mounter] unregisterManagedRoot root=$root")
        }
        removed
    }

    /** 获取当前受管理的根目录集合的副本。 */
    fun getManagedRoots(): Set<String> = synchronized(lock) {
        managedRoots.toSet()
    }

    fun onDestroy() {
        managedRoots.clear()
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

    /** 心跳重投候选：上次失败时刻 + 失败时计划内容哈希（单调时钟）。 */
    private data class FailedPackage(
        val lastFailedAtMs: Long,
        val failedPlanHash: Int,
    )

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

/** native 失败阶段到统一错误码的映射。 */
internal fun mountErrorCodeForStage(stage: String): String = when (stage) {
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
 * 用户存储内的**结构目录**判定（永不回收）。
 *
 * `RuntimeMountPlan.mkdirList` 是「挂载点 + 承载源」，`Mounter.record()` 会把它们的
 * 父级一并纳入，于是 `/storage/emulated/0/Android`、`.../Android/data` 这类卷内
 * 结构目录也会被登记。回收侧（`removeMountDirsLocked`）用的是同一份集合，
 * 而 `rm_dir` 对空目录会成功 —— 一旦某个设备上这些目录恰好为空，删掉就会
 * 连累整个用户存储的 app 目录结构。结构目录归 vold/MediaProvider 管。
 */
internal object ProtectedSystemDirs {
    private val RELATIVE_PATHS = setOf(
        "Android",
        "Android/data",
        "Android/obb",
        "Android/media",
        "Android/sandbox",
    )

    fun isProtected(root: String, dir: String): Boolean {
        if (root.isEmpty() || dir.length <= root.length) return true
        val relative = dir.removePrefix(root).removePrefix("/")
        // 结构目录本身及其子路径均受保护，避免 /Android/data/com.example.app 等
        // 被误判为可回收目录。
        return RELATIVE_PATHS.any { protected ->
            relative == protected || relative.startsWith("$protected/")
        }
    }
}

/**
 * 把 `RuntimeMountPlan.mkdirList` 展开成**可回收**的目录集合。
 *
 * 纯函数（不碰文件系统），因此单测可以逐条钉死。三件事：
 * 1. 只接受卷根的**严格子路径** —— 卷根本身由 vold 管理，不建也不删；
 * 2. 递归展开父级，让「建目录」能覆盖缺失的中间层；
 * 3. 滤掉 [ProtectedSystemDirs] 里的结构目录 —— 它们**可以建**（mkdirs 对已存在
 *    目录是 no-op），但**绝不回收**（rmdir 对空目录会成功，删掉就连累整个存储）。
 *
 * 为什么建与删要用两份不同的集合：`auto_prepare_dirs` 需要看到父级才能补建缺失层，
 * 而回收侧多一个父级就多一次误删风险。二者语义不同，不能共用同一份列表 ——
 * 第 51 批之前三处调用点各写一套，正是这里最容易漂移的地方。
 */
internal object MountDirRecords {

    /**
     * @param root 卷根（`/storage/emulated/0`）；为空时返回空集合（fail-closed）
     * @param mkdirList 挂载点 + 承载源
     */
    fun expandForReclaim(root: String, mkdirList: Collection<String>): Set<String> {
        if (root.isEmpty()) return emptySet()
        val expanded = mutableSetOf<String>()
        mkdirList.forEach { expandInto(expanded, root, it) }
        return expanded.filterNotTo(mutableSetOf()) { ProtectedSystemDirs.isProtected(root, it) }
    }

    private fun expandInto(acc: MutableSet<String>, root: String, dir: String) {
        if (dir.length <= root.length) return
        if (!isStrictChild(root, dir)) return
        acc.add(dir)
        // 用**字符串截取**取父级，不用 `File(dir).parent`：后者依赖平台的路径分隔符
        // （JVM 单测跑在 Windows 上会返回反斜杠路径，递归当场断裂）。这些路径恒为
        // Android 的绝对路径，按 '/' 截取既平台无关又不会做多余的规范化。
        val cut = dir.lastIndexOf('/')
        if (cut <= 0) return
        expandInto(acc, root, dir.substring(0, cut))
    }

    /** `child` 是否位于 `parent` 之下（按路径段比较，`/a/bc` 不算 `/a/b` 的子路径）。 */
    fun isStrictChild(parent: String, child: String): Boolean {
        val prefix = if (parent.endsWith("/")) parent else "$parent/"
        return child.length > prefix.length && child.startsWith(prefix)
    }
}
