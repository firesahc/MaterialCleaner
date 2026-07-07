package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import me.gm.cleaner.model.LayerStatus as IpcLayerStatus
import me.gm.cleaner.model.OrchestratedStatus as IpcOrchestratedStatus

/**
 * 三层运行状态聚合器。
 */
class RuntimeStatusAggregator(
    private val server: CleanerServer,
) {
    private val statusGeneration = AtomicLong(0)
    private val layerStartedAt = ConcurrentHashMap<LayerId, Long>()

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

    fun publishStatusSnapshot() {
        if (!DataBus.ensureInitialized()) return
        val status = collectStatusJson()
        DataBus.writeSnapshot(DataBus.SNAPSHOT_ORCHESTRATED_STATUS, status)
    }

    private fun collectStatus(): OrchestratedStatus {
        val now = System.currentTimeMillis()
        val gen = statusGeneration.incrementAndGet()
        val vfsReport = server.vfsLayerController.collectReport(gen, now)

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

        val nativeReport = NativeHookLayerReporter.collect(
            generation = gen,
            now = now,
            mediaProviderHookConnected = mediaProviderHookConnected,
        )

        val busReport = DataBusLayerReporter.collect(gen, now)

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

    private fun recordLayerStarted(report: LayerReport): LayerReport {
        if (report.state != LayerState.HEALTHY) {
            return report.copy(
                lastStartedAt = layerStartedAt[report.id] ?: 0L
            )
        }
        val now = report.lastHeartbeatAt
        val started = layerStartedAt[report.id]
        if (started == null) {
            layerStartedAt[report.id] = now
            return report.copy(lastStartedAt = now)
        }
        return report.copy(lastStartedAt = started)
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

}
