package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.lifecycle.ObserverManager
import me.gm.cleaner.runtime.server.process.BaseProcessObserver
import me.gm.cleaner.runtime.server.storage.StorageMountObserver
import me.gm.cleaner.runtime.server.orchestrator.EventConsumerScheduler
import me.gm.cleaner.runtime.server.orchestrator.HookRecoveryCoordinator
import me.gm.cleaner.runtime.server.orchestrator.MediaProviderRecoveryStrategy
import me.gm.cleaner.runtime.server.orchestrator.RuntimeStatusAggregator
import me.gm.cleaner.runtime.server.orchestrator.RuntimeRecoverySnapshot

/**
 * 三层编排器。
 *
 * 负责三层拦截能力的启动顺序与跨组件委托：
 * - VFS 主机制独立启动，不受 Hook Binder 状态影响
 * - DataBus 发布策略快照与事件队列
 * - Hook 死亡隔离恢复，不重启 VFS 主机制
 */
class LayerOrchestrator(
    private val server: CleanerServer,
) {
    private companion object {
        private const val TAG = "LayerOrchestrator"
    }

    private val eventConsumerScheduler = EventConsumerScheduler(server)
    private val mediaProviderRecoveryStrategy = MediaProviderRecoveryStrategy(server)
    private val hookRecoveryCoordinator = HookRecoveryCoordinator(
        server = server,
        mediaProviderRecoveryStrategy = mediaProviderRecoveryStrategy,
        onReconnectReady = {
            eventConsumerScheduler.pollOnce()
            eventConsumerScheduler.start()
        },
    )
    private val statusAggregator = RuntimeStatusAggregator(server) {
        RuntimeRecoverySnapshot(
            hook = hookRecoveryCoordinator.snapshot(),
            mediaProvider = mediaProviderRecoveryStrategy.snapshot(),
        )
    }

    init {
        eventConsumerScheduler.onHeartbeat = {
            hookRecoveryCoordinator.nativeHookHealthCheck()
            // 失败挂载重收敛：复用既有 2s 心跳，不另起线程；
            // 冷却/永久分类由 Mounter 内聚，出错不影响后继发布。
            runCatching {
                ObserverManager.getObserver(BaseProcessObserver::class.java)
                    ?.requeueFailedMounts()
            }
            statusAggregator.publishStatusSnapshot()
        }
    }

    /**
     * 执行服务器启动编排。
     */
    fun initialize() {
        Log.i(TAG, "initialize start")

        MediaProviderHookGateway.start(server)

        ObserverManager.startAllObservers(server)

        server.mPackageReceiver.registerPackageReceiver()

        // 日志采集进程按配置对账：应启用而未运行则启动，应停用则保持未运行。
        server.mAutoLogging.reconcileWithPreferences()

        val dataBusReady = SnapshotPublisher.publishAll()

        // 启动即收敛：publish 只更新快照，不管存量进程命名空间；
        // 不在这里 remountAll 的话，重启前挂上的进程将永久失 cover，
        // 直到下一次规则变更（挂载 decay 的根因之一）。
        ObserverManager.getObserver(BaseProcessObserver::class.java)?.remountAll()

        MediaProviderHookGateway.registerAndRefreshFromDataBus(server)
        if (!dataBusReady) {
            Log.w(TAG, "DataBus unavailable during initialize, snapshots will be published on reconnect")
        }

        val mountObserver = ObserverManager.getObserver(StorageMountObserver::class.java)
        if (mountObserver != null) {
            mountObserver.setCleanerServer(server)
            mountObserver.registerListener(mountObserver)
        }

        BinderSender.register(server.cleanerService)

        server.sendBinderToManger(server.cleanerService)

        eventConsumerScheduler.prepare()
        eventConsumerScheduler.pollOnce()
        eventConsumerScheduler.start()

        Log.i(TAG, "initialize done")
    }

    /**
     * 处理 Hooks Binder 死亡事件。
     */
    fun onHooksBinderDied() {
        hookRecoveryCoordinator.onHooksBinderDied()
    }

    fun collectStatusJson(): String =
        statusAggregator.collectStatusJson()

    fun collectStatusForIpc() =
        statusAggregator.collectStatusForIpc()

    fun publishStatusSnapshot() {
        statusAggregator.publishStatusSnapshot()
    }
}
