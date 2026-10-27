package me.gm.cleaner.runtime.server.orchestrator

object MediaProviderHookLayerReporter {

    // ── 指标键契约（DiagnosticArchive 等消费方必须引用此处，不得另写字面量） ──
    const val KEY_WAKE_ONLY_MODE = "mediaProviderWakeOnlyMode"
    const val KEY_DESTRUCTIVE_ROUNDS = "mediaProviderDestructiveRounds"

    const val KEY_FUSE_JAVA_GATE_SCAN_COMPLETE = "fuseJavaGateScanComplete"
    const val KEY_FUSE_JAVA_GATE_COVERAGE_STATE = "fuseJavaGateCoverageState"
    const val KEY_FUSE_JAVA_GATE_CRITICAL_MISSING = "fuseJavaGateCriticalMissingMethods"
    const val KEY_FUSE_JAVA_GATE_AUXILIARY_UNCOVERED =
        "fuseJavaGateAuxiliaryLookupOpenUncoveredMethods"

    internal fun collect(
        generation: Long,
        now: Long,
        hooksBridgeConnected: Boolean,
        mediaProviderHookConnected: Boolean,
        recovery: RuntimeRecoverySnapshot,
        coverage: FuseJavaGateCoverageSummary = FuseJavaGateCoverageSummary.UNKNOWN,
        freshness: HookPolicyFreshnessSummary = HookPolicyFreshnessSummary(),
    ): LayerReport {
        val mediaRecovery = recovery.mediaProvider
        val hookRecovery = recovery.hook
        val recoveryInProgress = hookRecovery.hooksReconnectScheduled ||
                mediaRecovery.mediaProviderWakeScheduled ||
                mediaRecovery.recoveryCooldownRemainingMs > 0L
        val state = MediaProviderHookHealthPolicy.evaluate(
            mediaProviderHookConnected = mediaProviderHookConnected,
            recoveryInProgress = recoveryInProgress,
            coverage = coverage,
            freshness = freshness,
        )
        return LayerReport(
            id = LayerId.MEDIA_PROVIDER_JAVA_HOOK,
            state = state,
            generation = generation,
            lastHeartbeatAt = if (mediaProviderHookConnected &&
                state != LayerState.RECOVERING
            ) now else 0L,
            lastErrorAt = if (state == LayerState.HEALTHY) 0L else now,
            lastError = when {
                state == LayerState.HEALTHY -> null
                mediaProviderHookConnected && !coverage.scanComplete ->
                    "FUSE Java Hook scan incomplete"
                mediaProviderHookConnected && coverage.criticalUncoveredMethods.isNotEmpty() ->
                    "Required FUSE Java Hook coverage incomplete: " +
                            coverage.criticalUncoveredMethods.joinToString(",")
                mediaProviderHookConnected && freshness.redirect != PolicyFreshnessState.CURRENT ->
                    "Redirect policy freshness ${freshness.redirect}"
                mediaProviderHookConnected && freshness.readOnly != PolicyFreshnessState.CURRENT ->
                    "Read-only policy freshness ${freshness.readOnly}"
                mediaProviderHookConnected &&
                        freshness.platformCapabilities != PolicyFreshnessState.CURRENT ->
                    "Platform capabilities freshness ${freshness.platformCapabilities}"
                hookRecovery.hooksReconnectScheduled -> "Hook bridge reconnect scheduled"
                mediaRecovery.wakeOnlyMode -> "MediaProvider hook missing, wake-only (recovery budget exhausted)"
                mediaRecovery.mediaProviderWakeScheduled -> "MediaProvider wake scheduled"
                mediaRecovery.recoveryCooldownRemainingMs > 0L ->
                    "MediaProvider recovery cooldown active"
                hooksBridgeConnected -> "MediaProvider Hook unavailable"
                else -> "Hook bridge Binder unavailable"
            },
            metrics = mapOf(
                "binderConnected" to hooksBridgeConnected.toString(),
                "mediaProviderHookConnected" to mediaProviderHookConnected.toString(),
                "hooksRetryCount" to hookRecovery.hooksRetryCount.toString(),
                "maxHookRetries" to hookRecovery.maxHookRetries.toString(),
                "hooksReconnectScheduled" to hookRecovery.hooksReconnectScheduled.toString(),
                "consecutiveMediaProviderHookMissing" to
                        mediaRecovery.consecutiveHookMissing.toString(),
                "lastMediaProviderRecoveryAt" to mediaRecovery.lastRecoveryAt.toString(),
                "mediaProviderRecoveryCooldownRemainingMs" to
                        mediaRecovery.recoveryCooldownRemainingMs.toString(),
                "mediaProviderWakeScheduled" to
                        mediaRecovery.mediaProviderWakeScheduled.toString(),
                "mediaProviderEpisodeStartMs" to mediaRecovery.episodeStartMs.toString(),
                KEY_DESTRUCTIVE_ROUNDS to mediaRecovery.destructiveRounds.toString(),
                KEY_WAKE_ONLY_MODE to mediaRecovery.wakeOnlyMode.toString(),
                KEY_FUSE_JAVA_GATE_SCAN_COMPLETE to coverage.scanComplete.toString(),
                KEY_FUSE_JAVA_GATE_COVERAGE_STATE to coverage.coverageState.name,
                "fuseJavaGateDiscoveredCount" to coverage.discoveredCount.toString(),
                "fuseJavaGateHookedCount" to coverage.hookedMethods.size.toString(),
                "fuseJavaGateUnknownCount" to coverage.unknownMethods.size.toString(),
                "fuseJavaGateUidSkippedCount" to coverage.uidSkippedMethods.size.toString(),
                "fuseJavaGateFailedCount" to coverage.failedMethods.size.toString(),
                KEY_FUSE_JAVA_GATE_CRITICAL_MISSING to
                        coverage.criticalMissingMethods.joinToString(","),
                "fuseJavaGateCriticalUidSkippedMethods" to
                        coverage.criticalUidSkippedMethods.map { it.methodName() }
                            .distinct().joinToString(","),
                "fuseJavaGateCriticalFailedMethods" to
                        coverage.criticalFailedMethods.map { it.methodName() }
                            .distinct().joinToString(","),
                "policyRedirectFreshnessState" to freshness.redirect.name,
                "policyReadOnlyFreshnessState" to freshness.readOnly.name,
                "policyPlatformCapabilitiesFreshnessState" to
                        freshness.platformCapabilities.name,
                "policyCacheDomainFailureCount" to freshness.domainFailures.size.toString(),
                "policyCacheDomainFailures" to org.json.JSONObject(freshness.domainFailures).toString(),
                "fuseJavaGateAuxiliaryLookupOpenMethods" to
                        coverage.auxiliaryLookupOpenMethods.joinToString(","),
                "fuseJavaGateAuxiliaryLookupOpenHookedCount" to
                        coverage.auxiliaryLookupOpenHookedCount.toString(),
                KEY_FUSE_JAVA_GATE_AUXILIARY_UNCOVERED to
                        coverage.auxiliaryLookupOpenUncoveredMethods.joinToString(","),
            ),
        )
    }
}

internal object MediaProviderHookHealthPolicy {
    fun evaluate(
        mediaProviderHookConnected: Boolean,
        recoveryInProgress: Boolean,
        coverage: FuseJavaGateCoverageSummary,
        freshness: HookPolicyFreshnessSummary,
    ): LayerState = when {
        !mediaProviderHookConnected && recoveryInProgress -> LayerState.RECOVERING
        !mediaProviderHookConnected -> LayerState.UNAVAILABLE
        !coverage.scanComplete -> LayerState.RECOVERING
        coverage.criticalUncoveredMethods.isNotEmpty() -> LayerState.DEGRADED
        freshness.redirect != PolicyFreshnessState.CURRENT -> LayerState.DEGRADED
        freshness.readOnly != PolicyFreshnessState.CURRENT -> LayerState.DEGRADED
        freshness.platformCapabilities != PolicyFreshnessState.CURRENT -> LayerState.DEGRADED
        else -> LayerState.HEALTHY
    }
}

private fun String.methodName(): String = trim().substringBefore(' ').substringBefore('(')
