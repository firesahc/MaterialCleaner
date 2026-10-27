package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway
import org.json.JSONArray
import org.json.JSONObject
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

internal enum class PolicyFreshnessState {
    UNKNOWN,
    CURRENT,
    STALE;

    companion object {
        fun from(value: String?): PolicyFreshnessState =
            values().firstOrNull { it.name == value?.uppercase() } ?: UNKNOWN
    }
}

internal data class HookPolicyFreshnessSummary(
    val redirect: PolicyFreshnessState = PolicyFreshnessState.UNKNOWN,
    val readOnly: PolicyFreshnessState = PolicyFreshnessState.UNKNOWN,
    val platformCapabilities: PolicyFreshnessState = PolicyFreshnessState.UNKNOWN,
    val mountPoints: PolicyFreshnessState = PolicyFreshnessState.UNKNOWN,
    val domainFailures: Map<String, String> = emptyMap(),
)

internal data class FuseJavaGateCoverageSummary(
    val scanComplete: Boolean = false,
    val discoveredCount: Int = 0,
    val hookedMethods: List<String> = emptyList(),
    val unknownMethods: List<String> = emptyList(),
    val uidSkippedMethods: List<String> = emptyList(),
    val failedMethods: List<String> = emptyList(),
) {
    val hookedMethodNames: Set<String> get() = hookedMethods.methodNames()
    val coverageState: LayerState
        get() = when {
            !scanComplete -> LayerState.RECOVERING
            criticalUncoveredMethods.isNotEmpty() -> LayerState.DEGRADED
            else -> LayerState.HEALTHY
        }
    val criticalMissingMethods: List<String>
        get() = CRITICAL_METHODS.filterNot(hookedMethodNames::contains)
    val criticalUidSkippedMethods: List<String>
        get() = uidSkippedMethods.filter { it.methodName() in CRITICAL_METHODS }
    val criticalFailedMethods: List<String>
        get() = failedMethods.filter { it.methodName() in CRITICAL_METHODS }
    val criticalUncoveredMethods: List<String>
        get() = (criticalMissingMethods +
                criticalUidSkippedMethods.map { it.methodName() } +
                criticalFailedMethods.map { it.methodName() })
            .distinct()

    val auxiliaryLookupOpenMethods: List<String>
        get() = (hookedMethods + unknownMethods + uidSkippedMethods + failedMethods)
            .map { it.methodName() }
            .filter { method ->
                method !in CRITICAL_METHODS &&
                        (method.contains("lookup", ignoreCase = true) ||
                                method.contains("open", ignoreCase = true))
            }
            .distinct()
    val auxiliaryLookupOpenHookedCount: Int
        get() = auxiliaryLookupOpenMethods.count(hookedMethodNames::contains)
    val auxiliaryLookupOpenUncoveredMethods: List<String>
        get() = auxiliaryLookupOpenMethods.filterNot(hookedMethodNames::contains)

    companion object {
        val CRITICAL_METHODS = listOf(
            "openWithFuse",
            "renameForFuse",
            "isDirAccessAllowedForFuse",
            "isDirectoryCreationOrDeletionAllowedForFuse",
            "insertFileIfNecessaryForFuse",
            "deleteFileForFuse",
        )

        val UNKNOWN = FuseJavaGateCoverageSummary()
    }
}

internal data class HookRuntimeStatusSnapshot(
    val nativeStatus: NativeHookLayerReporter.NativeHookRuntimeStatus,
    val nativeStatusAvailable: Boolean,
    val snapshotGeneration: Long,
    val snapshotEpoch: String,
    val platformSupportedNativeHookMode: String,
    val freshness: HookPolicyFreshnessSummary,
    val fuseJavaGate: FuseJavaGateCoverageSummary,
)

private fun List<String>.methodNames(): Set<String> = mapTo(linkedSetOf()) { it.methodName() }

private fun String.methodName(): String = trim().substringBefore(' ').substringBefore('(')

object NativeHookLayerReporter {
    private const val NATIVE_HOOK_STATUS_MAX_AGE_MS = 15_000L

    // ── 指标键契约（DiagnosticArchive 等消费方必须引用此处，不得另写字面量） ──
    const val KEY_SNAPSHOT_EPOCH = "snapshotPublisherEpoch"
    const val KEY_APPLIED_EPOCH = "nativeAppliedPublisherEpoch"
    const val KEY_POLICY_SYNCED = "nativePolicySynced"
    const val KEY_SYNC_VERDICT = "nativeSyncVerdict"

    internal fun readStatusSnapshot(
        now: Long,
        mediaProviderHookConnected: Boolean,
    ): HookRuntimeStatusSnapshot {
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
        return HookRuntimeStatusSnapshot(
            nativeStatus = nativeStatus,
            nativeStatusAvailable = nativeStatusFromDataBus != null || mediaProviderHookConnected,
            snapshotGeneration = MediaProviderHookGateway.configuredMountPointsSnapshotGeneration(),
            snapshotEpoch = MediaProviderHookGateway.mountPointsSnapshotEpoch(),
            platformSupportedNativeHookMode = readPlatformSupportedNativeHookMode(),
            freshness = nativeStatus.freshness,
            fuseJavaGate = nativeStatus.fuseJavaGate,
        )
    }

    internal fun collect(
        generation: Long,
        now: Long,
        statusSnapshot: HookRuntimeStatusSnapshot,
    ): LayerReport {
        val nativeStatus = statusSnapshot.nativeStatus
        val nativeStatusAvailable = statusSnapshot.nativeStatusAvailable
        val snapshotGen = statusSnapshot.snapshotGeneration
        val snapshotEpoch = statusSnapshot.snapshotEpoch
        val nativeGen = nativeStatus.mountPointsGeneration
        val mountFreshnessCurrent =
            statusSnapshot.freshness.mountPoints == PolicyFreshnessState.CURRENT
        // 同步裁决收口到纯策略：每个结论都需要可验证证据（身份 + 终态 + sane 时间），
        // 不再用单一时间戳冒充进展。
        val syncVerdict = NativeSyncPolicy.evaluate(
            NativeSyncPolicy.Input(
                snapshotEpoch = snapshotEpoch,
                snapshotGen = snapshotGen,
                appliedEpoch = nativeStatus.appliedPublisherEpoch,
                appliedGen = nativeGen,
                // 陈旧或未知 freshness 不能依靠旧的 apply 成功记录继续裁为 SYNCED。
                lastApplySuccess = nativeStatus.lastApplySuccess && mountFreshnessCurrent,
                attemptEpoch = nativeStatus.lastApplyAttemptEpoch,
                attemptGen = nativeStatus.lastApplyAttemptGeneration,
                attemptAt = nativeStatus.lastApplyAttemptAt,
                applicationState = nativeStatus.applicationState,
                now = now,
                snapshotFreshnessState = statusSnapshot.freshness.mountPoints.name,
            ),
        )
        val epochMismatch = snapshotEpoch.isNotBlank() &&
                nativeStatus.appliedPublisherEpoch != snapshotEpoch
        val policySynced = syncVerdict == NativeSyncPolicy.Verdict.SYNCED
        val nativeCapabilitiesCurrent =
            statusSnapshot.freshness.platformCapabilities == PolicyFreshnessState.CURRENT
        val nativeLayerPolicySynced = policySynced && nativeCapabilitiesCurrent
        val platformNativeHookMode = statusSnapshot.platformSupportedNativeHookMode
        val nativeHookModeMismatch = isHookModeMismatch(platformNativeHookMode, nativeStatus.hookMode)
        val nativeState = NativeHookStateMapper.map(
            available = nativeStatusAvailable,
            coreAvailable = nativeStatus.coreAvailable,
            inlineState = nativeStatus.inlineState,
            syncVerdict = syncVerdict,
            policySynced = nativeLayerPolicySynced,
            fuseLibraryLoaded = nativeStatus.fuseLibraryLoaded,
        )
        val nativeError = when {
            nativeState == LayerState.HEALTHY -> null
            nativeState == LayerState.STALE && !mountFreshnessCurrent ->
                "Native mount points snapshot freshness ${statusSnapshot.freshness.mountPoints}"
            nativeState == LayerState.STALE && !nativeCapabilitiesCurrent ->
                "Native platform capabilities freshness ${statusSnapshot.freshness.platformCapabilities}"
            nativeStatus.lastApplyError.isNotBlank() -> nativeStatus.lastApplyError
            nativeStatus.inlineLastError.isNotBlank() -> nativeStatus.inlineLastError
            nativeStatus.nativeLastError.isNotBlank() -> nativeStatus.nativeLastError
            nativeStatus.lastError.isNotBlank() -> nativeStatus.lastError
            !nativeStatusAvailable -> "MediaProvider Hook unavailable"
            nativeState == LayerState.DEGRADED &&
                    syncVerdict == NativeSyncPolicy.Verdict.UNSUPPORTED ->
                "Native hook unsupported by platform"
            nativeState == LayerState.STALE -> "Native mount points stale"
            nativeState == LayerState.RECOVERING && epochMismatch ->
                "Native policy sync pending (epoch change)"
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
                "hookVersionCode" to nativeStatus.hookVersionCode.toString(),
                "nativeCapabilityLevel" to nativeStatus.capabilityLevel,
                "nativeCoreAvailable" to nativeStatus.coreAvailable.toString(),
                "nativeMissingSymbols" to nativeStatus.missingSymbols,
                "configuredMountPointsGeneration" to nativeGen.toString(),
                "snapshotConfiguredMountPointsGeneration" to snapshotGen.toString(),
                KEY_SNAPSHOT_EPOCH to snapshotEpoch,
                KEY_APPLIED_EPOCH to nativeStatus.appliedPublisherEpoch,
                KEY_POLICY_SYNCED to policySynced.toString(),
                KEY_SYNC_VERDICT to syncVerdict.name,
                "nativePolicyApplicationState" to nativeStatus.applicationState,
                "nativePolicyCacheApplicationState" to
                        statusSnapshot.nativeStatus.applicationState,
                "nativeRedirectPolicyState" to nativeStatus.redirectPolicyState,
                "nativeReadOnlyPolicyState" to nativeStatus.readOnlyPolicyState,
                "nativeMountPointsFreshnessState" to statusSnapshot.freshness.mountPoints.name,
                "nativeRedirectFreshnessState" to statusSnapshot.freshness.redirect.name,
                "nativeReadOnlyFreshnessState" to statusSnapshot.freshness.readOnly.name,
                "nativePlatformCapabilitiesFreshnessState" to
                        statusSnapshot.freshness.platformCapabilities.name,
                "nativeLayerPolicyReady" to nativeLayerPolicySynced.toString(),
                "nativePolicyCacheDomainFailureCount" to
                        statusSnapshot.freshness.domainFailures.size.toString(),
                "nativePolicyCacheDomainFailures" to
                        JSONObject(statusSnapshot.freshness.domainFailures).toString(),
                "nativeAppliedRedirectRevision" to nativeStatus.appliedRedirectRevision,
                "nativeAppliedReadOnlyRevision" to nativeStatus.appliedReadOnlyRevision,
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
                "fuseBpfFillEntriesHooked" to nativeStatus.fillEntriesHooked.toString(),
                "fuseBpfInstallHooked" to nativeStatus.installHooked.toString(),
                "fuseBpfEffectiveHooked" to nativeStatus.effectiveHooked.toString(),
                "lastMountPointsApplySuccess" to nativeStatus.lastApplySuccess.toString(),
                "nativePolicyAppliedToExecutor" to nativeStatus.lastApplySuccess.toString(),
                "lastMountPointsApplyGeneration" to nativeStatus.lastApplyGeneration.toString(),
                "lastMountPointsApplyCount" to nativeStatus.lastApplyCount.toString(),
                "fuseJavaGateDiscoveredCount" to nativeStatus.fuseJavaGateDiscoveredCount.toString(),
                "fuseJavaGateHookedCount" to nativeStatus.fuseJavaGateHookedCount.toString(),
                "fuseJavaGateUnknownCount" to nativeStatus.fuseJavaGateUnknownCount.toString(),
                "fuseJavaGateFailedCount" to nativeStatus.fuseJavaGateFailedCount.toString(),
                "fuseJavaGateScanComplete" to statusSnapshot.fuseJavaGate.scanComplete.toString(),
                "fuseJavaGateCriticalMissingMethods" to
                        statusSnapshot.fuseJavaGate.criticalMissingMethods.joinToString(","),
                "fuseJavaGateCriticalUidSkippedMethods" to
                        statusSnapshot.fuseJavaGate.criticalUidSkippedMethods
                            .map { it.methodName() }.distinct().joinToString(","),
                "fuseJavaGateCriticalFailedMethods" to
                        statusSnapshot.fuseJavaGate.criticalFailedMethods
                            .map { it.methodName() }.distinct().joinToString(","),
                "fuseJavaGateAuxiliaryLookupOpenMethods" to
                        statusSnapshot.fuseJavaGate.auxiliaryLookupOpenMethods.joinToString(","),
                "fuseJavaGateAuxiliaryLookupOpenHookedCount" to
                        statusSnapshot.fuseJavaGate.auxiliaryLookupOpenHookedCount.toString(),
                "fuseJavaGateAuxiliaryLookupOpenUncoveredMethods" to
                        statusSnapshot.fuseJavaGate.auxiliaryLookupOpenUncoveredMethods.joinToString(","),
            ),
        )
    }

    private fun readPlatformSupportedNativeHookMode(): String {
        val json = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
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
        val json = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS) ?: return null
        val parsed = parseNativeHookStatus(json, source = "databus")
        if (parsed.createdAt <= 0L) return null
        val ageMs = now - parsed.createdAt
        if (ageMs < 0L || ageMs > NATIVE_HOOK_STATUS_MAX_AGE_MS) {
            return null
        }
        return parsed.copy(statusAgeMs = ageMs)
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
            val domainFailures = policyCache?.optJSONObject("domainFailures")?.toFailureMap()
                ?: emptyMap()
            val freshness = HookPolicyFreshnessSummary(
                redirect = PolicyFreshnessState.from(
                    policyCache?.optString("redirectFreshnessState"),
                ),
                readOnly = PolicyFreshnessState.from(
                    policyCache?.optString("readOnlyFreshnessState"),
                ),
                platformCapabilities = PolicyFreshnessState.from(
                    policyCache?.optString("platformCapabilitiesFreshnessState"),
                ),
                mountPoints = PolicyFreshnessState.from(policy?.optString("freshnessState")),
                domainFailures = domainFailures,
            )
            val coverage = FuseJavaGateCoverageSummary(
                scanComplete = fuseJavaGate?.optBoolean("scanComplete", false) ?: false,
                discoveredCount = fuseJavaGate?.optInt("discoveredCount", 0) ?: 0,
                hookedMethods = fuseJavaGate?.optJSONArray("hookedMethods").toStringList(),
                unknownMethods = fuseJavaGate?.optJSONArray("unknownMethods").toStringList(),
                uidSkippedMethods = fuseJavaGate?.optJSONArray("uidSkippedMethods").toStringList(),
                failedMethods = fuseJavaGate?.optJSONArray("failedMethods").toStringList(),
            )
            NativeHookRuntimeStatus(
                createdAt = root.optLong("createdAt", 0L),
                freshness = freshness,
                fuseJavaGate = coverage,
                hookVersionCode = root.optInt("hookVersionCode", 0),
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
                appliedPublisherEpoch = policy?.optString("appliedPublisherEpoch", "") ?: "",
                lastApplySuccess = policy?.optBoolean("lastApplySuccess", false) ?: false,
                lastApplyAt = policy?.optLong("lastApplyAt", 0L) ?: 0L,
                lastApplyAttemptAt = policy?.optLong("lastAttemptAt", 0L) ?: 0L,
                lastApplyAttemptEpoch = policy?.optString("lastAttemptEpoch", "") ?: "",
                lastApplyAttemptGeneration = policy?.optLong("lastAttemptGeneration", 0L) ?: 0L,
                lastApplyGeneration = policy?.optLong("lastApplyGeneration", 0L) ?: 0L,
                lastApplyCount = policy?.optInt("lastApplyCount", 0) ?: 0,
                lastApplyError = policy?.optString("lastApplyError", "") ?: "",
                applicationState = policy?.optString("applicationState", "PENDING")
                    ?: "PENDING",
                redirectPolicyState = policyCache?.optString("redirectState", "PENDING")
                    ?: "PENDING",
                readOnlyPolicyState = policyCache?.optString("readOnlyState", "PENDING")
                    ?: "PENDING",
                appliedRedirectRevision =
                    policy?.optString("appliedRedirectRevision", "") ?: "",
                appliedReadOnlyRevision =
                    policyCache?.optString("readOnlyAppliedRevision", "") ?: "",
                fuseLibraryLoaded = native?.optBoolean("fuseLibraryLoaded", false) ?: false,
                fuseLibraryName = native?.optString("fuseLibraryName", "") ?: "",
                hookMode = native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN",
                fuseJniLoadMode = native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN",
                embeddedFuseJniFound = native?.optBoolean("embeddedFuseJniFound", false) ?: false,
                containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false,
                startsWithHooked = symbols?.optBoolean("startsWith", false) ?: false,
                isFuseBpfEnabledHooked = symbols?.optBoolean("isFuseBpfEnabled", false) ?: false,
                fuseReqUserdataHooked = symbols?.optBoolean("fuseReqUserdata", false) ?: false,
                fillEntriesHooked = symbols?.optBoolean("fillEntries", false) ?: false,
                installHooked = symbols?.optBoolean("install", false) ?: false,
                effectiveHooked = run {
                    val f = symbols?.optBoolean("fillEntries", false) ?: false
                    val i = symbols?.optBoolean("install", false) ?: false
                    symbols?.optBoolean("effective", f || i) ?: (f || i)
                },
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

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null || length() == 0) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i, "").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONObject.toFailureMap(): Map<String, String> = buildMap {
        val iterator = keys()
        while (iterator.hasNext()) {
            val domain = iterator.next()
            val detail = optJSONObject(domain) ?: continue
            val code = detail.optString("code", "UNKNOWN")
            put(domain, code)
        }
    }

    internal data class NativeHookRuntimeStatus(
        val hookVersionCode: Int = 0,
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
        val appliedPublisherEpoch: String = "",
        val lastApplySuccess: Boolean = false,
        val lastApplyAt: Long = 0L,
        val lastApplyAttemptAt: Long = 0L,
        val lastApplyAttemptEpoch: String = "",
        val lastApplyAttemptGeneration: Long = 0L,
        val lastApplyGeneration: Long = 0L,
        val lastApplyCount: Int = 0,
        val lastApplyError: String = "",
        val applicationState: String = "PENDING",
        val redirectPolicyState: String = "PENDING",
        val readOnlyPolicyState: String = "PENDING",
        val appliedRedirectRevision: String = "",
        val appliedReadOnlyRevision: String = "",
        val fuseLibraryLoaded: Boolean = false,
        val fuseLibraryName: String = "",
        val hookMode: String = "UNKNOWN",
        val fuseJniLoadMode: String = "UNKNOWN",
        val embeddedFuseJniFound: Boolean = false,
        val containsMountHooked: Boolean = false,
        val startsWithHooked: Boolean = false,
        val isFuseBpfEnabledHooked: Boolean = false,
        val fuseReqUserdataHooked: Boolean = false,
        val fillEntriesHooked: Boolean = false,
        val installHooked: Boolean = false,
        val effectiveHooked: Boolean = false,
        val missingSymbols: String = "",
        val nativeLastError: String = "",
        val fuseJavaGateDiscoveredCount: Int = 0,
        val fuseJavaGateHookedCount: Int = 0,
        val fuseJavaGateUnknownCount: Int = 0,
        val fuseJavaGateFailedCount: Int = 0,
    val createdAt: Long = 0L,
    val freshness: HookPolicyFreshnessSummary = HookPolicyFreshnessSummary(),
    val fuseJavaGate: FuseJavaGateCoverageSummary = FuseJavaGateCoverageSummary.UNKNOWN,
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
                        effectiveHooked -> "FULL"
                containsMountHooked && startsWithHooked -> "CORE"
                containsMountHooked -> "DEGRADED"
                else -> "UNAVAILABLE"
            }
    }
}
