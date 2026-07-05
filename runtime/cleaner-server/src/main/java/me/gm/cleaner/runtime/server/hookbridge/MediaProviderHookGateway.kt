package me.gm.cleaner.runtime.server.hookbridge

import android.os.RemoteException
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import org.json.JSONObject
import java.util.function.Consumer

/**
 * Server-side gateway to the MediaProvider Java Hook runtime.
 *
 * This is a logical boundary before the runtime modules are physically split.
 * Server components should depend on this gateway instead of depending on
 * CleanerHooksClient or xposed implementation classes directly.
 */
object MediaProviderHookGateway {

    /** configured_mount_points snapshot generation 缓存（避免每 2s 健康检查读 DataBus） */
    @Volatile
    private var cachedMountPointsGeneration: Long = 0L
    /** 上次读取 DataBus snapshot 时的 signal 时间戳（跳过未变更的信号） */
    @Volatile
    private var lastMountSignalTimestamp: Long = 0L

    fun start(server: CleanerServer) {
        CleanerHooksClient.onStart(server)
    }

    fun tryReconnect(server: CleanerServer): Boolean =
        CleanerHooksClient.tryReconnect(server)

    @JvmStatic
    fun whileAlive(action: Consumer<ICleanerHooksService>) {
        CleanerHooksClient.whileAlive(action)
    }

    @JvmStatic
    fun pingBinder(): Boolean =
        CleanerHooksClient.pingBinder()

    fun registerCleanerServerBinder(server: CleanerServer) {
        whileAlive { service ->
            service.setCleanerServerBinder(server.mCleanerServerCallback)
        }
    }

    fun registerAndRefreshFromDataBus(server: CleanerServer) {
        whileAlive { service ->
            try {
                service.setCleanerServerBinder(server.mCleanerServerCallback)
                service.refreshPolicyFromDataBus()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    @JvmStatic
    fun refreshPolicyFromDataBus() {
        whileAlive { service ->
            try {
                service.refreshPolicyFromDataBus()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    @JvmStatic
    fun reconnectIfNeeded() {
        whileAlive {
            // Trigger CleanerHooksClient's lazy reconnect path.
        }
    }

    fun nativeMountPointsGeneration(): Long =
        runCatching {
            var generation = 0L
            whileAlive { service ->
                generation = service.nativeMountPointsGeneration
            }
            generation
        }.getOrDefault(0L)

    /**
     * 获取 configured_mount_points snapshot generation。
     *
     * 使用两级缓存减少 DataBus 文件 I/O：
     * 1. 先检查 signal 是否变更（仅 stat 代价）
     * 2. 信号未变 → 返回缓存值
     * 3. 信号变更 → 重新读取 DataBus 并更新缓存
     *
     * 此方法每 2s 被健康检查调用一次，缓存将 DataBus 读从每轮减少到仅在发布时。
     */
    fun configuredMountPointsSnapshotGeneration(): Long {
        val signalTime = DataBus.getSignalTimestamp(DataBus.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        if (signalTime <= lastMountSignalTimestamp && lastMountSignalTimestamp > 0) {
            return cachedMountPointsGeneration
        }
        // 信号变更，重新读取 DataBus
        val gen = DataBus.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
            ?.let { json ->
                runCatching { JSONObject(json).optLong("generation", 0L) }.getOrDefault(0L)
            } ?: 0L
        cachedMountPointsGeneration = gen
        lastMountSignalTimestamp = signalTime
        return gen
    }

    /**
     * Binder 进程死亡时重置 native 状态缓存。
     *
     * 使后续的恢复路径从零开始重新建立 native 挂载点，
     * 避免残留的 DataBus 缓存（snapshot generation）在 Binder 死后
     * 导致 collectStatus 产生误导性的 STALE 状态评估。
     */
    fun resetNativeStateForReconnect() {
        cachedMountPointsGeneration = 0L
        lastMountSignalTimestamp = 0L
    }

    @JvmStatic
    fun onDestroy() {
        CleanerHooksClient.onDestroy()
    }
}
