package me.gm.cleaner.runtime.server

import android.os.Binder
import android.util.Log
import api.SystemService
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.observer.ObserverManager
import me.gm.cleaner.runtime.server.observer.StorageMountObserver
import me.gm.cleaner.runtime.server.consumer.FileSystemEventConsumer
import me.gm.cleaner.runtime.server.consumer.RedirectNoticeConsumer
import me.gm.cleaner.runtime.server.orchestrator.LayerId
import me.gm.cleaner.runtime.server.orchestrator.LayerReport
import me.gm.cleaner.runtime.server.orchestrator.LayerState
import me.gm.cleaner.runtime.server.orchestrator.OrchestratedStatus
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import me.gm.cleaner.model.LayerStatus as IpcLayerStatus
import me.gm.cleaner.model.OrchestratedStatus as IpcOrchestratedStatus

/**
 * 三层编排器。
 *
 * 负责接管 [CleanerServer.onStorageManagerServiceReady] 中的隐式编排逻辑。
 * 负责三层拦截能力的生命周期编排：
 * - 数据面优先发布规则快照与事件队列
 * - 控制面仅承载注册、刷新命令和兼容 fallback
 * - Hook 死亡隔离恢复，不重启 VFS 主机制
 *
 * @param server CleanerServer 实例，提供所有被编排组件的引用
 */
class LayerOrchestrator(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "LayerOrchestrator"
        private const val MEDIA_PROVIDER_AUTHORITY = "media"
        private const val MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD = 3
        private const val MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS = 60_000L
        private const val MEDIA_PROVIDER_WAKE_DELAY_MS = 1_000L
        private const val NATIVE_HOOK_STATUS_MAX_AGE_MS = 15_000L
        private val MEDIA_PROVIDER_PACKAGE_CANDIDATES = arrayOf(
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.media",
        )
    }

    // ── Hook 重连状态 ──
    private var hooksRetryCount = 0
    private var hooksReconnectScheduled = false
    private val hooksRetryDelays = longArrayOf(1000L, 2000L, 5000L, 10000L, 30000L)

    // ── 状态诊断代数（每次 collectStatus 调用递增） ──
    private val statusGeneration = AtomicLong(0)

    // ── 各层首次启动时间追踪（用于 LayerReport.lastStartedAt 字段） ──
    private val layerStartedAt = java.util.concurrent.ConcurrentHashMap<LayerId, Long>()

    // ── 事件消费者调度 ──
    private var consumerSchedulerRunning = false
    private val consumerPollIntervalMs = 2000L

    /**
     * 执行服务器启动编排。
     *
     * 启动顺序：
     * 1. CleanerHooksClient.onStart()
     * 2. ObserverManager.startAllObservers()
     * 3. PackageReceiver.registerPackageReceiver()
     * 4. AutoLogging（条件注册）
     * 5. 发布 DataBus 初始快照
     * 6. whileAlive { setCleanerServerBinder + refreshPolicyFromDataBus }
     *    DataBus 不可用时才执行 legacy Binder 配置同步。
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

        // 5. 发布初始策略快照到 DataBus（含 platform_capabilities）
        val dataBusReady = SnapshotPublisher.publishAll()

        // 6. 注册 Hook 回调并请求 Hook 侧从 DataBus 刷新策略。
        // 不再保留 Binder legacy fallback——如果 DataBus 在初始化时不可用，
        // 原因通常是文件系统未就绪，legacy Binder 同步也无法绕过此限制。
        // 当 DataBus 可用后，Hook 重连路径（performHooksReconnect）会重新发布并刷新。
        MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
        if (!dataBusReady) {
            Log.w(TAG, "DataBus unavailable during initialize, snapshots will be published on reconnect")
        }

        // 7. 配置并注册 StorageMountObserver
        val mountObserver = ObserverManager.getObserver(StorageMountObserver::class.java)
        if (mountObserver != null) {
            mountObserver.setCleanerServer(server)
            mountObserver.registerListener(mountObserver)
        }

        // 8. 注册 Binder 服务
        BinderSender.register(server.cleanerService)

        // 9. 向所有用户发送 Binder
        server.sendBinderToManger(server.cleanerService)

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
     * binderDied 回调意味着 MediaProvider 进程死亡。
     * Java Hook 和 FUSE Native Hook（共享同一进程）都已停止。
     *
     * 不停止 VFS observer、不重启 server、不全量恢复。
     * 仅清空死连接、标记两层不可用、重置 native 状态缓存、开始退避重试。
     */
    fun onHooksBinderDied() {
        Log.w(TAG, "onHooksBinderDied: MEDIA_PROVIDER_JAVA_HOOK → UNAVAILABLE, " +
                "FUSE_NATIVE_HOOK → UNAVAILABLE (binderDied = process death)")
        MediaProviderHookGateway.resetNativeStateForReconnect()
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
     * 执行三层恢复编排。
     *
     * Phase 1: Binder 重连 ← 决定恢复是否能继续
     * Phase 2: DataBus 快照刷新 ← 独立于 Binder
     * Phase 3: 通知 Hook 侧从 DataBus 刷新 ← 依赖 Binder
     *
     * 恢复路径不替代 collectStatus 评估各层状态。
     * HEALTHY/STALE/UNAVAILABLE 由 collectStatus 独立判断。
     */
    private fun performHooksReconnect() {
        Log.i(TAG, "performHooksReconnect: attempting...")

        // ── Phase 1: Binder 重连 ──
        val hooksReconnected = MediaProviderHookGateway.tryReconnect(server)
        if (hooksReconnected) {
            hooksRetryCount = 0
            Log.i(TAG, "performHooksReconnect: Phase 1 OK — Binder reconnected")
        } else {
            hooksRetryCount++
            Log.w(TAG, "performHooksReconnect: Phase 1 FAILED (attempt $hooksRetryCount)")
            if (hooksRetryCount < hooksRetryDelays.size * 2) {
                scheduleHooksReconnect()
            } else {
                Log.e(TAG, "performHooksReconnect: max retries (${hooksRetryDelays.size * 2}) exceeded, giving up")
            }
            return
        }

        // ── Phase 2: DataBus 快照刷新（独立于 Binder） ──
        val dataBusReady = SnapshotPublisher.publishAll()
        if (!dataBusReady) {
            Log.w(TAG, "performHooksReconnect: Phase 2 — DataBus snapshots not published")
        }

        // ── Phase 3: 通知 Hook 侧从 DataBus 刷新（依赖 Binder） ──
        try {
            MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
        } catch (e: RuntimeException) {
            Log.e(TAG, "performHooksReconnect: Phase 3 — Hook refresh failed", e)
            // Binder 已建立，DataBus 快照已发布。
            // Hook 侧的下一次 isStale 检查会通过 signal 检测并自动刷新。
        }

        // ── 补偿消费 ──
        FileSystemEventConsumer.pollAndConsume()
        RedirectNoticeConsumer.pollAndConsume()
        startConsumerScheduler()

        Log.i(TAG, "performHooksReconnect: done")
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
            // 独立于事件消费心跳，执行 Native Hook 健康检查
            nativeHookHealthCheck()
            // 定期发布状态快照到 DataBus（供 App UI 直接读取）
            publishStatusSnapshot()
            scheduleConsumerPoll()
        }, consumerPollIntervalMs)
    }

    // ── FUSE Native Hook 独立健康检查 ──

    /**
     * 检查 FUSE Native Hook 层是否同步。
     * 如果 native 层的 mount points generation 落后于 DataBus snapshot generation，
     * 则尝试触发刷新——独立于 MediaProvider Java Hook Binder 状态。
     *
     * 此方法仅做健康检查，不会重置 Binder 连接或触发全量重连。
     *
     * 注意：当前仍依赖 Binder 查询 native 状态（nativeMountPointsGeneration 等方法
     * 通过 MediaProviderHooksService Binder 调用）。这是物理约束——native 层尚不支持
     * 独立写入 DataBus 状态报告。演进方向：native 层通过 DataBus 直接报告自身 generation，
     * 届时可消除此 Binder 依赖，实现 FUSE Native Hook 完全独立生命周期。
     */
    private var lastNativeHookCheckGeneration: Long = 0L
    private var consecutiveMediaProviderHookMissing: Int = 0
    private var lastMediaProviderRecoveryAt: Long = 0L
    private var mediaProviderWakeScheduled: Boolean = false

    private fun nativeHookHealthCheck() {
        // 如果 Binder 不可用，无法查询 native 状态，跳过
        if (!MediaProviderHookGateway.pingBinder()) return

        if (recoverMediaProviderHookRegistrationIfNeeded()) return

        val nativeGen = MediaProviderHookGateway.nativeMountPointsGeneration()
        val snapshotGen = MediaProviderHookGateway.configuredMountPointsSnapshotGeneration()

        // 如果 generation 相同或上次检查过的结果相同，跳过
        if (nativeGen >= snapshotGen && snapshotGen > 0) {
            lastNativeHookCheckGeneration = nativeGen
            return
        }
        if (nativeGen == lastNativeHookCheckGeneration && nativeGen > 0) {
            // 上次已经检查过并尝试触发了刷新，等待下一轮
            return
        }
        lastNativeHookCheckGeneration = nativeGen

        Log.w(TAG, "nativeHookHealthCheck: nativeGen=$nativeGen < snapshotGen=$snapshotGen, triggering refresh")
        // 通过 DataBus 刷新路径触发 native 侧重新加载挂载点
        MediaProviderHookGateway.refreshPolicyFromDataBus()
    }

    /**
     * 恢复“App Bridge 可达，但 MediaProvider Hook 未重新注册”的半断链状态。
     *
     * 典型触发场景：用户停止服务/杀掉 App 进程后重新启动服务。HooksBridgeProvider
     * 运行在 App 进程内，静态的 MediaProvider Binder 会随 App 进程消失；但
     * MediaProvider 进程可能仍然存活，Xposed 入口不会重新执行，因此不会主动把
     * IMediaProviderHooksService 重新注册到新的 App Bridge。
     *
     * 此处采用受控恢复：连续多轮确认缺失后，重启 MediaProvider 包并唤醒 media provider，
     * 让 Xposed 入口重新执行注册。带冷却，避免服务启动窗口里反复 force-stop。
     */
    private fun recoverMediaProviderHookRegistrationIfNeeded(): Boolean {
        if (MediaProviderHookGateway.isMediaProviderHookConnected()) {
            if (consecutiveMediaProviderHookMissing > 0) {
                Log.i(TAG, "MediaProvider hook reconnected after " +
                        "$consecutiveMediaProviderHookMissing missing checks")
            }
            consecutiveMediaProviderHookMissing = 0
            return false
        }

        consecutiveMediaProviderHookMissing++
        if (consecutiveMediaProviderHookMissing < MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD) {
            Log.w(TAG, "MediaProvider hook missing while bridge is alive: " +
                    "check=$consecutiveMediaProviderHookMissing/" +
                    MEDIA_PROVIDER_HOOK_MISSING_THRESHOLD)
            return false
        }

        val now = System.currentTimeMillis()
        val sinceLastRecovery = now - lastMediaProviderRecoveryAt
        if (sinceLastRecovery < MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS) {
            Log.w(TAG, "MediaProvider hook still missing, recovery cooldown active: " +
                    "${MEDIA_PROVIDER_RECOVERY_COOLDOWN_MS - sinceLastRecovery}ms remaining")
            return true
        }

        lastMediaProviderRecoveryAt = now
        consecutiveMediaProviderHookMissing = 0
        MediaProviderHookGateway.resetNativeStateForReconnect()

        val stoppedPackages = forceStopMediaProviderPackages()
        if (stoppedPackages.isEmpty()) {
            Log.w(TAG, "MediaProvider hook recovery requested, but no MediaProvider package was found")
        } else {
            Log.w(TAG, "MediaProvider hook recovery: force-stopped ${stoppedPackages.joinToString()}")
        }
        scheduleMediaProviderWake()
        return true
    }

    private fun forceStopMediaProviderPackages(): Set<String> {
        val userIds = SystemService.getUserIdsNoThrow()
        val packages = linkedSetOf<String>()

        for (packageName in MEDIA_PROVIDER_PACKAGE_CANDIDATES) {
            if (userIds.any { userId ->
                    SystemService.getPackageInfoNoThrow(packageName, 0, userId) != null
                }) {
                packages += packageName
            }
        }

        for (userId in userIds) {
            for (packageName in packages) {
                runCatching {
                    SystemService.forceStopPackageNoThrow(packageName, userId)
                }.onFailure {
                    Log.w(TAG, "force-stop MediaProvider failed: package=$packageName user=$userId", it)
                }
            }
        }
        return packages
    }

    private fun scheduleMediaProviderWake() {
        if (mediaProviderWakeScheduled) return
        mediaProviderWakeScheduled = true
        server.handler.postDelayed({
            mediaProviderWakeScheduled = false
            wakeMediaProvider()
            if (MediaProviderHookGateway.pingBinder() &&
                    MediaProviderHookGateway.isMediaProviderHookConnected()) {
                SnapshotPublisher.publishAll()
                runCatching {
                    MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
                }.onFailure {
                    Log.w(TAG, "refresh MediaProvider hook after wake failed", it)
                }
            }
        }, MEDIA_PROVIDER_WAKE_DELAY_MS)
    }

    private fun wakeMediaProvider() {
        for (userId in SystemService.getUserIdsNoThrow()) {
            val token = Binder()
            var acquired = false
            try {
                val provider = SystemService.getContentProviderExternal(
                    MEDIA_PROVIDER_AUTHORITY,
                    userId,
                    token,
                    TAG,
                )
                acquired = provider != null
                Log.i(TAG, "wakeMediaProvider: user=$userId acquired=$acquired")
            } catch (tr: Throwable) {
                Log.w(TAG, "wakeMediaProvider failed for user=$userId", tr)
            } finally {
                if (acquired) {
                    runCatching {
                        SystemService.removeContentProviderExternal(MEDIA_PROVIDER_AUTHORITY, token)
                    }.onFailure {
                        Log.w(TAG, "removeContentProviderExternal failed for user=$userId", it)
                    }
                }
            }
        }
    }

    // ── 状态诊断 ──

    /**
     * 采集五层运行状态，返回 JSON 字符串。
     * 供 App UI 通过 AIDL 查询——替代混合的 pidFlags 和 serverException。
     */
    fun collectStatusJson(): String {
        val status = collectStatus()
        val root = JSONObject()
        root.put("vfs", status.vfs.toJson())
        root.put("mediaProviderJavaHook", status.mediaProviderJavaHook.toJson())
        root.put("fuseNativeHook", status.fuseNativeHook.toJson())
        root.put("dataBus", status.dataBus.toJson())
        root.put("controlPlane", status.controlPlane.toJson())
        root.put("health", status.health.name)
        return root.toString(2)
    }

    fun collectStatusForIpc(): IpcOrchestratedStatus {
        val status = collectStatus()
        return IpcOrchestratedStatus(
            status.health.name,
            status.vfs.toIpc(),
            status.mediaProviderJavaHook.toIpc(),
            status.fuseNativeHook.toIpc(),
            status.dataBus.toIpc(),
            status.controlPlane.toIpc(),
        )
    }

    private fun collectStatus(): OrchestratedStatus {
        val now = System.currentTimeMillis()
        val gen = statusGeneration.incrementAndGet()
        val vfsReport = server.vfsLayerController.collectReport(gen, now)

        // MediaProvider Java Hook Layer
        val hooksBridgeConnected = MediaProviderHookGateway.pingBinder()
        val mediaProviderHookConnected = if (hooksBridgeConnected) {
            MediaProviderHookGateway.isMediaProviderHookConnected()
        } else {
            false
        }
        val hookReport = LayerReport(
            id = LayerId.MEDIA_PROVIDER_JAVA_HOOK,
            state = when {
                mediaProviderHookConnected -> LayerState.HEALTHY
                hooksBridgeConnected -> LayerState.UNAVAILABLE
                else -> LayerState.UNAVAILABLE
            },
            generation = gen,
            lastHeartbeatAt = if (mediaProviderHookConnected) now else 0L,
            lastErrorAt = if (mediaProviderHookConnected) 0L else now,
            lastError = when {
                mediaProviderHookConnected -> null
                hooksBridgeConnected -> "MediaProvider Hook unavailable"
                else -> "Hook bridge Binder unavailable"
            },
            metrics = mapOf(
                "binderConnected" to hooksBridgeConnected.toString(),
                "mediaProviderHookConnected" to mediaProviderHookConnected.toString(),
            ),
        )

        // FUSE Native Hook Layer
        //
        // 目前 nativeGen 只能通过 Binder 查询。当 Binder 断开时 nativeGen=0，
        // 但 DataBus snapshotGen 仍有残留缓存值（快照在 tmpfs 上，不依赖 Hook 进程存活）。
        // 这导致状态机无法区分 "native 进程死"（需全量恢复）和 "native 进程活但 Binder 暂时不可用"（仅需重连）。
        //
        // 演进方向：native 层通过 DataBus 心跳信号独立报告存活状态，
        // 届时可消除此盲区。
        // 当前保守策略：snapshotGen>0 时报告 STALE（比误报 HEALTHY 安全）。
        val nativeStatusFromDataBus = readNativeHookStatusFromDataBus(now)
        val nativeStatus = nativeStatusFromDataBus ?: if (mediaProviderHookConnected) {
            parseNativeHookStatus(
                MediaProviderHookGateway.nativeHookStatusJson(),
                source = "binder",
            )
        } else {
            NativeHookRuntimeStatus(
                mediaProviderHookLoaded = false,
                mountPointsGeneration = 0L,
                lastError = "MediaProvider Hook unavailable",
                statusSource = "unavailable",
            )
        }
        val nativeStatusAvailable = nativeStatusFromDataBus != null || mediaProviderHookConnected
        val nativeGen = if (nativeStatus.mountPointsGeneration > 0) {
            nativeStatus.mountPointsGeneration
        } else if (mediaProviderHookConnected) {
            MediaProviderHookGateway.nativeMountPointsGeneration()
        } else {
            0L
        }
        val snapshotGen = MediaProviderHookGateway.configuredMountPointsSnapshotGeneration()
        val nativeState = when {
            !nativeStatusAvailable -> LayerState.UNAVAILABLE
            !nativeStatus.inlineLibraryLoaded -> LayerState.DEGRADED
            nativeStatus.nativeLibraryKnownUnavailable -> LayerState.DISABLED
            nativeStatus.nativeHookPartiallyAvailable -> LayerState.DEGRADED
            nativeGen > 0 && nativeGen >= snapshotGen -> LayerState.HEALTHY
            nativeGen > 0 || snapshotGen > 0 -> LayerState.STALE
            else -> LayerState.UNAVAILABLE
        }
        val nativeError = when {
            nativeState == LayerState.HEALTHY -> null
            nativeStatus.lastMountPointsApplyError.isNotBlank() -> nativeStatus.lastMountPointsApplyError
            nativeStatus.lastInlineError.isNotBlank() -> nativeStatus.lastInlineError
            nativeStatus.nativeLastError.isNotBlank() -> nativeStatus.nativeLastError
            !nativeStatusAvailable -> "MediaProvider Hook unavailable"
            !nativeStatus.inlineLibraryLoaded -> "Inline native library unavailable"
            nativeStatus.nativeLibraryKnownUnavailable -> "FUSE native library unavailable"
            nativeStatus.nativeHookPartiallyAvailable -> "FUSE native symbols partially available"
            else -> "Native mount points stale or unavailable"
        }
        val nativeReport = LayerReport(
            id = LayerId.FUSE_NATIVE_HOOK,
            state = nativeState,
            generation = gen,
            lastHeartbeatAt = if (nativeState == LayerState.HEALTHY) now else 0L,
            lastErrorAt = if (nativeState == LayerState.HEALTHY) 0L else now,
            lastError = nativeError,
            metrics = mapOf(
                "configuredMountPointsGeneration" to nativeGen.toString(),
                "snapshotConfiguredMountPointsGeneration" to snapshotGen.toString(),
                "nativeStatusSource" to nativeStatus.statusSource,
                "nativeStatusAgeMs" to nativeStatus.statusAgeMs.toString(),
                "mediaProviderHookLoaded" to nativeStatus.mediaProviderHookLoaded.toString(),
                "policyCacheInitialized" to nativeStatus.policyCacheInitialized.toString(),
                "inlineLibraryLoaded" to nativeStatus.inlineLibraryLoaded.toString(),
                "inlineHookInitialized" to nativeStatus.inlineHookInitialized.toString(),
                "fuseLibraryLoaded" to nativeStatus.fuseLibraryLoaded.toString(),
                "fuseLibraryName" to nativeStatus.fuseLibraryName,
                "hookMode" to nativeStatus.hookMode,
                "fuseJniLoadMode" to nativeStatus.fuseJniLoadMode,
                "embeddedFuseJniFound" to nativeStatus.embeddedFuseJniFound.toString(),
                "containsMountHooked" to nativeStatus.containsMountHooked.toString(),
                "startsWithHooked" to nativeStatus.startsWithHooked.toString(),
                "isFuseBpfEnabledHooked" to nativeStatus.isFuseBpfEnabledHooked.toString(),
                "fuseReqUserdataHooked" to nativeStatus.fuseReqUserdataHooked.toString(),
                "fuseBpfInstallHooked" to nativeStatus.fuseBpfInstallHooked.toString(),
                "lastMountPointsApplySuccess" to nativeStatus.lastMountPointsApplySuccess.toString(),
                "lastMountPointsApplyGeneration" to nativeStatus.lastMountPointsApplyGeneration.toString(),
                "lastMountPointsApplyCount" to nativeStatus.lastMountPointsApplyCount.toString(),
            ),
        )

        // DataBus Layer（使用 readSnapshotSafe 检测损坏快照）
        val busInit = DataBus.ensureInitialized()
        val hasPolicy = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_REDIRECT_POLICY) != null
        val hasReadOnly = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_READ_ONLY) != null
        val hasMountPoints = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS) != null
        val platformCapsJson = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_PLATFORM_CAPABILITIES)
        val platformCaps = platformCapsJson?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val hasPlatformCaps = platformCapsJson != null
        val hasNativeHookStatus = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS) != null
        val hasStatus = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_ORCHESTRATED_STATUS) != null
        val busHealthy = busInit && hasPolicy && hasReadOnly && hasMountPoints
        val busMetrics = mutableMapOf(
            "busRootExists" to busInit.toString(),
            "snapshotRedirectPolicy" to if (hasPolicy) "exists" else "missing",
            "snapshotReadOnly" to if (hasReadOnly) "exists" else "missing",
            "snapshotConfiguredMountPoints" to if (hasMountPoints) "exists" else "missing",
            "snapshotPlatformCapabilities" to if (hasPlatformCaps) "exists" else "missing",
            "snapshotNativeHookStatus" to if (hasNativeHookStatus) "exists" else "missing",
            "snapshotOrchestratedStatus" to if (hasStatus) "exists" else "missing",
        )
        if (platformCaps != null) {
            busMetrics["platformMediaProviderPackage"] =
                platformCaps.optString("mediaProviderPackageName", "")
            busMetrics["platformFuseJniLoadMode"] =
                platformCaps.optString("fuseJniLoadMode", "UNKNOWN")
            busMetrics["platformSupportedNativeHookMode"] =
                platformCaps.optString("supportedNativeHookMode", "NONE")
            busMetrics["platformMediaProviderApiShape"] =
                platformCaps.optString("mediaProviderApiShape", "UNKNOWN")
            busMetrics["platformSystemFuseJniAvailable"] =
                platformCaps.optBoolean("systemFuseJniAvailable", false).toString()
        }
        val busReport = LayerReport(
            id = LayerId.DATA_BUS,
            state = if (busHealthy) {
                LayerState.HEALTHY
            } else if (busInit) {
                LayerState.DEGRADED
            } else {
                LayerState.UNAVAILABLE
            },
            generation = gen,
            lastHeartbeatAt = if (busInit) now else 0L,
            lastErrorAt = if (busHealthy) 0L else now,
            lastError = if (busHealthy) null else "DataBus snapshot missing or bus unavailable",
            metrics = busMetrics,
        )

        // ControlPlane
        val controlReport = LayerReport(
            id = LayerId.CONTROL_PLANE,
            state = if (hooksBridgeConnected) LayerState.HEALTHY else LayerState.DEGRADED,
            generation = gen,
            lastHeartbeatAt = now,
            metrics = mapOf(
                "appBinderRegistered" to (server.cleanerService != null).toString(),
                "hooksBridgeConnected" to hooksBridgeConnected.toString(),
                "mediaProviderHookConnected" to mediaProviderHookConnected.toString(),
            ),
        )

        return OrchestratedStatus.evaluate(
            vfs = recordLayerStarted(vfsReport),
            mediaProviderJavaHook = recordLayerStarted(hookReport),
            fuseNativeHook = recordLayerStarted(nativeReport),
            dataBus = recordLayerStarted(busReport),
            controlPlane = recordLayerStarted(controlReport),
        )
    }

    /**
     * 记录某层从非健康状态首次变为 HEALTHY 的时刻。
     *
     * 用于填充 [LayerReport.lastStartedAt] 字段。
     * 一旦某层进入 HEALTHY 状态，其启动时间被永久记录；
     * 之后即使该层再次变为 UNAVAILABLE，[lastStartedAt] 也保持上次的健康启动时间。
     */
    private fun recordLayerStarted(report: LayerReport): LayerReport {
        if (report.state != LayerState.HEALTHY) {
            return report.copy(
                lastStartedAt = layerStartedAt[report.id] ?: 0L
            )
        }
        val now = report.lastHeartbeatAt
        val started = layerStartedAt[report.id]
        if (started == null) {
            // 首次变为 HEALTHY，记录启动时间
            layerStartedAt[report.id] = now
            return report.copy(lastStartedAt = now)
        }
        // 维持已记录的启动时间
        return report.copy(lastStartedAt = started)
    }

    // ── 状态快照持久化到 DataBus ──

    /**
     * 将当前状态快照发布到 DataBus。
     * App UI 可通过读取 DataBus 快照获取诊断信息，无需走 Binder。
     */
    fun publishStatusSnapshot() {
        if (!DataBus.ensureInitialized()) return
        val status = collectStatusJson()
        DataBus.writeSnapshot(DataBus.SNAPSHOT_ORCHESTRATED_STATUS, status)
    }

    private fun LayerReport.toJson(): JSONObject = JSONObject().apply {
        put("id", id.name)
        put("state", state.name)
        put("generation", generation)
        put("lastStartedAt", lastStartedAt)
        put("lastHeartbeatAt", lastHeartbeatAt)
        put("lastErrorAt", lastErrorAt)
        put("lastError", lastError)
        for ((key, value) in metrics) {
            put(key, value)
        }
    }

    private fun LayerReport.toIpc(): IpcLayerStatus {
        return IpcLayerStatus(
            id.name,
            state.name,
            generation,
            lastStartedAt,
            lastHeartbeatAt,
            lastErrorAt,
            lastError,
            metrics.keys.toTypedArray(),
            metrics.values.toTypedArray(),
        )
    }

    private fun readNativeHookStatusFromDataBus(now: Long): NativeHookRuntimeStatus? {
        val json = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS) ?: return null
        val createdAt = runCatching {
            JSONObject(json).optLong("createdAt", 0L)
        }.getOrDefault(0L)
        if (createdAt <= 0L) return null
        val ageMs = now - createdAt
        if (ageMs < 0L || ageMs > NATIVE_HOOK_STATUS_MAX_AGE_MS) {
            return null
        }
        return parseNativeHookStatus(
            json,
            source = "databus",
            statusAgeMs = ageMs,
        )
    }

    private fun parseNativeHookStatus(
        json: String,
        source: String = "unknown",
        statusAgeMs: Long = 0L,
    ): NativeHookRuntimeStatus {
        if (json.isBlank()) {
            return NativeHookRuntimeStatus(
                lastError = "Native hook status missing",
                statusSource = source,
                statusAgeMs = statusAgeMs,
            )
        }
        return try {
            val root = JSONObject(json)
            val native = root.optJSONObject("native")
            NativeHookRuntimeStatus(
                mediaProviderHookLoaded = root.optBoolean("mediaProviderHookLoaded", false),
                policyCacheInitialized = root.optBoolean("policyCacheInitialized", false),
                inlineLibraryLoaded = root.optBoolean("inlineLibraryLoaded", false),
                inlineHookInitialized = root.optBoolean("inlineHookInitialized", false),
                lastInlineError = root.optString("lastInlineError", ""),
                mountPointsGeneration = root.optLong("mountPointsGeneration", 0L),
                lastMountPointsApplySuccess = root.optBoolean("lastMountPointsApplySuccess", false),
                lastMountPointsApplyGeneration = root.optLong("lastMountPointsApplyGeneration", 0L),
                lastMountPointsApplyCount = root.optInt("lastMountPointsApplyCount", 0),
                lastMountPointsApplyError = root.optString("lastMountPointsApplyError", ""),
                fuseLibraryLoaded = native?.optBoolean("fuseLibraryLoaded", false) ?: false,
                fuseLibraryName = native?.optString("fuseLibraryName", "") ?: "",
                hookMode = native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN",
                fuseJniLoadMode = native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN",
                embeddedFuseJniFound = native?.optBoolean("embeddedFuseJniFound", false) ?: false,
                containsMountHooked = native?.optBoolean("containsMountHooked", false) ?: false,
                startsWithHooked = native?.optBoolean("startsWithHooked", false) ?: false,
                isFuseBpfEnabledHooked = native?.optBoolean("isFuseBpfEnabledHooked", false) ?: false,
                fuseReqUserdataHooked = native?.optBoolean("fuseReqUserdataHooked", false) ?: false,
                fuseBpfInstallHooked = native?.optBoolean("fuseBpfInstallHooked", false) ?: false,
                nativeLastError = native?.optString("lastError", "") ?: "",
                statusSource = source,
                statusAgeMs = statusAgeMs,
            )
        } catch (e: Exception) {
            NativeHookRuntimeStatus(
                lastError = "Invalid native hook status: ${e.message}",
                statusSource = source,
                statusAgeMs = statusAgeMs,
            )
        }
    }

    private data class NativeHookRuntimeStatus(
        val mediaProviderHookLoaded: Boolean = false,
        val policyCacheInitialized: Boolean = false,
        val inlineLibraryLoaded: Boolean = false,
        val inlineHookInitialized: Boolean = false,
        val lastInlineError: String = "",
        val mountPointsGeneration: Long = 0L,
        val lastMountPointsApplySuccess: Boolean = false,
        val lastMountPointsApplyGeneration: Long = 0L,
        val lastMountPointsApplyCount: Int = 0,
        val lastMountPointsApplyError: String = "",
        val fuseLibraryLoaded: Boolean = false,
        val fuseLibraryName: String = "",
        val hookMode: String = "UNKNOWN",
        val fuseJniLoadMode: String = "UNKNOWN",
        val embeddedFuseJniFound: Boolean = false,
        val containsMountHooked: Boolean = false,
        val startsWithHooked: Boolean = false,
        val isFuseBpfEnabledHooked: Boolean = false,
        val fuseReqUserdataHooked: Boolean = false,
        val fuseBpfInstallHooked: Boolean = false,
        val nativeLastError: String = "",
        val lastError: String = "",
        val statusSource: String = "unknown",
        val statusAgeMs: Long = 0L,
    ) {
        val nativeLibraryKnownUnavailable: Boolean
            get() = inlineLibraryLoaded && nativeLastError.contains("dlopen libfuse_jni.so failed")

        val nativeHookPartiallyAvailable: Boolean
            get() = inlineLibraryLoaded && fuseLibraryLoaded && when (hookMode) {
                "EMBEDDED_GOT_PATCH" -> !embeddedFuseJniFound || !containsMountHooked
                "XHOOK" -> !containsMountHooked || !startsWithHooked || !isFuseBpfEnabledHooked
                else -> false
            }
    }
}
