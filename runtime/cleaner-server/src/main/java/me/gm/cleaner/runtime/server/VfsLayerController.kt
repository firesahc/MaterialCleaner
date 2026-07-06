package me.gm.cleaner.runtime.server

import android.app.ActivityManager.RunningAppProcessInfo
import android.os.storage.VolumeInfo
import api.SystemService
import hidden.HiddenApiBridge.UserHandle_isIsolated
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.model.PackageStatus
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
import me.gm.cleaner.runtime.server.observer.StorageEventListenerDelegate
import me.gm.cleaner.runtime.server.orchestrator.LayerId
import me.gm.cleaner.runtime.server.orchestrator.LayerReport
import me.gm.cleaner.runtime.server.orchestrator.LayerState
import java.io.File
import java.util.TreeMap

/**
 * VFS bind mount 层的生命周期门面。
 *
 * CleanerServer 只转交系统存储事件；本类负责与旧 observer/mounter 体系交互，
 * 让 VFS 层细节集中在单一边界内，便于后续继续迁移到快照驱动实现。
 */
class VfsLayerController {

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
        val mkdir = observer.getMountedPackages().contains(packageName)

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                procInfo.pkgList
                    .filter { it == packageName }
                    .forEach {
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
            }

        status.pids = pids.toIntArray()
        status.pidFlags = pidFlags.toIntArray()
        status.userIds = userIds.toIntArray()
        return status
    }

    fun getSrPackagesStatus(flags: Int): Map<String, PackageStatus> {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
            ?: return emptyMap()
        val startUpAwarePids = observer.getAllStartUpAwarePids()
        val mountFailedPids = observer.getMountFailedPids()
        val mountedPackages = observer.getMountedPackages()
        val srPackages = ServicePreferences.srPackages
        val processes = selectProcesses(flags, startUpAwarePids)
        val statuses = TreeMap<String, MutablePackageStatus>()

        processes
            .asSequence()
            .filter { !UserHandle_isIsolated(RuntimeFileUtils.read_uid(it.pid)) }
            .sortedBy { it.pid }
            .forEach { procInfo ->
                procInfo.pkgList
                    .filter { srPackages.contains(it) }
                    .forEach { packageName ->
                        val userId = procInfo.uid.toUserId()
                        val status = statuses.getOrPut(packageName) { MutablePackageStatus() }
                        status.pids.add(procInfo.pid)
                        status.pidFlags.add(buildPidFlag(
                            procInfo.pid,
                            packageName,
                            userId,
                            startUpAwarePids,
                            mountFailedPids,
                            mountedPackages.contains(packageName),
                        ))
                        status.userIds.add(userId)
                    }
            }

        return statuses.mapValues { (_, value) -> value.toPackageStatus() }
    }

    fun collectReport(generation: Long, now: Long): LayerReport {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        return if (observer != null) {
            val mountedPackages = observer.getMountedPackages().size
            val recordedPids = observer.getAllStartUpAwarePids().size
            val mountFailedPids = observer.getMountFailedPids().size
            val mountTotalAttempts = observer.getTotalMountAttempts()
            val mountFailureCount = observer.getMountFailureCount()
            val state = if (mountFailedPids > 0 || mountFailureCount > 0) {
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
                    "configuredPackages" to ServicePreferences.srPackages.size.toString(),
                    "mountedPackages" to mountedPackages.toString(),
                    "recordedPids" to recordedPids.toString(),
                    "mountFailedPids" to mountFailedPids.toString(),
                    "mountTotalAttempts" to mountTotalAttempts.toString(),
                    "mountFailureCount" to mountFailureCount.toString(),
                ),
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
                for (packageName in ServicePreferences.srPackages) {
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
        val targets = ServicePreferences.getPackageSr(packageName, userId).second
        val mountedIndices = RuntimeFileUtils.check_mounts(pid, targets.toTypedArray())
        var pidFlag = 0
        if (mountedIndices == null) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_UNKNOWN
        } else if (mountedIndices.any { it < 0 }) {
            if (mountedIndices.contains(-1)) {
                pidFlag = pidFlag or PackageStatus.PID_FLAG_DELETED
            }
            if (mountedIndices.contains(-2)) {
                pidFlag = pidFlag or PackageStatus.PID_FLAG_OVERRIDE
            }
        } else if (targets.size == mountedIndices.size) {
            pidFlag = pidFlag or PackageStatus.PID_FLAG_MOUNTED
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
