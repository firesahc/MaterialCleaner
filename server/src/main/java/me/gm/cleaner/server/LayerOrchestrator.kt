package me.gm.cleaner.server

import android.os.RemoteException
import android.util.Log
import me.gm.cleaner.client.CleanerHooksClient
import me.gm.cleaner.client.CleanerHooksClient.syncMountPoint
import me.gm.cleaner.client.CleanerHooksClient.syncReadOnlyPaths
import me.gm.cleaner.client.CleanerHooksClient.syncRecordExternalAppSpecificStorage
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.server.observer.ObserverManager
import me.gm.cleaner.server.observer.StorageMountObserver

/**
 * 三层编排器。
 *
 * 负责接管 [CleanerServer.onStorageManagerServiceReady] 中的隐式编排逻辑。
 * 当前阶段仅做空壳迁移——保持原执行顺序和原行为，后续计划逐步加入：
 * - Layer 状态追踪
 * - Hook 死亡隔离恢复
 * - 分层降级策略
 *
 * @param server CleanerServer 实例，提供所有被编排组件的引用
 */
class LayerOrchestrator(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "LayerOrchestrator"
    }

    // ── Hook 重连状态 ──
    private var hooksRetryCount = 0
    private var hooksReconnectScheduled = false
    private val hooksRetryDelays = longArrayOf(1000L, 2000L, 5000L, 10000L, 30000L)

    /**
     * 执行服务器启动编排。
     *
     * 当前保持与旧 onStorageManagerServiceReady() 完全一致的顺序和行为：
     * 1. CleanerHooksClient.onStart()
     * 2. ObserverManager.startAllObservers()
     * 3. PackageReceiver.registerPackageReceiver()
     * 4. AutoLogging（条件注册）
     * 5. whileAlive { setCleanerServerBinder + syncReadOnly + syncMountPoint + syncRecordExternal }
     * 6. StorageMountObserver 配置与注册
     * 7. BinderSender.register()
     * 8. sendBinderToManger()
     */
    fun initialize() {
        Log.i(TAG, "initialize start")

        // 1. 启动 Hooks 客户端
        CleanerHooksClient.onStart(server)

        // 2. 启动所有观察者
        ObserverManager.startAllObservers(server)

        // 3. 注册包安装/卸载广播
        server.mPackageReceiver.registerPackageReceiver()

        // 4. 根据 auto_logging 偏好启动日志文件转储
        if (ServicePreferences.autoLogging) {
            server.mAutoLogging.registerBootShutdownReceiver(AutoLogging.MODE_CONTINUOUSLY)
        }

        // 5. 同步 Hook 配置（setCleanerServerBinder + 规则 + 挂载点 + 外部存储记录）
        CleanerHooksClient.whileAlive { service ->
            try {
                service.setCleanerServerBinder(server.mCleanerServerCallback)
                service.syncReadOnlyPaths()
                service.syncMountPoint()
                service.syncRecordExternalAppSpecificStorage()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }

        // 6. 配置并注册 StorageMountObserver
        val mountObserver = ObserverManager.getObserver(StorageMountObserver::class.java)
        if (mountObserver != null) {
            mountObserver.setCleanerServer(server)
            mountObserver.registerListener(mountObserver)
        }

        // 7. 注册 Binder 服务
        BinderSender.register(server.cleanerService)

        // 8. 向所有用户发送 Binder
        server.sendBinderToManger(server.cleanerService)

        // 9. 发布初始策略快照到 DataBus
        SnapshotPublisher.publishAll()

        Log.i(TAG, "initialize done")
    }

    /**
     * 处理 Hooks Binder 死亡事件。
     *
     * 不停止 VFS observer、不重启 server、不全量恢复。
     * 仅清空死连接、标记兼容层不可用、开始退避重试。
     */
    fun onHooksBinderDied() {
        Log.w(TAG, "onHooksBinderDied: MEDIA_PROVIDER_JAVA_HOOK → UNAVAILABLE, FUSE_NATIVE_HOOK → STALE")
        scheduleHooksReconnect()
    }

    /**
     * 按退避策略调度 Hooks 重连。
     * 防重入：已在调度中则跳过。
     */
    private fun scheduleHooksReconnect() {
        if (hooksReconnectScheduled) return
        hooksReconnectScheduled = true

        val delay = hooksRetryDelays[hooksRetryCount.coerceAtMost(hooksRetryDelays.size - 1)]
        Log.i(TAG, "scheduleHooksReconnect: attempt #${hooksRetryCount + 1}, delay=${delay}ms")

        server.handler.postDelayed({
            hooksReconnectScheduled = false
            performHooksReconnect()
        }, delay)
    }

    /**
     * 执行实际的 Hooks 重连操作。
     * 成功后同步所有配置（callback + readOnly + mountPoint + recordExternal）。
     * 失败后递增重试计数并继续退避。
     */
    private fun performHooksReconnect() {
        Log.i(TAG, "performHooksReconnect: attempting...")
        val success = CleanerHooksClient.tryReconnect(server)
        if (success) {
            Log.i(TAG, "performHooksReconnect: SUCCESS — MEDIA_PROVIDER_JAVA_HOOK → HEALTHY, FUSE_NATIVE_HOOK → HEALTHY")
            hooksRetryCount = 0

            // 重连成功后同步所有配置
            CleanerHooksClient.whileAlive { service ->
                try {
                    service.setCleanerServerBinder(server.mCleanerServerCallback)
                    service.syncReadOnlyPaths()
                    service.syncMountPoint()
                    service.syncRecordExternalAppSpecificStorage()
                } catch (e: RemoteException) {
                    Log.e(TAG, "performHooksReconnect: config sync failed", e)
                }
            }

            // 重连后重新发布策略快照（确保 MediaProvider 端可读取最新规则）
            SnapshotPublisher.publishAll()
        } else {
            hooksRetryCount++
            Log.w(TAG, "performHooksReconnect: FAILED (attempt $hooksRetryCount)")
            // 最多重试 delays.size * 2 轮（防止无限重试）
            if (hooksRetryCount < hooksRetryDelays.size * 2) {
                scheduleHooksReconnect()
            } else {
                Log.e(TAG, "performHooksReconnect: max retries (${hooksRetryDelays.size * 2}) exceeded, giving up")
            }
        }
    }
}
