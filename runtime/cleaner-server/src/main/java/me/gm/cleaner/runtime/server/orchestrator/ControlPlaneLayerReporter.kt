package me.gm.cleaner.runtime.server.orchestrator

import me.gm.cleaner.runtime.server.CleanerServer

object ControlPlaneLayerReporter {

    fun collect(
        server: CleanerServer,
        generation: Long,
        now: Long,
        hooksBridgeConnected: Boolean,
        mediaProviderHookConnected: Boolean,
        recovery: RuntimeRecoverySnapshot,
    ): LayerReport {
        val hookRecovery = recovery.hook
        val mediaRecovery = recovery.mediaProvider
        val appBinderRegistered = server.cleanerService != null
        val state = when {
            hooksBridgeConnected -> LayerState.HEALTHY
            hookRecovery.hooksReconnectScheduled || mediaRecovery.mediaProviderWakeScheduled ->
                LayerState.RECOVERING
            else -> LayerState.DEGRADED
        }
        return LayerReport(
            id = LayerId.CONTROL_PLANE,
            state = state,
            generation = generation,
            lastHeartbeatAt = now,
            lastErrorAt = if (state == LayerState.HEALTHY) 0L else now,
            lastError = when {
                state == LayerState.HEALTHY -> null
                hookRecovery.hooksReconnectScheduled -> "Hook bridge reconnect scheduled"
                mediaRecovery.mediaProviderWakeScheduled -> "MediaProvider wake scheduled"
                else -> "Hook bridge unavailable"
            },
            metrics = mapOf(
                "appBinderRegistered" to appBinderRegistered.toString(),
                "hooksBridgeConnected" to hooksBridgeConnected.toString(),
                "mediaProviderHookConnected" to mediaProviderHookConnected.toString(),
                "hooksRetryCount" to hookRecovery.hooksRetryCount.toString(),
                "maxHookRetries" to hookRecovery.maxHookRetries.toString(),
                "hooksReconnectScheduled" to hookRecovery.hooksReconnectScheduled.toString(),
                "lastNativeHookCheckGeneration" to
                        hookRecovery.lastNativeHookCheckGeneration.toString(),
                "mediaProviderWakeScheduled" to
                        mediaRecovery.mediaProviderWakeScheduled.toString(),
                "mediaProviderRecoveryCooldownRemainingMs" to
                        mediaRecovery.recoveryCooldownRemainingMs.toString(),
            ),
        )
    }
}
