package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver
import me.gm.cleaner.runtime.server.observer.ObserverManager
import me.gm.cleaner.runtime.server.observer.StorageMountObserver
import me.gm.cleaner.runtime.server.consumer.FileSystemEventConsumer
import me.gm.cleaner.runtime.server.consumer.RedirectNoticeConsumer
import org.json.JSONObject

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

    // ── 事件消费者调度 ──
    private var consumerSchedulerRunning = false
    private val consumerPollIntervalMs = 2000L

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

        // 1. 启动 Hooks Gateway
        MediaProviderHookGateway.start(server)

        // 2. 启动所有观察者
        ObserverManager.startAllObservers(server)

        // 3. 注册包安装/卸载广播
        server.mPackageReceiver.registerPackageReceiver()

        // 4. 根据 auto_logging 偏好启动日志文件转储
        if (ServicePreferences.autoLogging) {
            server.mAutoLogging.registerBootShutdownReceiver(AutoLogging.MODE_CONTINUOUSLY)
        }

        // 5. 同步 Hook 配置（setCleanerServerBinder + 规则 + 挂载点 + 外部存储记录）
        MediaProviderHookGateway.syncAll(server)

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

        // 10. 绑定消费者 + 加载持久化游标 + 启动定时轮询
        RedirectNoticeConsumer.bind(server)
        FileSystemEventConsumer.loadCursor()
        RedirectNoticeConsumer.loadCursor()
        // 立即补偿消费积压事件
        FileSystemEventConsumer.pollAndConsume()
        RedirectNoticeConsumer.pollAndConsume()
        // 启动定时轮询（每 2s）
        startConsumerScheduler()

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
        val success = MediaProviderHookGateway.tryReconnect(server)
        if (success) {
            Log.i(TAG, "performHooksReconnect: SUCCESS — MEDIA_PROVIDER_JAVA_HOOK → HEALTHY, FUSE_NATIVE_HOOK → HEALTHY")
            hooksRetryCount = 0

            try {
                MediaProviderHookGateway.syncAll(server)
            } catch (e: RuntimeException) {
                Log.e(TAG, "performHooksReconnect: config sync failed", e)
            }

            // 重连后重新发布策略快照（确保 MediaProvider 端可读取最新规则）
            SnapshotPublisher.publishAll()

            // 重连后立即补偿消费积压事件
            FileSystemEventConsumer.pollAndConsume()
            RedirectNoticeConsumer.pollAndConsume()
            // 确保调度器运行
            startConsumerScheduler()
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

    // ── 事件消费者调度 ──

    /**
     * 启动事件消费者定时轮询。
     * 幂等：已在运行则跳过。
     */
    private fun startConsumerScheduler() {
        if (consumerSchedulerRunning) return
        consumerSchedulerRunning = true
        Log.i(TAG, "EventConsumerScheduler started (interval=${consumerPollIntervalMs}ms)")
        scheduleConsumerPoll()
    }

    private fun scheduleConsumerPoll() {
        if (!consumerSchedulerRunning) return
        server.handler.postDelayed({
            FileSystemEventConsumer.pollAndConsume()
            RedirectNoticeConsumer.pollAndConsume()
            scheduleConsumerPoll()
        }, consumerPollIntervalMs)
    }

    // ── 状态诊断 ──

    /**
     * 采集五层运行状态，返回 JSON 字符串。
     * 供 App UI 通过 AIDL 查询——替代混合的 pidFlags 和 serverException。
     */
    fun collectStatusJson(): String {
        val root = JSONObject()

        // VFS Layer
        val vfsObj = JSONObject()
        val procObs = ObserverManager.fastGetObserver(BaseProcessObserver::class.java)
        if (procObs != null) {
            vfsObj.put("state", "HEALTHY")
            vfsObj.put("started", true)
            vfsObj.put("mountedPackages", procObs.getMountedPackages().size)
            vfsObj.put("recordedPids", procObs.getAllStartUpAwarePids().size)
            vfsObj.put("mountFailedPids", procObs.getMountFailedPids().size)
        } else {
            vfsObj.put("state", "UNAVAILABLE")
            vfsObj.put("started", false)
        }
        root.put("vfs", vfsObj)

        // MediaProvider Java Hook Layer
        val hookObj = JSONObject()
        val hooksConnected = MediaProviderHookGateway.pingBinder()
        hookObj.put("state", if (hooksConnected) "HEALTHY" else "UNAVAILABLE")
        hookObj.put("binderConnected", hooksConnected)
        root.put("mediaProviderJavaHook", hookObj)

        // FUSE Native Hook Layer
        val nativeObj = JSONObject()
        val nativeGen = MediaProviderHookGateway.nativeMountPointsGeneration()
        nativeObj.put("state", if (nativeGen > 0) "HEALTHY" else "STALE")
        nativeObj.put("configuredMountPointsGeneration", nativeGen)
        root.put("fuseNativeHook", nativeObj)

        // DataBus Layer
        val busObj = JSONObject()
        val busInit = DataBus.ensureInitialized()
        val hasPolicy = DataBus.readSnapshot(DataBus.SNAPSHOT_REDIRECT_POLICY) != null
        val hasMountPoints = DataBus.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS) != null
        val busHealthy = busInit && hasPolicy && hasMountPoints
        busObj.put("state", if (busHealthy) "HEALTHY" else if (busInit) "DEGRADED" else "UNAVAILABLE")
        busObj.put("busRootExists", busInit)
        busObj.put("snapshotRedirectPolicy", if (hasPolicy) "exists" else "missing")
        busObj.put("snapshotConfiguredMountPoints", if (hasMountPoints) "exists" else "missing")
        root.put("dataBus", busObj)

        // ControlPlane
        val ctrlObj = JSONObject()
        ctrlObj.put("state", if (hooksConnected) "HEALTHY" else "DEGRADED")
        ctrlObj.put("appBinderRegistered", server.cleanerService != null)
        ctrlObj.put("hooksBridgeConnected", hooksConnected)
        root.put("controlPlane", ctrlObj)

        // Overall health
        val overallHealthy = vfsObj.optString("state") == "HEALTHY"
                && hooksConnected && busInit
        val overallDegraded = vfsObj.optString("state") == "HEALTHY" && busInit
        root.put("health", when {
            overallHealthy -> "HEALTHY"
            overallDegraded -> "DEGRADED"
            else -> "CRITICAL"
        })

        return root.toString(2)
    }
}
