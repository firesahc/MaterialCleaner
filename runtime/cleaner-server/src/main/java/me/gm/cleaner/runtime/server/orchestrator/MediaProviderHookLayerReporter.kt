package me.gm.cleaner.runtime.server.orchestrator

object MediaProviderHookLayerReporter {

    fun collect(
        generation: Long,
        now: Long,
        hooksBridgeConnected: Boolean,
        mediaProviderHookConnected: Boolean,
        recovery: RuntimeRecoverySnapshot,
    ): LayerReport {
        val mediaRecovery = recovery.mediaProvider
        val hookRecovery = recovery.hook
        val recovering = hookRecovery.hooksReconnectScheduled ||
                mediaRecovery.mediaProviderWakeScheduled ||
                mediaRecovery.recoveryCooldownRemainingMs > 0L
        val state = when {
            mediaProviderHookConnected -> LayerState.HEALTHY
            recovering -> LayerState.RECOVERING
            else -> LayerState.UNAVAILABLE
        }
        return LayerReport(
            id = LayerId.MEDIA_PROVIDER_JAVA_HOOK,
            state = state,
            generation = generation,
            lastHeartbeatAt = if (mediaProviderHookConnected) now else 0L,
            lastErrorAt = if (mediaProviderHookConnected) 0L else now,
            lastError = when {
                mediaProviderHookConnected -> null
                hookRecovery.hooksReconnectScheduled -> "Hook bridge reconnect scheduled"
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
            ),
        )
    }
}
