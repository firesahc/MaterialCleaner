package me.gm.cleaner.runtime.server.orchestrator

data class RuntimeRecoverySnapshot(
    val hook: HookRecoveryCoordinator.RecoverySnapshot =
        HookRecoveryCoordinator.RecoverySnapshot(),
    val mediaProvider: MediaProviderRecoveryStrategy.RecoverySnapshot =
        MediaProviderRecoveryStrategy.RecoverySnapshot(),
)
