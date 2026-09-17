package me.gm.cleaner.client.ui

import java.util.Locale
import me.gm.cleaner.R
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ServiceStatusFormatter 纯函数单测：Fake StatusStrings 注入，不依赖 Android 运行时。
 */
class ServiceStatusFormatterTest {

    private val fake = StatusStrings { id, args ->
        val template = TEMPLATES[id] ?: "s$id"
        if (args.isEmpty()) template else String.format(Locale.US, template, *args)
    }

    @Test
    fun `layerColorRes 覆盖全部状态分支`() {
        assertEquals(android.R.color.holo_green_dark, ServiceStatusFormatter.layerColorRes("HEALTHY"))
        assertEquals(android.R.color.holo_orange_dark, ServiceStatusFormatter.layerColorRes("DEGRADED"))
        assertEquals(android.R.color.holo_orange_dark, ServiceStatusFormatter.layerColorRes("RECOVERING"))
        assertEquals(android.R.color.holo_orange_dark, ServiceStatusFormatter.layerColorRes("STALE"))
        assertEquals(android.R.color.holo_orange_dark, ServiceStatusFormatter.layerColorRes("STARTING"))
        assertEquals(android.R.color.darker_gray, ServiceStatusFormatter.layerColorRes("UNINITIALIZED"))
        assertEquals(android.R.color.darker_gray, ServiceStatusFormatter.layerColorRes("DISABLED"))
        assertEquals(android.R.color.holo_red_dark, ServiceStatusFormatter.layerColorRes("UNAVAILABLE"))
        assertEquals(android.R.color.holo_red_dark, ServiceStatusFormatter.layerColorRes("CRITICAL"))
    }

    @Test
    fun `stateLabel 已知态走文案_未知态原样回退`() {
        assertEquals("healthy", ServiceStatusFormatter.stateLabel(fake, "HEALTHY"))
        assertEquals("starting", ServiceStatusFormatter.stateLabel(fake, "STARTING"))
        assertEquals("SOME_FUTURE_STATE", ServiceStatusFormatter.stateLabel(fake, "SOME_FUTURE_STATE"))
    }

    @Test
    fun `statusIconRes 三分支`() {
        assertEquals(
            R.drawable.ic_baseline_check_circle_24 to android.R.color.holo_green_dark,
            ServiceStatusFormatter.statusIconRes("HEALTHY")
        )
        assertEquals(
            R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark,
            ServiceStatusFormatter.statusIconRes("DEGRADED")
        )
        assertEquals(
            R.drawable.ic_baseline_error_24 to android.R.color.holo_red_dark,
            ServiceStatusFormatter.statusIconRes("CRITICAL")
        )
    }

    @Test
    fun `rowText 空摘要不挂尾部分隔符`() {
        assertEquals("VFS · healthy", ServiceStatusFormatter.rowText("VFS", "healthy", ""))
        assertEquals("VFS · healthy · mounted=1", ServiceStatusFormatter.rowText("VFS", "healthy", "mounted=1"))
    }

    @Test
    fun `formatDurationSeconds 向上取整且至少1s`() {
        assertEquals("1s", ServiceStatusFormatter.formatDurationSeconds(0L))
        assertEquals("1s", ServiceStatusFormatter.formatDurationSeconds(1000L))
        assertEquals("2s", ServiceStatusFormatter.formatDurationSeconds(1001L))
        assertEquals("6s", ServiceStatusFormatter.formatDurationSeconds(5500L))
    }

    @Test
    fun `vfsSummary 无规则直接返回空规则文案`() {
        val out = ServiceStatusFormatter.vfsSummary(fake, OrchestratedLayerStatus(), configured = 0)
        assertEquals("no_rules", out)
    }

    @Test
    fun `vfsSummary 组合挂载数_进程数_异常数_topErrorCode`() {
        val layer = OrchestratedLayerStatus(
            state = "HEALTHY",
            metrics = mapOf(
                "mountedPackages" to "3",
                "recordedPids" to "5",
                "mountFailedPids" to "1",
                "mountFailureCount" to "2",
                "mountTotalAttempts" to "10",
                "topErrorCode" to "MOUNT.SETNS.OPEN_FAILED",
            ),
        )
        val out = ServiceStatusFormatter.vfsSummary(fake, layer, configured = 4)
        assertEquals(
            "configured=4 · mounted=3 · pids=5 · exceptions=2 · topError=MOUNT.SETNS.OPEN_FAILED · attempts=10",
            out
        )
    }

    @Test
    fun `hookSummary 已连接且MediaProvider在线`() {
        val layer = OrchestratedLayerStatus(
            metrics = mapOf("binderConnected" to "true", "mediaProviderHookConnected" to "true")
        )
        assertEquals("hook_connected", ServiceStatusFormatter.hookSummary(fake, layer))
    }

    @Test
    fun `hookSummary 唤醒与冷却分支`() {
        val waking = OrchestratedLayerStatus(
            metrics = mapOf("binderConnected" to "true", "mediaProviderWakeScheduled" to "true")
        )
        assertEquals("hook_waking", ServiceStatusFormatter.hookSummary(fake, waking))

        val cooldown = OrchestratedLayerStatus(
            metrics = mapOf(
                "binderConnected" to "true",
                "mediaProviderRecoveryCooldownRemainingMs" to "5500",
            )
        )
        assertEquals("hook_cooldown_6s", ServiceStatusFormatter.hookSummary(fake, cooldown))
    }

    @Test
    fun `hookSummary 断开与未知`() {
        val reconnecting = OrchestratedLayerStatus(
            metrics = mapOf(
                "binderConnected" to "false",
                "hooksReconnectScheduled" to "true",
                "hooksRetryCount" to "2",
                "maxHookRetries" to "5",
            )
        )
        assertEquals("hook_reconnecting_retry 3/5", ServiceStatusFormatter.hookSummary(fake, reconnecting))

        val disconnected = OrchestratedLayerStatus(metrics = mapOf("binderConnected" to "false"))
        assertEquals("hook_binder_disconnected", ServiceStatusFormatter.hookSummary(fake, disconnected))

        assertEquals("", ServiceStatusFormatter.hookSummary(fake, OrchestratedLayerStatus()))
    }

    @Test
    fun `nativeSummary 按优先级返回首个故障`() {
        assertEquals(
            "libinline_not_loaded",
            ServiceStatusFormatter.nativeSummary(fake, OrchestratedLayerStatus(metrics = mapOf("inlineLibraryLoaded" to "false")))
        )
        val gen = OrchestratedLayerStatus(
            metrics = mapOf(
                "inlineLibraryLoaded" to "true",
                "fuseLibraryLoaded" to "true",
                "hookMode" to "XHOOK",
                "containsMountHooked" to "true",
                "startsWithHooked" to "true",
                "isFuseBpfEnabledHooked" to "true",
                "configuredMountPointsGeneration" to "7",
                "snapshotConfiguredMountPointsGeneration" to "7",
            )
        )
        assertEquals(
            "gen_xhook_7_7",
            ServiceStatusFormatter.nativeSummary(fake, gen)
        )
        assertEquals("", ServiceStatusFormatter.nativeSummary(fake, OrchestratedLayerStatus()))
    }

    @Test
    fun `dataBusSummary 缺失快照优先`() {
        val missing = OrchestratedLayerStatus(
            metrics = mapOf("snapshotRedirectPolicy" to "missing", "snapshotReadOnly" to "ok")
        )
        assertEquals("databus_missing_rules", ServiceStatusFormatter.dataBusSummary(fake, missing))

        val ready = OrchestratedLayerStatus(
            metrics = mapOf("platformSupportedNativeHookMode" to "XHOOK")
        )
        assertEquals("databus_ready · hook_system", ServiceStatusFormatter.dataBusSummary(fake, ready))
    }

    @Test
    fun `controlPlaneSummary 空metrics返回空串_组合多段`() {
        assertEquals("", ServiceStatusFormatter.controlPlaneSummary(fake, OrchestratedLayerStatus()))
        val layer = OrchestratedLayerStatus(
            metrics = mapOf(
                "appBinderRegistered" to "true",
                "hooksBridgeConnected" to "false",
                "mediaProviderHookConnected" to "true",
                "hooksReconnectScheduled" to "true",
                "mediaProviderRecoveryCooldownRemainingMs" to "2000",
            )
        )
        assertEquals(
            "app_registered · bridge_disconnected · mp_registered · reconnect · cooldown_2s",
            ServiceStatusFormatter.controlPlaneSummary(fake, layer)
        )
    }

    @Test
    fun `firstProblem 全健康无错误返回null_非健康优先于DEGRADED`() {
        val healthy = OrchestratedLayerStatus(state = "HEALTHY")
        assertNull(
            ServiceStatusFormatter.firstProblem(
                fake, listOf("VFS" to healthy, "Hook" to healthy)
            )
        )
        val layers = listOf(
            "VFS" to OrchestratedLayerStatus(state = "HEALTHY"),
            "Hook" to OrchestratedLayerStatus(state = "DEGRADED", lastError = "boom"),
            "Native" to OrchestratedLayerStatus(state = "UNAVAILABLE"),
        )
        assertEquals("problem_Native_unavailable", ServiceStatusFormatter.firstProblem(fake, layers))
        val degradedOnly = listOf("VFS" to OrchestratedLayerStatus(state = "DEGRADED"))
        assertTrue(
            ServiceStatusFormatter.firstProblem(fake, degradedOnly)!!.startsWith("problem_VFS_")
        )
    }

    @Test
    fun `fallbackRows 五层顺序与状态一致`() {
        val rows = ServiceStatusFormatter.fallbackRows(fake, ServerState.RUNNING, rootAvailable = true, xposedConnected = false)
        assertEquals(5, rows.size)
        assertEquals(StatusFallbackRow("wait_orchestrator", "STARTING"), rows[0])
        assertEquals(StatusFallbackRow("wait_mp_hook", "UNAVAILABLE"), rows[1])
        assertEquals(StatusFallbackRow("wait_native_hook", "UNAVAILABLE"), rows[2])
        assertEquals(StatusFallbackRow("wait_snapshot", "STARTING"), rows[3])
        assertEquals(StatusFallbackRow("binder_connected", "HEALTHY"), rows[4])
    }

    @Test
    fun `fallbackCause 三分支与null`() {
        assertEquals(
            "daemon_failed",
            ServiceStatusFormatter.fallbackCause(fake, ServerState.FAILED, rootAvailable = true, xposedConnected = true)
        )
        assertEquals(
            "root_unavailable",
            ServiceStatusFormatter.fallbackCause(fake, ServerState.RUNNING, rootAvailable = false, xposedConnected = true)
        )
        assertNull(
            ServiceStatusFormatter.fallbackCause(fake, ServerState.STOPPED, rootAvailable = false, xposedConnected = false)
        )
    }

    @Test
    fun `controller 手动停止分支吐停止态且无行`() {
        val controller = StatusCardController(
            strings = fake,
            getOrchestratedStatus = { null },
            isRoot = { true },
            configuredCount = { 2 },
            isManuallyStopped = { true },
            guard = { true },
        )
        val state = controller.buildViewState(ServerState.RUNNING, xposedConnected = true)
        assertTrue(state.manuallyStopped)
        assertTrue(state.rows.isEmpty())
        assertNull(state.cause)
        assertEquals("stopped_title", state.title)
    }

    @Test
    fun `controller 非运行态无orchestrated时走fallback`() {
        val controller = StatusCardController(
            strings = fake,
            getOrchestratedStatus = { null },
            isRoot = { true },
            configuredCount = { 0 },
            isManuallyStopped = { false },
            guard = { true },
        )
        val state = controller.buildViewState(ServerState.STOPPED, xposedConnected = false)
        assertEquals(5, state.rows.size)
        assertTrue(state.detailsAvailable)
    }

    @Test
    fun `controller 守卫未通过或视图未绑定时render丢弃`() {
        val guarded = StatusCardController(
            strings = fake,
            getOrchestratedStatus = { null },
            isRoot = { true },
            configuredCount = { 0 },
            isManuallyStopped = { false },
            guard = { false },
        )
        assertNull(guarded.render(ServerState.STOPPED, xposedConnected = false))

        val detached = StatusCardController(
            strings = fake,
            getOrchestratedStatus = { null },
            isRoot = { true },
            configuredCount = { 0 },
            isManuallyStopped = { false },
            guard = { true },
        )
        // 未 attach 视图 → 丢弃，不抛空指针。
        assertNull(detached.render(ServerState.STOPPED, xposedConnected = false))
    }

    companion object {
        private val TEMPLATES: Map<Int, String> = mapOf(
            R.string.runtime_state_healthy to "healthy",
            R.string.runtime_state_degraded to "degraded",
            R.string.runtime_state_stale to "stale",
            R.string.runtime_state_unavailable to "unavailable",
            R.string.runtime_state_recovering to "recovering",
            R.string.runtime_state_starting to "starting",
            R.string.runtime_state_uninitialized to "uninitialized",
            R.string.runtime_state_disabled to "disabled",
            R.string.service_status_summary_healthy to "healthy_summary",
            R.string.service_status_summary_ready_no_rules to "ready_no_rules",
            R.string.service_status_summary_degraded to "degraded_summary",
            R.string.service_status_summary_unavailable to "unavailable_summary",
            R.string.storage_redirect_no_mount_rules to "no_rules",
            R.string.service_status_configured_apps to "configured=%d",
            R.string.service_status_mounted_packages to "mounted=%d",
            R.string.service_status_recorded_processes to "pids=%d",
            R.string.service_status_exceptions to "exceptions=%d",
            R.string.service_status_top_error_code to "topError=%s",
            R.string.service_status_attempts to "attempts=%d",
            R.string.service_status_retry_with_max to "retry %d/%d",
            R.string.service_status_retry to "retry %d",
            R.string.hook_connected to "hook_connected",
            R.string.hook_waking_media_provider to "hook_waking",
            R.string.hook_recovery_cooldown_remaining to "hook_cooldown_%s",
            R.string.hook_bridge_connected_missing to "hook_missing_%d",
            R.string.hook_bridge_waiting to "hook_waiting",
            R.string.hook_bridge_reconnecting to "hook_reconnecting_%s",
            R.string.hook_binder_disconnected to "hook_binder_disconnected",
            R.string.native_mode_embedded_hook to "embedded",
            R.string.native_mount_points_generation to "gen_%s_%s",
            R.string.native_mount_points_generation_with_label to "gen_%s_%s_%s",
            R.string.native_libinline_not_loaded to "libinline_not_loaded",
            R.string.native_fuse_library_not_loaded to "fuse_not_loaded",
            R.string.native_embedded_library_missing to "embedded_missing",
            R.string.native_embedded_contains_mount_not_hooked to "contains_not_hooked",
            R.string.native_symbols_missing to "symbols_missing",
            R.string.native_mount_points_push_failed to "push_failed_%s_%s",
            R.string.native_generation to "gen_%s",
            R.string.databus_snapshot_rules to "rules",
            R.string.databus_snapshot_read_only to "read_only",
            R.string.databus_snapshot_mount_points to "mount_points",
            R.string.databus_snapshot_platform to "platform",
            R.string.databus_missing_snapshots to "databus_missing_%s",
            R.string.databus_hook_mode_embedded to "hook_embedded",
            R.string.databus_hook_mode_system to "hook_system",
            R.string.databus_hook_mode_none to "hook_none",
            R.string.databus_snapshots_ready to "databus_ready",
            R.string.control_app_binder_registered to "app_registered",
            R.string.control_app_binder_unregistered to "app_unregistered",
            R.string.control_hook_bridge_connected to "bridge_connected",
            R.string.control_hook_bridge_disconnected to "bridge_disconnected",
            R.string.control_media_provider_registered to "mp_registered",
            R.string.control_media_provider_unregistered to "mp_unregistered",
            R.string.control_reconnect_scheduled to "reconnect",
            R.string.control_wake_scheduled to "wake",
            R.string.control_cooldown to "cooldown_%s",
            R.string.service_status_problem_detail to "problem_%s_%s",
            R.string.service_status_healthy_configured_title to "healthy_title",
            R.string.service_status_ready_title to "ready_title",
            R.string.service_status_degraded_title to "degraded_title",
            R.string.service_status_unavailable_title to "unavailable_title",
            R.string.btn_stop_service to "stop",
            R.string.btn_start_service to "start",
            R.string.service_status_stopped_title to "stopped_title",
            R.string.service_status_stopped_configured to "stopped_%d",
            R.string.service_stopped_manually to "stopped_manually",
            R.string.service_status_starting_title to "starting_title",
            R.string.service_status_reading_title to "reading_title",
            R.string.service_status_start_failed_title to "failed_title",
            R.string.service_status_not_running_title to "not_running_title",
            R.string.service_status_daemon_connected_reading to "daemon_reading",
            R.string.service_status_configured_waiting to "waiting_%d",
            R.string.service_status_wait_orchestrator to "wait_orchestrator",
            R.string.service_status_root_unavailable to "root_unavailable",
            R.string.service_status_starting_short to "starting_short",
            R.string.service_status_start_failed_short to "failed_short",
            R.string.service_status_not_running_short to "not_running_short",
            R.string.service_status_wait_media_provider_hook to "wait_mp_hook",
            R.string.service_status_wait_native_hook to "wait_native_hook",
            R.string.service_status_wait_snapshot to "wait_snapshot",
            R.string.service_status_app_binder_connected to "binder_connected",
            R.string.service_status_disconnected to "disconnected",
            R.string.service_status_problem_daemon_failed to "daemon_failed",
            R.string.service_status_problem_root_unavailable to "root_unavailable",
            R.string.service_status_hint_media_provider_hook_unavailable to "mp_hint",
            R.string.runtime_layer_vfs to "VFS",
            R.string.runtime_layer_media_provider_hook to "Hook",
            R.string.runtime_layer_fuse_native to "Native",
            R.string.runtime_layer_databus to "DataBus",
            R.string.runtime_layer_control_plane to "Control",
        )
    }
}
