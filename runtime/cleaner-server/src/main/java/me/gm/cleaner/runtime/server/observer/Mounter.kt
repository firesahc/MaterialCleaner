package me.gm.cleaner.runtime.server.observer

import android.app.ActivityManager
import android.os.Handler
import android.os.HandlerThread
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.core.os.postDelayed
import api.SystemService
import com.google.common.collect.Multimaps
import com.google.common.collect.SetMultimap
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.core.storage.redirect.domain.MountRules
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
    private val mountFailedPids: MutableList<Int> = mutableListOf()
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
            return RuntimeFileUtils.bind_mount(
                pid, uid,
                !isFuseBpfEnabled && recordExternalAppSpecificStorage, false,
                emptyArray(), emptyArray()
            )
        }

        pidRecords.put(packageName, pid)
        if (!mkdirRecords.containsKey(packageName)) {
            val mkdirRecord = mutableSetOf<String>()
            getMkdirList(rules).forEach { record(mkdirRecord, it) }
            if (RuntimeFileUtils.auto_prepare_dirs(mkdirRecord.toTypedArray(), uid)) {
                mkdirRecords.putAll(packageName, mkdirRecord)
            }
        }
        val ret = RuntimeFileUtils.bind_mount(
            pid, uid,
            !isFuseBpfEnabled && recordExternalAppSpecificStorage, isFuseBpfEnabled,
            rules.sources.toTypedArray(), rules.targets.toTypedArray()
        )
        Log.i("MC_REDIRECT", "[Mounter] bindMount result=$ret pkg=$packageName pid=$pid " +
                "sources=${rules.sources} targets=${rules.targets}")
        if (ret) {
            mountFailedPids.remove(pid)
            mountRetryCount.remove(pid)
        } else {
            mountFailedPids.add(pid)
            failureCount.incrementAndGet()
            scheduleMountRetryLocked(packageName, pid, uid)
        }
        return ret
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
                bindMountLocked(packageName, pid, uid)
            }
        }
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
                removeMountDirsLocked(packageName, procInfo.uid)
            }
        }
        for (procInfo in procList) {
            procInfo.pkgList.forEach { packageName ->
                bindMountLocked(packageName, procInfo.pid, procInfo.uid)
            }
        }
    }

    fun notifyProcessKilled(packageName: String, pid: Int, uid: Int) {
        handler.post {
            synchronized(lock) {
                notifyProcessKilledLocked(packageName, pid, uid)
            }
        }
    }

    private fun notifyProcessKilledLocked(packageName: String, pid: Int, uid: Int) {
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
            removeMountDirsLocked(packageName, uid)
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
                val uid = SystemService.getApplicationInfo(packageName, 0, 0)?.uid ?: -1
                removeMountDirsLocked(packageName, uid)
            }
        }
    }

    private fun removeMountDirsLocked(packageName: String, uid: Int) {
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

    fun getTotalAttempts(): Int = totalAttempts.get()

    fun getFailureCount(): Int = failureCount.get()

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
}
