package me.gm.cleaner.runtime.server

import android.util.Log
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import me.gm.cleaner.runtime.server.observer.ObserverManager
import me.gm.cleaner.runtime.server.observer.StorageMountObserver
import me.gm.cleaner.runtime.server.orchestrator.EventConsumerScheduler
import me.gm.cleaner.runtime.server.orchestrator.HookRecoveryCoordinator
import me.gm.cleaner.runtime.server.orchestrator.MediaProviderRecoveryStrategy
import me.gm.cleaner.runtime.server.orchestrator.RuntimeStatusAggregator

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

    private val statusAggregator = RuntimeStatusAggregator(server)
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

    init {
        eventConsumerScheduler.onHeartbeat = {
            hookRecoveryCoordinator.nativeHookHealthCheck()
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

        if (ServicePreferences.autoLogging) {
            server.mAutoLogging.registerBootShutdownReceiver(AutoLogging.MODE_CONTINUOUSLY)
        }

        val dataBusReady = SnapshotPublisher.publishAll()

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
