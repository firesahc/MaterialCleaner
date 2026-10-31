package me.gm.cleaner.runtime.server

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.storage.VolumeInfo
import api.SystemService
import hidden.HiddenApiBridge.UserHandle_isIsolated
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.model.PackageStatus
import me.gm.cleaner.runtime.server.process.BaseProcessObserver
import me.gm.cleaner.runtime.server.process.PackageInfoMapper
import me.gm.cleaner.runtime.server.vfs.CensusEntry
import me.gm.cleaner.runtime.server.vfs.PidMountClassifier
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensusBuilder
import me.gm.cleaner.runtime.server.lifecycle.ObserverManager
import me.gm.cleaner.runtime.server.storage.StorageEventListenerDelegate
import me.gm.cleaner.runtime.server.orchestrator.LayerId
import me.gm.cleaner.runtime.server.orchestrator.LayerReport
import me.gm.cleaner.runtime.server.orchestrator.LayerState
import java.io.File
import java.util.TreeMap

/**
 * VFS bind mount 层的生命周期门面。
 *
 * CleanerServer 只转交系统存储事件；本类负责与现有 observer/mounter 体系交互，
 * 让 VFS 层细节集中在单一边界内。
 */
class VfsLayerController {

    private companion object {
        /** 普查节流：collectReport 每 2s 心跳复用缓存，避免诊断成为负载源。 */
        private const val CENSUS_THROTTLE_MS = 30_000L
    }

    @Volatile
    private var lastCensusAt: Long = 0L

    @Volatile
    private var cachedCensus: VfsProcessCensus? = null

    fun isFuseBpfEnabled(): Boolean {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return observer != null && observer.isFuseBpfEnabled()
    }

    fun onStorageMounted(vol: VolumeInfo, isPrimary: Boolean, isJustMounted: Boolean) {
        if (isPrimary) {
            RuntimeFileUtils.setExternalStorageDir(File(vol.path, "0"))
        }
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        if (observer != null) {
            val mountUserId = StorageEventListenerDelegate.getMountUserId(vol)
            observer.mountedStorage.add(mountUserId)
            if (isJustMounted) {
                if (isPrimary) {
                    observer.remountAll()
                } else {
                    observer.remountAllWithCheck()
                }
            } else if (isPrimary) {
                observer.recordAll()
            }
        }
        if (isPrimary && observer != null && observer.isFuseBpfEnabled()) {
            switchAppDataDirOwnersAsync()
        }
    }

    fun onStorageUnmounted(vol: VolumeInfo) {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java) ?: return
        val mountUserId = StorageEventListenerDelegate.getMountUserId(vol)
        observer.mountedStorage.remove(mountUserId)
    }

    fun remount(packageNames: Array<String>) {
        ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.remountForPackages(packageNames)
    }

    fun getMountedDirs(): List<String> {
        return ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.getMountedDirs()
            ?: emptyList()
    }

    fun shutdown() {
        ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?.resetAllMounts()
    }

    fun getPackageStatus(packageName: String, flags: Int): PackageStatus {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return PackageStatus()
        val processes = selectProcesses(flags, observer.getStartUpAwarePids(packageName))
        val status = PackageStatus()
        val pids = mutableListOf<Int>()
        val pidFlags = mutableListOf<Int>()
        val userIds = mutableListOf<Int>()
        val startUpAwarePids = observer.getStartUpAwarePids(packageName)
        val mountFailedPids = observer.getMountFailedPids()
        val mkdir = observer.getPreparedPackages().contains(packageName)
        // 只有已配置重定向的包才走 uid 权威判定，非重定向包保持 pkgList 语义，
        // 避免把不在重定向配置中的查询误判成没有进程。
        val uidMappingReady = VfsRuntimePolicy.getStorageRedirectPackages()
            .contains(packageName) && PackageInfoMapper.isMappingReady()

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                if (!isProcessOfPackage(procInfo, packageName, uidMappingReady)) return@forEach
                val userId = procInfo.uid.toUserId()
                pids.add(procInfo.pid)
                pidFlags.add(buildPidFlag(
                    procInfo.pid,
                    packageName,
                    userId,
                    startUpAwarePids,
                    mountFailedPids,
                    mkdir,
                ))
                userIds.add(userId)
            }

        status.pids = pids.toIntArray()
        status.pidFlags = pidFlags.toIntArray()
        status.userIds = userIds.toIntArray()
        return status
    }

    fun getSrPackagesStatus(flags: Int): Map<String, PackageStatus> {
        return enumerateSrProcesses(flags).statuses.mapValues { (_, value) -> value.toPackageStatus() }
    }

    /**
     * P1-C 普查（节流 30s）：与 [collectReport] 复用同一次采样，产出分母与
     * 有界 srStatus 明细。诊断投影，不驱动接管行为；失败只降诊断能力。
     */
    fun collectCensus(now: Long): VfsProcessCensus {
        cachedCensus?.let {
            if (now >= lastCensusAt && now - lastCensusAt < CENSUS_THROTTLE_MS) return it
        }
        val enumeration = enumerateSrProcesses(PackageStatus.GET_FROM_ALL_PROCESS)
        val census = VfsProcessCensusBuilder.build(
            sampledAt = now,
            entries = enumeration.entries,
            unattributedPids = enumeration.unattributedPids,
        )
        lastCensusAt = now
        cachedCensus = census
        return census
    }

    private data class SrEnumeration(
        val statuses: TreeMap<String, MutablePackageStatus>,
        val entries: List<CensusEntry>,
        val unattributedPids: Int,
    )

    private fun enumerateSrProcesses(flags: Int): SrEnumeration {
        val empty = SrEnumeration(TreeMap(), emptyList(), 0)
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return empty
        val startUpAwarePids = observer.getAllStartUpAwarePids()
        val mountFailedPids = observer.getMountFailedPids()
        val mountedPackages = observer.getPreparedPackages()
        val srPackages = VfsRuntimePolicy.getStorageRedirectPackages()
        val processes = selectProcesses(flags, startUpAwarePids)
        // 映射就绪用 uid 权威归属，未就绪保守回退 pkgList，绝不直接返回空。
        val uidMappingReady = PackageInfoMapper.isMappingReady()
        val statuses = TreeMap<String, MutablePackageStatus>()
        val entries = mutableListOf<CensusEntry>()
        var unattributed = 0

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                val packageName =
                    resolveSrPackageName(procInfo, srPackages, uidMappingReady)
                if (packageName == null) {
                    unattributed++
                    return@forEach
                }
                val userId = procInfo.uid.toUserId()
                val flag = buildPidFlag(
                    procInfo.pid,
                    packageName,
                    userId,
                    startUpAwarePids,
                    mountFailedPids,
                    mountedPackages.contains(packageName),
                )
                val status = statuses.getOrPut(packageName) { MutablePackageStatus() }
                status.pids.add(procInfo.pid)
                status.pidFlags.add(flag)
                status.userIds.add(userId)
                entries.add(CensusEntry(packageName, procInfo.pid, procInfo.uid, flag))
            }

        return SrEnumeration(statuses, entries, unattributed)
    }

    fun collectReport(generation: Long, now: Long): LayerReport {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return if (observer != null) {
            val mountedPackages = observer.getPreparedPackages().size
            val recordedPids = observer.getAllStartUpAwarePids().size
            val mountFailedPids = observer.getMountFailedPids().size
            val mountTotalAttempts = observer.getTotalMountAttempts()
            val mountFailureCount = observer.getMountFailureCount()
            val mountGateRefusals = observer.getGateRefusalCount()
            val lastFailure = observer.getLastMountFailure()
            val lastMountErrorCode = observer.getLastMountErrorCode()
            // 同一次普查采样复用：分母与 srStatus 明细同源，诊断失败不改行为。
            val census = runCatching { collectCensus(now) }.getOrNull()
            val state = if (mountFailedPids > 0) {
                LayerState.DEGRADED
            } else {
                LayerState.HEALTHY
            }
            LayerReport(
                id = LayerId.VFS,
                state = state,
                generation = generation,
                lastHeartbeatAt = if (state == LayerState.HEALTHY) now else 0L,
                lastErrorAt = if (state == LayerState.HEALTHY) 0L else now,
                lastError = if (state == LayerState.HEALTHY) {
                    null
                } else {
                    "VFS mount failures detected"
                },
                metrics = mapOf(
                    "started" to "true",
                    "configuredPackages" to VfsRuntimePolicy
                        .getStorageRedirectPackages()
                        .size
                        .toString(),
                    "mountedPackages" to mountedPackages.toString(),
                    "recordedPids" to recordedPids.toString(),
                    "mountFailedPids" to mountFailedPids.toString(),
                    "mountTotalAttempts" to mountTotalAttempts.toString(),
                    "mountFailureCount" to mountFailureCount.toString(),
                    "mountGateRefusals" to mountGateRefusals.toString(),
                    "lastMountFailureAt" to (lastFailure?.timeMillis ?: 0L).toString(),
                    "lastMountFailurePackage" to (lastFailure?.packageName ?: ""),
                    "lastMountFailurePid" to (lastFailure?.pid ?: 0).toString(),
                    "lastMountFailureUid" to (lastFailure?.uid ?: 0).toString(),
                    "lastMountFailureReason" to (lastFailure?.reason ?: ""),
                    "lastMountFailureStage" to (lastFailure?.stage ?: ""),
                    "lastMountFailureErrno" to (lastFailure?.errno ?: 0).toString(),
                    "lastMountFailureIndex" to (lastFailure?.failedIndex ?: -1).toString(),
                    "lastMountFailureSource" to (lastFailure?.source ?: ""),
                    "lastMountFailureTarget" to (lastFailure?.target ?: ""),
                    "topErrorCode" to lastMountErrorCode,
                    "lastMountRetryable" to (lastFailure?.retryable ?: false).toString(),
                    "lastMountNamespaceDirty" to (lastFailure?.namespaceDirty ?: false).toString(),
                    "lastMountForceStopAttempted" to
                            (lastFailure?.forceStopAttempted ?: false).toString(),
                    "lastMountForceStopSucceeded" to
                            (lastFailure?.forceStopSucceeded ?: false).toString(),
                ) + (census?.toMetrics() ?: emptyMap()),
            )
        } else {
            LayerReport(
                id = LayerId.VFS,
                state = LayerState.UNAVAILABLE,
                generation = generation,
                lastErrorAt = now,
                lastError = "BaseProcessObserver unavailable",
                metrics = mapOf("started" to "false"),
            )
        }
    }

    private fun switchAppDataDirOwnersAsync() {
        Thread {
            for (userId in SystemService.getUserIdsNoThrow()) {
                for (packageName in VfsRuntimePolicy.getStorageRedirectPackages()) {
                    val ai = SystemService.getApplicationInfoNoThrow(packageName, 0, userId)
                        ?: continue
                    RuntimeFileUtils.switch_owner(
                        RuntimeFileUtils.getPathAsUser(
                            RuntimeFileUtils.buildExternalStorageAppDataDirs(packageName).path,
                            userId,
                        ),
                        ai.uid,
                        true,
                    )
                }
            }
        }.start()
    }

    /**
     * 把进程归属到唯一一个重定向包，空表示它不属于任何重定向包。
     * 不能只用 pkgList：宿主进程会把他人包名也列进来，那种进程永远不是重定向对象，
     * 一旦并入就会被判成异常且随宿主生灭抖动。改用 uid（共享 uid 场景退化为进程名）权威映射天然剔除。
     * 映射未就绪时保守回退旧 pkgList 语义，绝不直接返回空，避免把全部进程判成非目标。
     */
    private fun resolveSrPackageName(
        procInfo: RunningAppProcessInfo,
        srPackages: Set<String>,
        uidMappingReady: Boolean,
    ): String? {
        if (!uidMappingReady) {
            return procInfo.pkgList?.firstOrNull { srPackages.contains(it) }
        }
        val srPackageName = PackageInfoMapper.getSrPackageName(procInfo.uid, procInfo.processName)
        return if (srPackageName != null && srPackages.contains(srPackageName)) srPackageName else null
    }

    private fun isProcessOfPackage(
        procInfo: RunningAppProcessInfo,
        packageName: String,
        uidMappingReady: Boolean,
    ): Boolean {
        if (!uidMappingReady) {
            return procInfo.pkgList?.contains(packageName) == true
        }
        return PackageInfoMapper.getSrPackageName(procInfo.uid, procInfo.processName) == packageName
    }

    private fun selectProcesses(flags: Int, startUpAwarePids: Set<Int>): List<RunningAppProcessInfo> {
        val processes = SystemService.getRunningAppProcessesNoThrow()
        return when (flags) {
            PackageStatus.GET_FROM_ALL_PROCESS -> processes
            PackageStatus.GET_FROM_RECORDS -> processes.filter { startUpAwarePids.contains(it.pid) }
            else -> emptyList()
        }
    }

    private fun buildPidFlag(
        pid: Int,
        packageName: String,
        userId: Int,
        startUpAwarePids: Set<Int>,
        mountFailedPids: Set<Int>,
        mkdir: Boolean,
    ): Int {
        val targets = VfsRuntimePolicy.getMountTargets(packageName, userId)
        var pidFlag = if (targets.isEmpty()) {
            // 该 (package,user) 无挂载目标：已观测但不在管理范围，不进 managed 分母。
            PackageStatus.PID_FLAG_UNMANAGED
        } else {
            PidMountClassifier.classify(
                targets.size,
                RuntimeFileUtils.check_mounts(pid, targets.toTypedArray()),
            )
        }
        if (startUpAwarePids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_STARTUP_AWARE
        }
        if (mountFailedPids.contains(pid)) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNT_FAILED
        }
        if (!mkdir) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MKDIR_FAILED
        }
        return normalizePidFlag(pidFlag, pid)
    }

    /**
     * 终态维度互斥校验（P1-A）：非法组合运行时退化为 UNKNOWN + 诊断日志
     * （测试中强断言）。判定规则归 [PidMountClassifier]。
     */
    private fun normalizePidFlag(pidFlag: Int, pid: Int): Int {
        if (!PidMountClassifier.isLegalCombination(pidFlag)) {
            android.util.Log.w(
                "VfsLayerController",
                "Illegal pidFlag combination $pidFlag for pid=$pid, degrading to UNKNOWN",
            )
            val stateBits = pidFlag and (
                PackageStatus.PID_FLAG_MOUNTED or
                    PackageStatus.PID_FLAG_PARTIALLY_MOUNTED or
                    PackageStatus.PID_FLAG_NOT_MOUNTED or
                    PackageStatus.PID_FLAG_UNMANAGED or
                    PackageStatus.PID_FLAG_UNKNOWN or
                    PackageStatus.PID_FLAG_DELETED or
                    PackageStatus.PID_FLAG_OVERRIDE
                )
            return (pidFlag and stateBits.inv()) or PackageStatus.PID_FLAG_UNKNOWN
        }
        return pidFlag
    }

    private class MutablePackageStatus {
        val pids = mutableListOf<Int>()
        val pidFlags = mutableListOf<Int>()
        val userIds = mutableListOf<Int>()

        fun toPackageStatus(): PackageStatus {
            val status = PackageStatus()
            status.pids = pids.toIntArray()
            status.pidFlags = pidFlags.toIntArray()
            status.userIds = userIds.toIntArray()
            return status
        }
    }
}
