package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import org.json.JSONArray
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
                mediaProviderLoaded = false,
                inlineState = "NOT_LOADED",
                lastError = "MediaProvider Hook unavailable",
                statusSource = "unavailable",
            )
        }
        val nativeStatusAvailable = nativeStatusFromDataBus != null || mediaProviderHookConnected
        val snapshotGen = MediaProviderHookGateway.configuredMountPointsSnapshotGeneration()
        val nativeGen = nativeStatus.mountPointsGeneration
        val policySynced = nativeStatus.lastApplySuccess &&
                (snapshotGen <= 0L || nativeGen >= snapshotGen)
        val platformNativeHookMode = readPlatformSupportedNativeHookMode()
        val nativeHookModeMismatch = isHookModeMismatch(platformNativeHookMode, nativeStatus.hookMode)
        val nativeState = when {
            !nativeStatusAvailable -> LayerState.UNAVAILABLE
            nativeStatus.inlineState == "DISABLED" -> LayerState.DISABLED
            nativeStatus.inlineState == "FUSE_WAITING" ||
                    nativeStatus.inlineState == "INLINE_LOADED" -> LayerState.RECOVERING
            nativeStatus.coreAvailable && !policySynced -> LayerState.STALE
            nativeStatus.inlineState == "HOOK_READY_FULL" && policySynced -> LayerState.HEALTHY
            nativeStatus.inlineState == "HOOK_READY_CORE" && policySynced -> LayerState.HEALTHY
            nativeStatus.inlineState == "HOOK_DEGRADED" && policySynced -> LayerState.DEGRADED
            nativeStatus.coreAvailable && policySynced -> LayerState.DEGRADED
            nativeStatus.fuseLibraryLoaded -> LayerState.UNAVAILABLE
            else -> LayerState.UNAVAILABLE
        }
        val nativeError = when {
            nativeState == LayerState.HEALTHY -> null
            nativeStatus.lastApplyError.isNotBlank() -> nativeStatus.lastApplyError
            nativeStatus.inlineLastError.isNotBlank() -> nativeStatus.inlineLastError
            nativeStatus.nativeLastError.isNotBlank() -> nativeStatus.nativeLastError
            nativeStatus.lastError.isNotBlank() -> nativeStatus.lastError
            !nativeStatusAvailable -> "MediaProvider Hook unavailable"
            nativeState == LayerState.STALE -> "Native mount points stale"
            nativeState == LayerState.RECOVERING -> "Native hook initialization pending"
            nativeState == LayerState.DISABLED -> "Native hook disabled"
            !nativeStatus.coreAvailable -> "Native core symbol containsMount unavailable"
            else -> "Native hook degraded"
        }

        return LayerReport(
            id = LayerId.FUSE_NATIVE_HOOK,
            state = nativeState,
            generation = generation,
            lastHeartbeatAt = if (nativeState == LayerState.HEALTHY ||
                nativeState == LayerState.DEGRADED
            ) now else 0L,
            lastErrorAt = if (nativeState == LayerState.HEALTHY) 0L else now,
            lastError = nativeError,
            metrics = linkedMapOf(
                "nativeHookState" to nativeStatus.inlineState,
                "nativeCapabilityLevel" to nativeStatus.capabilityLevel,
                "nativeCoreAvailable" to nativeStatus.coreAvailable.toString(),
                "nativeMissingSymbols" to nativeStatus.missingSymbols,
                "configuredMountPointsGeneration" to nativeGen.toString(),
                "snapshotConfiguredMountPointsGeneration" to snapshotGen.toString(),
                "nativePolicySynced" to policySynced.toString(),
                "nativeHookModeMismatch" to nativeHookModeMismatch.toString(),
                "platformSupportedNativeHookMode" to platformNativeHookMode,
                "nativeStatusSource" to nativeStatus.statusSource,
                "nativeStatusAgeMs" to nativeStatus.statusAgeMs.toString(),
                "mediaProviderHookLoaded" to nativeStatus.mediaProviderLoaded.toString(),
                "policyCacheInitialized" to nativeStatus.policyCacheInitialized.toString(),
                "inlineLibraryLoaded" to nativeStatus.inlineLoaded.toString(),
                "inlineHookInitialized" to nativeStatus.inlineInitialized.toString(),
                "inlineRetryCount" to nativeStatus.inlineRetryCount.toString(),
                "inlineNextRetryAt" to nativeStatus.inlineNextRetryAt.toString(),
                "inlineRetryExhausted" to nativeStatus.inlineRetryExhausted.toString(),
                "inlineDisabledByPlatform" to nativeStatus.inlineDisabledByPlatform.toString(),
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
                "lastMountPointsApplySuccess" to nativeStatus.lastApplySuccess.toString(),
                "lastMountPointsApplyGeneration" to nativeStatus.lastApplyGeneration.toString(),
                "lastMountPointsApplyCount" to nativeStatus.lastApplyCount.toString(),
                "fuseJavaGateDiscoveredCount" to nativeStatus.fuseJavaGateDiscoveredCount.toString(),
                "fuseJavaGateHookedCount" to nativeStatus.fuseJavaGateHookedCount.toString(),
                "fuseJavaGateUnknownCount" to nativeStatus.fuseJavaGateUnknownCount.toString(),
                "fuseJavaGateFailedCount" to nativeStatus.fuseJavaGateFailedCount.toString(),
            ),
        )
    }

    private fun readPlatformSupportedNativeHookMode(): String {
        val json = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?: return "UNKNOWN"
        return runCatching {
            JSONObject(json).optString("supportedNativeHookMode", "UNKNOWN")
        }.getOrDefault("UNKNOWN")
    }

    private fun isHookModeMismatch(platformMode: String, runtimeMode: String): Boolean {
        if (platformMode == "UNKNOWN" || platformMode == "NONE") return false
        if (runtimeMode == "UNKNOWN" || runtimeMode == "NONE") return false
        return when (platformMode) {
            "XHOOK" -> !runtimeMode.startsWith("XHOOK")
            else -> runtimeMode != platformMode
        }
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
            val mediaProvider = root.optJSONObject("mediaProvider")
            val policyCache = root.optJSONObject("policyCache")
            val inline = root.optJSONObject("inline")
            val native = root.optJSONObject("native")
            val symbols = native?.optJSONObject("symbols")
            val policy = root.optJSONObject("policy")
            val fuseJavaGate = root.optJSONObject("fuseJavaGate")
            NativeHookRuntimeStatus(
                mediaProviderLoaded = mediaProvider?.optBoolean("loaded", false) ?: false,
                policyCacheInitialized = policyCache?.optBoolean("initialized", false) ?: false,
                inlineState = inline?.optString("state", "NOT_LOADED") ?: "NOT_LOADED",
                inlineLoaded = inline?.optBoolean("loaded", false) ?: false,
                inlineInitialized = inline?.optBoolean("initialized", false) ?: false,
                inlineRetryCount = inline?.optInt("retryCount", 0) ?: 0,
                inlineNextRetryAt = inline?.optLong("nextRetryAt", 0L) ?: 0L,
                inlineRetryExhausted = inline?.optBoolean("retryExhausted", false) ?: false,
                inlineDisabledByPlatform =
                    inline?.optBoolean("disabledByPlatform", false) ?: false,
                inlineLastError = inline?.optString("lastError", "") ?: "",
                mountPointsGeneration = policy?.optLong("mountPointsGeneration", 0L) ?: 0L,
                lastApplySuccess = policy?.optBoolean("lastApplySuccess", false) ?: false,
                lastApplyGeneration = policy?.optLong("lastApplyGeneration", 0L) ?: 0L,
                lastApplyCount = policy?.optInt("lastApplyCount", 0) ?: 0,
                lastApplyError = policy?.optString("lastApplyError", "") ?: "",
                fuseLibraryLoaded = native?.optBoolean("fuseLibraryLoaded", false) ?: false,
                fuseLibraryName = native?.optString("fuseLibraryName", "") ?: "",
                hookMode = native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN",
                fuseJniLoadMode = native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN",
                embeddedFuseJniFound = native?.optBoolean("embeddedFuseJniFound", false) ?: false,
                containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false,
                startsWithHooked = symbols?.optBoolean("startsWith", false) ?: false,
                isFuseBpfEnabledHooked = symbols?.optBoolean("isFuseBpfEnabled", false) ?: false,
                fuseReqUserdataHooked = symbols?.optBoolean("fuseReqUserdata", false) ?: false,
                fuseBpfInstallHooked = symbols?.optBoolean("fuseBpfInstall", false) ?: false,
                missingSymbols = native?.optJSONArray("missingSymbols").toCsv(),
                nativeLastError = native?.optString("lastError", "") ?: "",
                fuseJavaGateDiscoveredCount =
                    fuseJavaGate?.optInt("discoveredCount", 0) ?: 0,
                fuseJavaGateHookedCount =
                    fuseJavaGate?.optInt("hookedCount", 0) ?: 0,
                fuseJavaGateUnknownCount =
                    fuseJavaGate?.optInt("unknownCount", 0) ?: 0,
                fuseJavaGateFailedCount =
                    fuseJavaGate?.optInt("failedCount", 0) ?: 0,
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

    private fun JSONArray?.toCsv(): String {
        if (this == null || length() == 0) return ""
        return buildList {
            for (i in 0 until length()) {
                add(optString(i, ""))
            }
        }.filter { it.isNotBlank() }.joinToString(",")
    }

    private data class NativeHookRuntimeStatus(
        val mediaProviderLoaded: Boolean = false,
        val policyCacheInitialized: Boolean = false,
        val inlineState: String = "NOT_LOADED",
        val inlineLoaded: Boolean = false,
        val inlineInitialized: Boolean = false,
        val inlineRetryCount: Int = 0,
        val inlineNextRetryAt: Long = 0L,
        val inlineRetryExhausted: Boolean = false,
        val inlineDisabledByPlatform: Boolean = false,
        val inlineLastError: String = "",
        val mountPointsGeneration: Long = 0L,
        val lastApplySuccess: Boolean = false,
        val lastApplyGeneration: Long = 0L,
        val lastApplyCount: Int = 0,
        val lastApplyError: String = "",
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
        val missingSymbols: String = "",
        val nativeLastError: String = "",
        val fuseJavaGateDiscoveredCount: Int = 0,
        val fuseJavaGateHookedCount: Int = 0,
        val fuseJavaGateUnknownCount: Int = 0,
        val fuseJavaGateFailedCount: Int = 0,
        val lastError: String = "",
        val statusSource: String = "unknown",
        val statusAgeMs: Long = 0L,
    ) {
        val coreAvailable: Boolean
            get() = containsMountHooked

        val capabilityLevel: String
            get() = when {
                containsMountHooked &&
                        startsWithHooked &&
                        isFuseBpfEnabledHooked &&
                        fuseReqUserdataHooked &&
                        fuseBpfInstallHooked -> "FULL"
                containsMountHooked && startsWithHooked -> "CORE"
                containsMountHooked -> "DEGRADED"
                else -> "UNAVAILABLE"
            }
    }
}
