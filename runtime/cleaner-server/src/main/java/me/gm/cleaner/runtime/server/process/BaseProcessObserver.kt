package me.gm.cleaner.runtime.server.process

import android.app.ActivityManager
import android.util.Log
import androidx.annotation.CallSuper
import api.SystemService
import me.gm.cleaner.core.common.RuntimeFileUtils.isIsolatedUid
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.runtime.server.VfsRuntimePolicy
import me.gm.cleaner.runtime.server.lifecycle.BaseObserver
import me.gm.cleaner.runtime.server.vfs.mount.Mounter
import java.util.concurrent.CopyOnWriteArraySet

abstract class BaseProcessObserver : BaseObserver() {
    protected val mounter: Mounter = Mounter()

    val mountedStorage: CopyOnWriteArraySet<Int> = CopyOnWriteArraySet()

    protected fun isMounterActiveForUser(userId: Int): Boolean =
        mountedStorage.contains(userId)

    protected fun isMounterActiveForUid(uid: Int): Boolean = isMounterActiveForUser(uid.toUserId())

    private fun getRunningAppProcesses(packageNames: Array<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            !procInfo.uid.isIsolatedUid() &&
                isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountForPackages(packageNames: Array<String>) {
        mounter.forProcList(getRunningAppProcesses(packageNames), false, true)
    }

    private fun getRunningAppProcesses(packageNames: Iterable<String>): List<ActivityManager.RunningAppProcessInfo> =
        SystemService.getRunningAppProcessesNoThrow().filter { procInfo ->
            !procInfo.uid.isIsolatedUid() &&
                isMounterActiveForUid(procInfo.uid) && procInfo.pkgList.any { packageNames.contains(it) }
        }

    fun remountAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
            false,
            true,
        )
    }

    fun remountAllWithCheck() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
            true,
            true,
        )
    }

    fun recordAll() {
        mounter.forProcListAsync(
            getRunningAppProcesses(VfsRuntimePolicy.getStorageRedirectPackages()),
            true,
            false,
        )
    }

    fun isFuseBpfEnabled(): Boolean = mounter.isFuseBpfEnabled

    fun getStartUpAwarePids(packageName: String): Set<Int> = mounter.getRecordedPids(packageName)

    fun getAllStartUpAwarePids(): Set<Int> = mounter.getAllRecordedPids()

    fun getMountFailedPids(): Set<Int> = mounter.getMountFailedPids()

    fun getPreparedPackages(): Set<String> = mounter.getPreparedPackages()

    fun getTotalMountAttempts(): Int = mounter.getTotalAttempts()

    fun getMountFailureCount(): Int = mounter.getFailureCount()

    fun getGateRefusalCount(): Int = mounter.getGateRefusalCount()

    /**
     * 心跳重收敛：取出到期失败包，经正常 remount 链（新鲜 procList +
     * 选择策略 + 隔离过滤 + 身份门）重投，不直调单 pid 挂载。
     */
    fun requeueFailedMounts() {
        val due = mounter.consumeDueFailedPackages()
        if (due.isEmpty()) return
        Log.i("MC_REDIRECT", "[Observer] heartbeat requeue packages=$due")
        mounter.forProcListAsync(
            getRunningAppProcesses(due),
            false,
            true,
        )
    }

    fun getMountedDirs(): List<String> = mounter.getMountedDirs()

    fun getLastMountFailure(): Mounter.MountFailure? = mounter.getLastMountFailure()

    /** 最近一次挂载失败对应的统一错误码（ErrorCodes 注册表），无失败时为空串。 */
    fun getLastMountErrorCode(): String = mounter.getLastMountErrorCode()

    fun resetAllMounts() {
        mounter.resetAllMounts()
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        mounter.onDestroy()
    }
}
