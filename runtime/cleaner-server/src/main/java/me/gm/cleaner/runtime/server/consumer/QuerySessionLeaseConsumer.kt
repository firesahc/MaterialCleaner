package me.gm.cleaner.runtime.server.consumer

import android.util.Log
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
import org.json.JSONObject
import java.io.File

/**
 * Query session lease 消费器。
 *
 * MediaProvider Hook 进程只发布短期 lease，root server 在这里维护对应临时目录。
 * 这样 FUSE Java 热路径不依赖 Binder，同时保留 root 创建/清理目录的能力。
 */
object QuerySessionLeaseConsumer {
    private const val TAG = "QuerySessionLeaseConsumer"

    fun pollAndApply(): Int {
        val leases = DataBus.readLeaseFiles(DataBus.LEASE_QUERY_SESSIONS)
        if (leases.isEmpty()) return 0

        val now = System.currentTimeMillis()
        var applied = 0
        var expired = 0
        for (lease in leases) {
            try {
                val root = JSONObject(lease.content)
                val mountedPath = root.optString("mountedPath", "")
                val expiresAt = root.optLong("expiresAt", 0L)
                if (mountedPath.isBlank()) {
                    DataBus.deleteLeaseFile(DataBus.LEASE_QUERY_SESSIONS, lease.name)
                    continue
                }
                if (expiresAt <= now) {
                    rmdirSafe(mountedPath)
                    DataBus.deleteLeaseFile(DataBus.LEASE_QUERY_SESSIONS, lease.name)
                    expired++
                    continue
                }
                if (File(mountedPath).mkdirs() || File(mountedPath).isDirectory) {
                    applied++
                } else {
                    Log.w(TAG, "mkdirs failed for query session lease: $mountedPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply query session lease ${lease.name}", e)
                DataBus.deleteLeaseFile(DataBus.LEASE_QUERY_SESSIONS, lease.name)
            }
        }
        if (applied > 0 || expired > 0) {
            Log.d(TAG, "leases applied=$applied expired=$expired")
        }
        return applied
    }

    private fun rmdirSafe(dir: String) {
        val observer = ObserverManager.getObserver(BaseProcessObserver::class.java)
        val mountedDirs = observer?.getMountedDirs()?.toSet() ?: emptySet()
        rmdirRecursively(dir, mountedDirs)
    }

    private fun rmdirRecursively(dir: String?, exceptions: Set<String>) {
        if (dir == null || exceptions.contains(dir)) {
            return
        }
        val parent = File(dir).parent
        if (RuntimeFileUtils.rm_dir(dir) == 0) {
            rmdirRecursively(parent, exceptions)
        }
    }
}
