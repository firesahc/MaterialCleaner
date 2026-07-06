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
    private companion object {
        private const val NATIVE_HOOK_STATUS_MAX_AGE_MS = 15_000L
    }

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
