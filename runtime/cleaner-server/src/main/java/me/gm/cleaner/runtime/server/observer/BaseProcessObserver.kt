package me.gm.cleaner.runtime.server.observer

import android.app.ActivityManager
import androidx.annotation.CallSuper
import api.SystemService
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.runtime.server.VfsRuntimeConfigStore
import java.util.concurrent.CopyOnWriteArraySet

abstract class BaseProcessObserver : BaseObserver() {
    protected val mounter: Mounter = Mounter()

    val mountedStorage: CopyOnWriteArraySet<Int> = CopyOnWriteArraySet()

    protected fun isMounterActiveForUser(userId: Int): Boolean =
        mountedStorage.contains(userId)

    protected fun isMounterActiveForUid(uid: Int): Boolean = isMounterActiveForUser(uid.toUserId())

    private fun getRunningAppProcesses(packageNames: Array<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountForPackages(packageNames: Array<String>) {
        mounter.forProcList(getRunningAppProcesses(packageNames), false, true)
    }

    private fun getRunningAppProcesses(packageNames: Iterable<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            false,
            true,
        )
    }

    fun remountAllWithCheck() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            true,
            true,
        )
    }

    fun recordAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimeConfigStore.getStorageRedirectPackages()),
            true,
            false,
        )
    }

    fun isFuseBpfEnabled(): Boolean = mounter.isFuseBpfEnabled

    fun getStartUpAwarePids(packageName: String): Set<Int> = mounter.getRecordedPids(packageName)

    fun getAllStartUpAwarePids(): Set<Int> = mounter.getAllRecordedPids()

    fun getMountFailedPids(): Set<Int> = mounter.getMountFailedPids()

    fun getMountedPackages(): Set<String> = mounter.getMountedPackages()

    fun getTotalMountAttempts(): Int = mounter.getTotalAttempts()

    fun getMountFailureCount(): Int = mounter.getFailureCount()

    fun getMountedDirs(): List<String> = mounter.getMountedDirs()

    fun getLastMountFailure(): Mounter.MountFailure? = mounter.getLastMountFailure()

    fun resetAllMounts() {
        mounter.resetAllMounts()
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        mounter.onDestroy()
    }
}
