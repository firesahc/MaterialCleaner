package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import org.json.JSONObject

object NativeHookLayerReporter {
    private const val NATIVE_HOOK_STATUS_MAX_AGE_MS = 15_000L

    fun collect(
        generation: Long,
        now: Long,
        mediaProviderHookConnected: Boolean,
    ): LayerReport {
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
        return LayerReport(
            id = LayerId.FUSE_NATIVE_HOOK,
            state = nativeState,
            generation = generation,
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
