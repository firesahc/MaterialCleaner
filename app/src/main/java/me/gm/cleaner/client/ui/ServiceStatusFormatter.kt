package me.gm.cleaner.client.ui

import me.gm.cleaner.R
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.OrchestratedRuntimeStatus
import me.gm.cleaner.client.ServerState

/**
 * 状态卡文案解析器：所有函数均为纯函数。
 *
 * 约束：
 * - 不持有 Context / View / Fragment / lifecycle，JVM 单测可直接调用；
 * - 原 `ctx.getString` 一律经由 [StatusStrings] 注入；
 * - 原 `Shell.isRoot` / `srPackages.size` 由调用方求值后以 [rootAvailable] /
 *   [configured] 等值参数传入，本文件不直接读取。
 *
 * 与 DiagnosticArchiveUtils 的 fallback 文案已比对：诊断包面向导出取证
 * （硬编码中英文 + Binder 直读），状态卡面向 UI 展示（R.string 三语言），
 * 两者职责不同，不复用、不合并。
 */
fun interface StatusStrings {
    fun get(id: Int, vararg args: Any?): String
}

/** fallback 行：UI 展示用摘要 + 伪层状态（复用 [ServiceStatusFormatter.layerColorRes]）。 */
data class StatusFallbackRow(
    val summary: String,
    val state: String,
)

object ServiceStatusFormatter {

    fun layerColorRes(state: String): Int = when (state) {
        "HEALTHY" -> android.R.color.holo_green_dark
        "DEGRADED", "RECOVERING", "STALE", "STARTING" -> android.R.color.holo_orange_dark
        "UNINITIALIZED", "DISABLED" -> android.R.color.darker_gray
        else -> android.R.color.holo_red_dark
    }

    fun stateLabel(s: StatusStrings, state: String): String = when (state) {
        "HEALTHY" -> s.get(R.string.runtime_state_healthy)
        "DEGRADED" -> s.get(R.string.runtime_state_degraded)
        "STALE" -> s.get(R.string.runtime_state_stale)
        "UNAVAILABLE" -> s.get(R.string.runtime_state_unavailable)
        "RECOVERING" -> s.get(R.string.runtime_state_recovering)
        "STARTING" -> s.get(R.string.runtime_state_starting)
        "UNINITIALIZED" -> s.get(R.string.runtime_state_uninitialized)
        "DISABLED" -> s.get(R.string.runtime_state_disabled)
        else -> state
    }

    fun statusIconRes(health: String): Pair<Int, Int> = when (health) {
        "HEALTHY" -> R.drawable.ic_baseline_check_circle_24 to android.R.color.holo_green_dark
        "DEGRADED" -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
        else -> R.drawable.ic_baseline_error_24 to android.R.color.holo_red_dark
    }

    /** 原 setLayerRow 的文本拼接部分；View 绑定留在 StatusCardController。 */
    fun rowText(name: String, stateText: String, summary: String): String = buildString {
        append(name)
        append(" · ")
        append(stateText)
        if (summary.isNotBlank()) {
            append(" · ")
            append(summary)
        }
    }

    fun topSummary(
        s: StatusStrings,
        status: OrchestratedRuntimeStatus,
        configured: Int,
    ): String {
        val base = when (status.health) {
            "HEALTHY" -> if (configured > 0) {
                s.get(R.string.service_status_summary_healthy)
            } else {
                s.get(R.string.service_status_summary_ready_no_rules)
            }
            "DEGRADED" -> s.get(R.string.service_status_summary_degraded)
            else -> s.get(R.string.service_status_summary_unavailable)
        }
        return "$base · ${vfsSummary(s, status.vfs, configured)}"
    }

    fun vfsSummary(
        s: StatusStrings,
        layer: OrchestratedLayerStatus,
        configured: Int,
    ): String {
        if (configured == 0) return s.get(R.string.storage_redirect_no_mount_rules)
        val mountedPackages = layer.metrics["mountedPackages"]?.toIntOrNull() ?: 0
        val recordedPids = layer.metrics["recordedPids"]?.toIntOrNull() ?: 0
        val failedPids = layer.metrics["mountFailedPids"]?.toIntOrNull() ?: 0
        val attempts = layer.metrics["mountTotalAttempts"]?.toIntOrNull() ?: 0
        val failures = layer.metrics["mountFailureCount"]?.toIntOrNull() ?: 0
        val parts = mutableListOf(s.get(R.string.service_status_configured_apps, configured))
        if (mountedPackages > 0) {
            parts += s.get(R.string.service_status_mounted_packages, mountedPackages)
        }
        if (recordedPids > 0) {
            parts += s.get(R.string.service_status_recorded_processes, recordedPids)
        }
        if (failedPids > 0 || failures > 0) {
            parts += s.get(R.string.service_status_exceptions, maxOf(failedPids, failures))
        }
        // 统一错误码下钻：让用户与支持人员无需导出诊断包即可定位故障类别。
        layer.metrics["topErrorCode"]?.takeIf { it.isNotBlank() }?.let { code ->
            parts += s.get(R.string.service_status_top_error_code, code)
        }
        if (attempts > 0) {
            parts += s.get(R.string.service_status_attempts, attempts)
        }
        return parts.joinToString(" · ")
    }

    fun hookSummary(s: StatusStrings, layer: OrchestratedLayerStatus): String {
        val connected = layer.metrics["binderConnected"]?.toBooleanStrictOrNull()
        val mediaProviderConnected = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        val reconnectScheduled = layer.metrics["hooksReconnectScheduled"]?.toBooleanStrictOrNull()
        val wakeScheduled = layer.metrics["mediaProviderWakeScheduled"]?.toBooleanStrictOrNull()
        val missingChecks = layer.metrics["consecutiveMediaProviderHookMissing"]?.toIntOrNull() ?: 0
        val cooldownMs = layer.metrics["mediaProviderRecoveryCooldownRemainingMs"]?.toLongOrNull() ?: 0L
        val retryCount = layer.metrics["hooksRetryCount"]?.toIntOrNull() ?: 0
        val maxRetries = layer.metrics["maxHookRetries"]?.toIntOrNull() ?: 0
        val retryLabel = if (maxRetries > 0) {
            s.get(R.string.service_status_retry_with_max, retryCount + 1, maxRetries)
        } else {
            s.get(R.string.service_status_retry, retryCount + 1)
        }
        return when (connected) {
            true -> if (mediaProviderConnected == true) {
                s.get(R.string.hook_connected)
            } else if (wakeScheduled == true) {
                s.get(R.string.hook_waking_media_provider)
            } else if (cooldownMs > 0L) {
                s.get(R.string.hook_recovery_cooldown_remaining, formatDurationSeconds(cooldownMs))
            } else if (missingChecks > 0) {
                s.get(R.string.hook_bridge_connected_missing, missingChecks)
            } else {
                s.get(R.string.hook_bridge_waiting)
            }
            false -> if (reconnectScheduled == true) {
                s.get(R.string.hook_bridge_reconnecting, retryLabel)
            } else {
                s.get(R.string.hook_binder_disconnected)
            }
            null -> ""
        }
    }

    fun nativeSummary(s: StatusStrings, layer: OrchestratedLayerStatus): String {
        val nativeGen = layer.metrics["configuredMountPointsGeneration"]
        val snapshotGen = layer.metrics["snapshotConfiguredMountPointsGeneration"]
        val inlineLoaded = layer.metrics["inlineLibraryLoaded"]?.toBooleanStrictOrNull()
        val fuseLoaded = layer.metrics["fuseLibraryLoaded"]?.toBooleanStrictOrNull()
        val hookMode = layer.metrics["hookMode"].orEmpty()
        val embeddedFound = layer.metrics["embeddedFuseJniFound"]?.toBooleanStrictOrNull()
        val containsMount = layer.metrics["containsMountHooked"]?.toBooleanStrictOrNull()
        val startsWith = layer.metrics["startsWithHooked"]?.toBooleanStrictOrNull()
        val bpf = layer.metrics["isFuseBpfEnabledHooked"]?.toBooleanStrictOrNull()
        val applySuccess = layer.metrics["lastMountPointsApplySuccess"]?.toBooleanStrictOrNull()
        val modeLabel = when (hookMode) {
            "EMBEDDED_GOT_PATCH" -> s.get(R.string.native_mode_embedded_hook)
            "XHOOK" -> "xhook"
            else -> ""
        }
        fun generationSummary(prefix: String): String {
            val label = listOf(prefix, modeLabel).filter { it.isNotBlank() }.joinToString(" · ")
            return if (label.isBlank()) {
                s.get(R.string.native_mount_points_generation, nativeGen, snapshotGen)
            } else {
                s.get(R.string.native_mount_points_generation_with_label, label, nativeGen, snapshotGen)
            }
        }
        return when {
            inlineLoaded == false -> s.get(R.string.native_libinline_not_loaded)
            fuseLoaded == false && inlineLoaded == true -> s.get(R.string.native_fuse_library_not_loaded)
            hookMode == "EMBEDDED_GOT_PATCH" && embeddedFound == false ->
                s.get(R.string.native_embedded_library_missing)
            hookMode == "EMBEDDED_GOT_PATCH" && containsMount == false ->
                s.get(R.string.native_embedded_contains_mount_not_hooked)
            hookMode != "EMBEDDED_GOT_PATCH" &&
                (containsMount == false || startsWith == false || bpf == false) ->
                s.get(R.string.native_symbols_missing)
            applySuccess == false && nativeGen != null && nativeGen != "0" ->
                s.get(R.string.native_mount_points_push_failed, nativeGen, snapshotGen)
            nativeGen != null && snapshotGen != null -> generationSummary("")
            nativeGen != null -> s.get(R.string.native_generation, nativeGen)
            else -> ""
        }
    }

    fun dataBusSummary(s: StatusStrings, layer: OrchestratedLayerStatus): String {
        val labels = listOf(
            "snapshotRedirectPolicy" to s.get(R.string.databus_snapshot_rules),
            "snapshotReadOnly" to s.get(R.string.databus_snapshot_read_only),
            "snapshotConfiguredMountPoints" to s.get(R.string.databus_snapshot_mount_points),
            "snapshotPlatformCapabilities" to s.get(R.string.databus_snapshot_platform),
        )
        val missing = labels
            .filter { (key, _) -> layer.metrics[key] == "missing" }
            .map { (_, label) -> label }
        if (missing.isNotEmpty()) {
            return s.get(R.string.databus_missing_snapshots, missing.joinToString("、"))
        }
        val hookMode = when (layer.metrics["platformSupportedNativeHookMode"]) {
            "EMBEDDED_GOT_PATCH" -> s.get(R.string.databus_hook_mode_embedded)
            "XHOOK" -> s.get(R.string.databus_hook_mode_system)
            "NONE" -> s.get(R.string.databus_hook_mode_none)
            else -> ""
        }
        return listOf(s.get(R.string.databus_snapshots_ready), hookMode)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    fun controlPlaneSummary(s: StatusStrings, layer: OrchestratedLayerStatus): String {
        val appBinder = layer.metrics["appBinderRegistered"]?.toBooleanStrictOrNull()
        val hooksBridge = layer.metrics["hooksBridgeConnected"]?.toBooleanStrictOrNull()
        val mediaProviderHook = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        val reconnectScheduled = layer.metrics["hooksReconnectScheduled"]?.toBooleanStrictOrNull()
        val wakeScheduled = layer.metrics["mediaProviderWakeScheduled"]?.toBooleanStrictOrNull()
        val cooldownMs = layer.metrics["mediaProviderRecoveryCooldownRemainingMs"]?.toLongOrNull() ?: 0L
        val parts = mutableListOf<String>()
        if (appBinder != null) {
            parts += if (appBinder) {
                s.get(R.string.control_app_binder_registered)
            } else {
                s.get(R.string.control_app_binder_unregistered)
            }
        }
        if (hooksBridge != null) {
            parts += if (hooksBridge) {
                s.get(R.string.control_hook_bridge_connected)
            } else {
                s.get(R.string.control_hook_bridge_disconnected)
            }
        }
        if (mediaProviderHook != null) {
            parts += if (mediaProviderHook) {
                s.get(R.string.control_media_provider_registered)
            } else {
                s.get(R.string.control_media_provider_unregistered)
            }
        }
        if (reconnectScheduled == true) parts += s.get(R.string.control_reconnect_scheduled)
        if (wakeScheduled == true) parts += s.get(R.string.control_wake_scheduled)
        if (cooldownMs > 0L) parts += s.get(R.string.control_cooldown, formatDurationSeconds(cooldownMs))
        return parts.joinToString(" · ")
    }

    fun formatDurationSeconds(durationMs: Long): String {
        val seconds = ((durationMs + 999L) / 1000L).coerceAtLeast(1L)
        return "${seconds}s"
    }

    /**
     * 首个问题行：调用方按展示顺序传入（层名 → 层状态），层名解析留在调用方。
     */
    fun firstProblem(
        s: StatusStrings,
        layers: List<Pair<String, OrchestratedLayerStatus>>,
    ): String? {
        val problem = layers.firstOrNull { (_, layer) ->
            layer.state !in setOf("HEALTHY", "DEGRADED")
        } ?: layers.firstOrNull { (_, layer) ->
            layer.state == "DEGRADED" || layer.lastError != null
        }
        return problem?.let { (name, layer) ->
            val detail = layer.lastError ?: stateLabel(s, layer.state)
            s.get(R.string.service_status_problem_detail, name, detail)
        }
    }

    // ── 正常态（orchestrated 可用）标题 ────────────────────────────

    fun orchestratedTitle(s: StatusStrings, health: String, configured: Int): String = when (health) {
        "HEALTHY" -> if (configured > 0) {
            s.get(R.string.service_status_healthy_configured_title)
        } else {
            s.get(R.string.service_status_ready_title)
        }
        "DEGRADED" -> s.get(R.string.service_status_degraded_title)
        else -> s.get(R.string.service_status_unavailable_title)
    }

    fun toggleLabel(s: StatusStrings, serverState: ServerState): String = s.get(
        if (serverState == ServerState.RUNNING || serverState == ServerState.STARTING) {
            R.string.btn_stop_service
        } else {
            R.string.btn_start_service
        }
    )

    // ── 手动停止覆盖 ───────────────────────────────────────────────

    fun stoppedTitle(s: StatusStrings): String =
        s.get(R.string.service_status_stopped_title)

    fun stoppedSubtitle(s: StatusStrings, configured: Int): String = if (configured > 0) {
        s.get(R.string.service_status_stopped_configured, configured)
    } else {
        s.get(R.string.service_stopped_manually)
    }

    // ── fallback（orchestrated 不可用）─────────────────────────────

    fun fallbackTitleRes(serverState: ServerState): Int = when (serverState) {
        ServerState.STARTING -> R.string.service_status_starting_title
        ServerState.RUNNING -> R.string.service_status_reading_title
        ServerState.FAILED -> R.string.service_status_start_failed_title
        else -> R.string.service_status_not_running_title
    }

    fun fallbackSubtitle(s: StatusStrings, serverState: ServerState, configured: Int): String = when {
        serverState == ServerState.RUNNING -> s.get(R.string.service_status_daemon_connected_reading)
        configured == 0 -> s.get(R.string.storage_redirect_no_mount_rules)
        else -> s.get(R.string.service_status_configured_waiting, configured)
    }

    /**
     * fallback 五层行，与 UI 展示顺序一致：
     * VFS / MediaProvider Hook / FUSE Native / DataBus / 控制面。
     */
    fun fallbackRows(
        s: StatusStrings,
        serverState: ServerState,
        rootAvailable: Boolean,
        xposedConnected: Boolean,
    ): List<StatusFallbackRow> = listOf(
        StatusFallbackRow(
            summary = when {
                serverState == ServerState.RUNNING && rootAvailable ->
                    s.get(R.string.service_status_wait_orchestrator)
                serverState == ServerState.RUNNING ->
                    s.get(R.string.service_status_root_unavailable)
                serverState == ServerState.STARTING ->
                    s.get(R.string.service_status_starting_short)
                serverState == ServerState.FAILED ->
                    s.get(R.string.service_status_start_failed_short)
                else -> s.get(R.string.service_status_not_running_short)
            },
            state = if (serverState == ServerState.RUNNING && rootAvailable) "STARTING" else "UNAVAILABLE",
        ),
        StatusFallbackRow(
            summary = if (!xposedConnected) {
                s.get(R.string.service_status_wait_media_provider_hook)
            } else {
                s.get(R.string.service_status_wait_orchestrator)
            },
            state = if (xposedConnected) "STARTING" else "UNAVAILABLE",
        ),
        StatusFallbackRow(
            summary = if (!xposedConnected) {
                s.get(R.string.service_status_wait_native_hook)
            } else {
                s.get(R.string.service_status_wait_orchestrator)
            },
            state = if (xposedConnected) "STARTING" else "UNAVAILABLE",
        ),
        StatusFallbackRow(
            summary = s.get(R.string.service_status_wait_snapshot),
            state = "STARTING",
        ),
        StatusFallbackRow(
            summary = if (serverState == ServerState.RUNNING) {
                s.get(R.string.service_status_app_binder_connected)
            } else {
                s.get(R.string.service_status_disconnected)
            },
            state = if (serverState == ServerState.RUNNING) "HEALTHY" else "UNAVAILABLE",
        ),
    )

    fun fallbackCause(
        s: StatusStrings,
        serverState: ServerState,
        rootAvailable: Boolean,
        xposedConnected: Boolean,
    ): String? = when {
        serverState == ServerState.FAILED ->
            s.get(R.string.service_status_problem_daemon_failed)
        serverState == ServerState.RUNNING && !rootAvailable ->
            s.get(R.string.service_status_problem_root_unavailable)
        serverState == ServerState.RUNNING && !xposedConnected ->
            s.get(R.string.service_status_hint_media_provider_hook_unavailable)
        else -> null
    }

    fun fallbackDotRes(serverState: ServerState): Pair<Int, Int> = when (serverState) {
        ServerState.RUNNING -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
        ServerState.STARTING -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
        else -> R.drawable.ic_baseline_error_24 to android.R.color.holo_red_dark
    }
}
