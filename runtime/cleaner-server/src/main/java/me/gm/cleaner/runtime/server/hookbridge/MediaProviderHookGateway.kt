package me.gm.cleaner.runtime.server.hookbridge

import android.os.RemoteException
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import org.json.JSONObject
import java.util.function.Consumer
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

/**
 * Server-side gateway to the MediaProvider Java Hook runtime.
 *
 * This is a logical boundary before the runtime modules are physically split.
 * Server components should depend on this gateway instead of depending on
 * CleanerHooksClient or xposed implementation classes directly.
 */
object MediaProviderHookGateway {

    private const val TAG = "MediaProviderHookGateway"

    /** configured_mount_points snapshot generation 缓存（避免每 2s 健康检查读 DataBus） */
    @Volatile
    private var cachedMountPointsGeneration: Long = 0L
    /** 挂载点快照 publisherEpoch 缓存（与 generation 同批更新） */
    @Volatile
    private var cachedMountPointsEpoch: String = ""
    /** 上次读取 DataBus snapshot 时的 signal 时间戳（跳过未变更的信号） */
    @Volatile
    private var lastMountSignalTimestamp: Long = 0L

    fun start(server: CleanerServer) {
        CleanerHooksClient.onStart(server)
    }

    fun tryReconnect(server: CleanerServer): Boolean =
        // 协调器已有有界退避与冷却，不能再由懒调用节流消耗恢复次数。
        CleanerHooksClient.tryReconnect(server, respectThrottle = false)

    @JvmStatic
    fun whileAlive(action: Consumer<ICleanerHooksService>) {
        CleanerHooksClient.whileAlive(action)
    }

    @JvmStatic
    fun pingBinder(): Boolean =
        CleanerHooksClient.pingBinder()

    fun isMediaProviderHookConnected(): Boolean =
        runCatching {
            var connected = false
            whileAlive { service ->
                connected = service.isMediaProviderHookConnected
            }
            connected
        }.getOrDefault(false)

    fun registerCleanerServerBinder(server: CleanerServer) {
        whileAlive { service ->
            try {
                service.setCleanerServerBinder(server.mCleanerServerCallback)
            } catch (e: RemoteException) {
                android.util.Log.w(TAG, "registerCleanerServerBinder failed", e)
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "registerCleanerServerBinder failed", e)
            }
        }
    }

    /** 连接回调由 CleanerHooksClient 建连时注入；此处只刷新策略快照。 */
    fun registerAndRefreshFromDataBus(@Suppress("UNUSED_PARAMETER") server: CleanerServer) {
        refreshPolicyFromDataBus()
    }

    @JvmStatic
    fun refreshPolicyFromDataBus() {
        whileAlive { service ->
            try {
                service.refreshPolicyFromDataBus()
            } catch (e: RemoteException) {
                android.util.Log.w(TAG, "refreshPolicyFromDataBus Binder transaction failed", e)
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "refreshPolicyFromDataBus failed", e)
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

    fun nativeHookStatusJson(): String =
        runCatching {
            var status = ""
            whileAlive { service ->
                status = service.nativeHookStatusJson
            }
            status
        }.getOrDefault("")

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
        return readSnapshotIdentity().first
    }

    /**
     * 获取挂载点快照的 publisherEpoch。
     * 与 generation 读取共用同一批 signal 缓存，避免每 2s 健康检查重复读 DataBus。
     */
    fun mountPointsSnapshotEpoch(): String {
        return readSnapshotIdentity().second
    }

    private fun readSnapshotIdentity(): Pair<Long, String> {
        val signalTime = DataBus.getSignalTimestamp(DataBusProtocol.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
        if (signalTime <= lastMountSignalTimestamp && lastMountSignalTimestamp > 0) {
            return cachedMountPointsGeneration to cachedMountPointsEpoch
        }
        // 信号变更，重新读取 DataBus
        val (gen, epoch) = DataBus.readSnapshot(DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
            ?.let { json ->
                runCatching {
                    val root = JSONObject(json)
                    root.optLong("generation", 0L) to root.optString("publisherEpoch", "")
                }.getOrDefault(0L to "")
            } ?: (0L to "")
        cachedMountPointsGeneration = gen
        cachedMountPointsEpoch = epoch
        lastMountSignalTimestamp = signalTime
        return gen to epoch
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
