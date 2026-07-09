package me.gm.cleaner.client.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.OrchestratedRuntimeStatus
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.client.ServerState
import me.gm.cleaner.client.StartSource
import me.gm.cleaner.client.StopSource
import me.gm.cleaner.client.XposedConnectionState
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.util.fitsSystemWindowInsets

class AppListFragment : BaseServiceSettingsFragment() {
    override val viewModel: AppListViewModel by viewModels()

    // 状态卡主控件
    private var statusDot: ImageView? = null
    private var statusTitle: TextView? = null
    private var statusSubtitle: TextView? = null
    private var btnToggleServer: MaterialButton? = null

    // 三层详情控件
    private var statusDetails: View? = null
    private var l1Indicator: ImageView? = null
    private var l1Text: TextView? = null
    private var l2Indicator: ImageView? = null
    private var l2Text: TextView? = null
    private var l3Indicator: ImageView? = null
    private var l3Text: TextView? = null
    private var l4Indicator: ImageView? = null
    private var l4Text: TextView? = null
    private var l5Indicator: ImageView? = null
    private var l5Text: TextView? = null
    private var statusCause: TextView? = null

    private var orchestratedStatus: OrchestratedRuntimeStatus? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.applist_fragment_main, container, false)

        statusDot = view.findViewById(R.id.status_dot)
        statusTitle = view.findViewById(R.id.status_title)
        statusSubtitle = view.findViewById(R.id.status_subtitle)
        btnToggleServer = view.findViewById(R.id.btn_toggle_server)
        statusDetails = view.findViewById(R.id.status_details)
        l1Indicator = view.findViewById(R.id.l1_indicator)
        l1Text = view.findViewById(R.id.l1_text)
        l2Indicator = view.findViewById(R.id.l2_indicator)
        l2Text = view.findViewById(R.id.l2_text)
        l3Indicator = view.findViewById(R.id.l3_indicator)
        l3Text = view.findViewById(R.id.l3_text)
        l4Indicator = view.findViewById(R.id.l4_indicator)
        l4Text = view.findViewById(R.id.l4_text)
        l5Indicator = view.findViewById(R.id.l5_indicator)
        l5Text = view.findViewById(R.id.l5_text)
        statusCause = view.findViewById(R.id.status_cause)
        val btnNewMount = view.findViewById<MaterialButton>(R.id.btn_new_mount)
        val listContainer = view.findViewById<SwipeRefreshLayout>(R.id.list_container)
        val list = view.findViewById<RecyclerView>(R.id.list)

        // Setup RecyclerView
        val adapter = AppListAdapter(
            fragment = this,
            navDestinationId = R.id.service_settings_fragment,
            navAction = { model ->
                ServiceSettingsFragmentDirections
                    .serviceSettingsToStorageRedirectAction(model.packageInfo)
            }
        )
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.setHasFixedSize(true)

        // Pull-to-refresh: only refresh app list, not server/status
        listContainer.setOnRefreshListener {
            viewModel.updateAppsRuleCount()
        }

        // Initial status update
        updateServiceStatus()

        // Apply system window insets to root layout so content starts below toolbar+tabs+status bar
        view.fitsSystemWindowInsets()

        // Toggle service button — start or stop
        btnToggleServer?.setOnClickListener {
            val serverState = ServerStateMachine.state.value
            if (ServerStateMachine.isSessionManuallyStopped ||
                serverState == ServerState.STOPPED ||
                serverState == ServerState.FAILED
            ) {
                startServer()
            } else {
                stopServer()
            }
        }

        // "新建挂载" button → navigate to MountAppPickerFragment
        btnNewMount?.setOnClickListener {
            findNavController().navigate(
                ServiceSettingsFragmentDirections.serviceSettingsToMountAppPickerAction()
            )
        }

        // Observe server version changes for passive UI updates
        CleanerClient.serverVersionLiveData.observe(viewLifecycleOwner) {
            if (isAdded) updateServiceStatus()
        }

        // 观察 appsFlow → ViewModel 加载完成后自动更新挂载列表
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appsFlow.collect { state ->
                    when (state) {
                        is AppListState.Done -> {
                            val mounted = state.list.filter { it.mountRulesCount > 0 }
                            adapter.submitList(mounted)
                            listContainer.isRefreshing = false
                        }
                        is AppListState.Loading -> {
                            // keep refreshing indicator visible during reload
                        }
                        is AppListState.Error -> {
                            adapter.submitList(emptyList())
                            listContainer.isRefreshing = false
                        }
                    }
                }
            }
        }

        // 用 ServerStateMachine + XposedConnectionState 的 Flow combine 驱动状态卡
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ServerStateMachine.state,
                    XposedConnectionState.isConnected
                ) { serverState, xposedConnected ->
                    // 服务器刚进入运行态 → 异步加载编排状态
                    if (serverState == ServerState.RUNNING) {
                        refreshOrchestratedStatus()
                    } else {
                        orchestratedStatus = null
                    }
                    updateServiceStatus(
                        serverState = serverState,
                        isXposedConnected = xposedConnected
                    )
                }.collect { }
            }
        }

        // Observe preferences changes → refresh rule count, mount list, and status display
        ServicePreferences.preferencesChangeLiveData.observe(viewLifecycleOwner) {
            viewModel.updateAppsRuleCount()
            refreshOrchestratedStatus()
            updateServiceStatus()
        }

        // Load initial mounted apps（添加重试等待服务器就绪）
        loadMountedApps(adapter)

        super.onCreateView(inflater, container, savedInstanceState)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Refresh mounted apps list when returning from mount creation/edit
        viewModel.loadApps()
        refreshOrchestratedStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.applist_main_toolbar, menu)
        // Do NOT call super — BaseServiceSettingsFragment sets up SearchView which is not used here
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_diagnostics_archive -> {
                exportDiagnosticsArchiveAndShare(requireContext())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------------------------------------------------------------

    /**
     * 更新三层状态卡的显示，三个层级对应真实存储重定向架构：
     *
     * Level 1 — VFS bind mount（Mount Namespace 层）
     *   cleaner_server 进程通过 fork() → setns() → mount(MS_BIND)
     *   在目标应用 mount namespace 中创建绑定挂载。需要 root。
     *   数据源：[OrchestratedRuntimeStatus.vfs]
     *
     * Level 2 — InsertHooker（MediaProvider Java Hook 层）
     *   Xposed 模块 Hook MediaProvider.insertFile()，在 ContentValues
     *   层替换 `_data` 列路径。需要 LSPosed。
     *   数据源：[XposedConnectionState.isConnected]
     *
     * Level 3 — bpf_hook / xhook（FUSE Native Hook 层）
     *   PLT/GOT Hook libfuse_jni.so，拦截 containsMount / StartsWith
     *   等 6 个函数，实现 FUSE 内核级 I/O 重定向。需要 LSPosed。
     *   数据源：[XposedConnectionState.isConnected]（同 Level 2，
     *   两者在 XposedInit 中一同初始化）
     */
    private fun updateServiceStatus(
        serverState: ServerState = ServerStateMachine.state.value,
        isXposedConnected: Boolean = XposedConnectionState.isConnected.value
    ) {
        if (!isAdded) return

        val ctx = requireContext()
        val isRunning = serverState == ServerState.RUNNING
        val isManuallyStopped = ServerStateMachine.isSessionManuallyStopped
        val rootAvailable = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        val orchestrated = orchestratedStatus.takeIf { isRunning }
        val configured = ServicePreferences.srPackages.size

        // ── 手动停止：覆盖显示 ──
        if (isManuallyStopped) {
            statusTitle?.text = ctx.getString(R.string.service_status_stopped_title)
            statusSubtitle?.text = if (configured > 0) {
                ctx.getString(R.string.service_status_stopped_configured, configured)
            } else {
                ctx.getString(R.string.service_stopped_manually)
            }
            statusDetails?.visibility = android.view.View.GONE
            statusCause?.visibility = android.view.View.GONE
            btnToggleServer?.text = ctx.getString(R.string.btn_start_service)
            statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
            statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
            )
            return
        }

        // ── 正常显示：五层详情 ──
        statusDetails?.visibility = android.view.View.VISIBLE
        btnToggleServer?.text = ctx.getString(
            if (serverState == ServerState.RUNNING || serverState == ServerState.STARTING) {
                R.string.btn_stop_service
            } else {
                R.string.btn_start_service
            }
        )

        if (orchestrated == null) {
            updateFallbackStatus(serverState, rootAvailable, isXposedConnected, configured)
            return
        }

        val title = when (orchestrated.health) {
            "HEALTHY" -> if (configured > 0) {
                ctx.getString(R.string.service_status_healthy_configured_title)
            } else {
                ctx.getString(R.string.service_status_ready_title)
            }
            "DEGRADED" -> ctx.getString(R.string.service_status_degraded_title)
            else -> ctx.getString(R.string.service_status_unavailable_title)
        }
        statusTitle?.text = title
        statusSubtitle?.text = topSummary(ctx, orchestrated, configured)

        setLayerRow(
            ctx,
            l1Indicator,
            l1Text,
            orchestrated.vfs,
            ctx.getString(R.string.runtime_layer_vfs),
            vfsSummary(ctx, orchestrated.vfs, configured),
        )
        setLayerRow(
            ctx,
            l2Indicator,
            l2Text,
            orchestrated.mediaProviderJavaHook,
            ctx.getString(R.string.runtime_layer_media_provider_hook),
            hookSummary(ctx, orchestrated.mediaProviderJavaHook),
        )
        setLayerRow(
            ctx,
            l3Indicator,
            l3Text,
            orchestrated.fuseNativeHook,
            ctx.getString(R.string.runtime_layer_fuse_native),
            nativeSummary(ctx, orchestrated.fuseNativeHook),
        )
        setLayerRow(
            ctx,
            l4Indicator,
            l4Text,
            orchestrated.dataBus,
            ctx.getString(R.string.runtime_layer_databus),
            dataBusSummary(ctx, orchestrated.dataBus),
        )
        setLayerRow(
            ctx,
            l5Indicator,
            l5Text,
            orchestrated.controlPlane,
            ctx.getString(R.string.runtime_layer_control_plane),
            controlPlaneSummary(ctx, orchestrated.controlPlane),
        )

        val problem = firstProblem(ctx, orchestrated)
        statusCause?.visibility = if (problem == null) android.view.View.GONE else android.view.View.VISIBLE
        statusCause?.text = problem

        val (dotIcon, dotColorRes) = statusIcon(orchestrated.health)
        statusDot?.setImageResource(dotIcon)
        statusDot?.imageTintList = tintList(ctx, dotColorRes)
    }

    private fun updateFallbackStatus(
        serverState: ServerState,
        rootAvailable: Boolean,
        isXposedConnected: Boolean,
        configured: Int,
    ) {
        val ctx = requireContext()
        val title = when (serverState) {
            ServerState.STARTING -> ctx.getString(R.string.service_status_starting_title)
            ServerState.RUNNING -> ctx.getString(R.string.service_status_reading_title)
            ServerState.FAILED -> ctx.getString(R.string.service_status_start_failed_title)
            else -> ctx.getString(R.string.service_status_not_running_title)
        }
        statusTitle?.text = title
        statusSubtitle?.text = when {
            serverState == ServerState.RUNNING -> ctx.getString(R.string.service_status_daemon_connected_reading)
            configured == 0 -> ctx.getString(R.string.storage_redirect_no_mount_rules)
            else -> ctx.getString(R.string.service_status_configured_waiting, configured)
        }

        setFallbackRow(ctx, l1Indicator, l1Text, ctx.getString(R.string.runtime_layer_vfs), when {
            serverState == ServerState.RUNNING && rootAvailable -> ctx.getString(R.string.service_status_wait_orchestrator)
            serverState == ServerState.RUNNING -> ctx.getString(R.string.service_status_root_unavailable)
            serverState == ServerState.STARTING -> ctx.getString(R.string.service_status_starting_short)
            serverState == ServerState.FAILED -> ctx.getString(R.string.service_status_start_failed_short)
            else -> ctx.getString(R.string.service_status_not_running_short)
        }, if (serverState == ServerState.RUNNING && rootAvailable) "STARTING" else "UNAVAILABLE")
        setFallbackRow(ctx, l2Indicator, l2Text, ctx.getString(R.string.runtime_layer_media_provider_hook), when {
            !isXposedConnected -> ctx.getString(R.string.service_status_wait_media_provider_hook)
            else -> ctx.getString(R.string.service_status_wait_orchestrator)
        }, if (isXposedConnected) "STARTING" else "UNAVAILABLE")
        setFallbackRow(ctx, l3Indicator, l3Text, ctx.getString(R.string.runtime_layer_fuse_native), when {
            !isXposedConnected -> ctx.getString(R.string.service_status_wait_native_hook)
            else -> ctx.getString(R.string.service_status_wait_orchestrator)
        }, if (isXposedConnected) "STARTING" else "UNAVAILABLE")
        setFallbackRow(
            ctx, l4Indicator, l4Text,
            ctx.getString(R.string.runtime_layer_databus),
            ctx.getString(R.string.service_status_wait_snapshot),
            "STARTING"
        )
        setFallbackRow(
            ctx, l5Indicator, l5Text,
            ctx.getString(R.string.runtime_layer_control_plane),
            if (serverState == ServerState.RUNNING) {
                ctx.getString(R.string.service_status_app_binder_connected)
            } else {
                ctx.getString(R.string.service_status_disconnected)
            },
            if (serverState == ServerState.RUNNING) "HEALTHY" else "UNAVAILABLE"
        )

        val cause = when {
            serverState == ServerState.FAILED -> ctx.getString(R.string.service_status_problem_daemon_failed)
            serverState == ServerState.RUNNING && !rootAvailable -> ctx.getString(R.string.service_status_problem_root_unavailable)
            serverState == ServerState.RUNNING && !isXposedConnected -> ctx.getString(R.string.service_status_hint_media_provider_hook_unavailable)
            else -> null
        }
        statusCause?.visibility = if (cause == null) android.view.View.GONE else android.view.View.VISIBLE
        statusCause?.text = cause

        val (dotIcon, dotColorRes) = when (serverState) {
            ServerState.RUNNING -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
            ServerState.STARTING -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
            else -> R.drawable.ic_baseline_error_24 to android.R.color.holo_red_dark
        }
        statusDot?.setImageResource(dotIcon)
        statusDot?.imageTintList = tintList(ctx, dotColorRes)
    }

    private fun tintList(ctx: android.content.Context, colorRes: Int) =
        android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))

    private fun layerColor(layer: OrchestratedLayerStatus): Int = when (layer.state) {
        "HEALTHY" -> android.R.color.holo_green_dark
        "DEGRADED", "RECOVERING", "STALE", "STARTING" -> android.R.color.holo_orange_dark
        "UNINITIALIZED", "DISABLED" -> android.R.color.darker_gray
        else -> android.R.color.holo_red_dark
    }

    private fun stateLabel(ctx: android.content.Context, state: String): String = when (state) {
        "HEALTHY" -> ctx.getString(R.string.runtime_state_healthy)
        "DEGRADED" -> ctx.getString(R.string.runtime_state_degraded)
        "STALE" -> ctx.getString(R.string.runtime_state_stale)
        "UNAVAILABLE" -> ctx.getString(R.string.runtime_state_unavailable)
        "RECOVERING" -> ctx.getString(R.string.runtime_state_recovering)
        "STARTING" -> ctx.getString(R.string.runtime_state_starting)
        "UNINITIALIZED" -> ctx.getString(R.string.runtime_state_uninitialized)
        "DISABLED" -> ctx.getString(R.string.runtime_state_disabled)
        else -> state
    }

    private fun statusIcon(health: String): Pair<Int, Int> = when (health) {
        "HEALTHY" -> R.drawable.ic_baseline_check_circle_24 to android.R.color.holo_green_dark
        "DEGRADED" -> R.drawable.ic_baseline_error_24 to android.R.color.holo_orange_dark
        else -> R.drawable.ic_baseline_error_24 to android.R.color.holo_red_dark
    }

    private fun setLayerRow(
        ctx: android.content.Context,
        indicator: ImageView?,
        text: TextView?,
        layer: OrchestratedLayerStatus,
        name: String,
        summary: String,
    ) {
        val color = layerColor(layer)
        indicator?.imageTintList = tintList(ctx, color)
        text?.text = buildString {
            append(name)
            append(" · ")
            append(stateLabel(ctx, layer.state))
            if (summary.isNotBlank()) {
                append(" · ")
                append(summary)
            }
        }
        text?.setTextColor(ContextCompat.getColor(ctx, color))
    }

    private fun setFallbackRow(
        ctx: android.content.Context,
        indicator: ImageView?,
        text: TextView?,
        name: String,
        summary: String,
        state: String,
    ) {
        val layer = OrchestratedLayerStatus(state = state)
        setLayerRow(ctx, indicator, text, layer, name, summary)
    }

    private fun topSummary(
        ctx: android.content.Context,
        status: OrchestratedRuntimeStatus,
        configured: Int,
    ): String {
        val base = when (status.health) {
            "HEALTHY" -> if (configured > 0) {
                ctx.getString(R.string.service_status_summary_healthy)
            } else {
                ctx.getString(R.string.service_status_summary_ready_no_rules)
            }
            "DEGRADED" -> ctx.getString(R.string.service_status_summary_degraded)
            else -> ctx.getString(R.string.service_status_summary_unavailable)
        }
        return "$base · ${vfsSummary(ctx, status.vfs, configured)}"
    }

    private fun vfsSummary(
        ctx: android.content.Context,
        layer: OrchestratedLayerStatus,
        configured: Int = ServicePreferences.srPackages.size,
    ): String {
        if (configured == 0) return ctx.getString(R.string.storage_redirect_no_mount_rules)
        val mountedPackages = layer.metrics["mountedPackages"]?.toIntOrNull() ?: 0
        val recordedPids = layer.metrics["recordedPids"]?.toIntOrNull() ?: 0
        val failedPids = layer.metrics["mountFailedPids"]?.toIntOrNull() ?: 0
        val attempts = layer.metrics["mountTotalAttempts"]?.toIntOrNull() ?: 0
        val failures = layer.metrics["mountFailureCount"]?.toIntOrNull() ?: 0
        val parts = mutableListOf(ctx.getString(R.string.service_status_configured_apps, configured))
        if (mountedPackages > 0) {
            parts += ctx.getString(R.string.service_status_mounted_packages, mountedPackages)
        }
        if (recordedPids > 0) {
            parts += ctx.getString(R.string.service_status_recorded_processes, recordedPids)
        }
        if (failedPids > 0 || failures > 0) {
            parts += ctx.getString(R.string.service_status_exceptions, maxOf(failedPids, failures))
        }
        if (attempts > 0) {
            parts += ctx.getString(R.string.service_status_attempts, attempts)
        }
        return parts.joinToString(" · ")
    }

    private fun hookSummary(ctx: android.content.Context, layer: OrchestratedLayerStatus): String {
        val connected = layer.metrics["binderConnected"]?.toBooleanStrictOrNull()
        val mediaProviderConnected = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        val reconnectScheduled = layer.metrics["hooksReconnectScheduled"]?.toBooleanStrictOrNull()
        val wakeScheduled = layer.metrics["mediaProviderWakeScheduled"]?.toBooleanStrictOrNull()
        val missingChecks = layer.metrics["consecutiveMediaProviderHookMissing"]?.toIntOrNull() ?: 0
        val cooldownMs = layer.metrics["mediaProviderRecoveryCooldownRemainingMs"]?.toLongOrNull() ?: 0L
        val retryCount = layer.metrics["hooksRetryCount"]?.toIntOrNull() ?: 0
        val maxRetries = layer.metrics["maxHookRetries"]?.toIntOrNull() ?: 0
        val retryLabel = if (maxRetries > 0) {
            ctx.getString(R.string.service_status_retry_with_max, retryCount + 1, maxRetries)
        } else {
            ctx.getString(R.string.service_status_retry, retryCount + 1)
        }
        return when (connected) {
            true -> if (mediaProviderConnected == true) {
                ctx.getString(R.string.hook_connected)
            } else if (wakeScheduled == true) {
                ctx.getString(R.string.hook_waking_media_provider)
            } else if (cooldownMs > 0L) {
                ctx.getString(R.string.hook_recovery_cooldown_remaining, formatDurationSeconds(cooldownMs))
            } else if (missingChecks > 0) {
                ctx.getString(R.string.hook_bridge_connected_missing, missingChecks)
            } else {
                ctx.getString(R.string.hook_bridge_waiting)
            }
            false -> if (reconnectScheduled == true) {
                ctx.getString(R.string.hook_bridge_reconnecting, retryLabel)
            } else {
                ctx.getString(R.string.hook_binder_disconnected)
            }
            null -> ""
        }
    }

    private fun nativeSummary(ctx: android.content.Context, layer: OrchestratedLayerStatus): String {
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
            "EMBEDDED_GOT_PATCH" -> ctx.getString(R.string.native_mode_embedded_hook)
            "XHOOK" -> "xhook"
            else -> ""
        }
        fun generationSummary(prefix: String): String {
            val label = listOf(prefix, modeLabel).filter { it.isNotBlank() }.joinToString(" · ")
            return if (label.isBlank()) {
                ctx.getString(R.string.native_mount_points_generation, nativeGen, snapshotGen)
            } else {
                ctx.getString(R.string.native_mount_points_generation_with_label, label, nativeGen, snapshotGen)
            }
        }
        return when {
            inlineLoaded == false -> ctx.getString(R.string.native_libinline_not_loaded)
            fuseLoaded == false && inlineLoaded == true -> ctx.getString(R.string.native_fuse_library_not_loaded)
            hookMode == "EMBEDDED_GOT_PATCH" && embeddedFound == false -> ctx.getString(R.string.native_embedded_library_missing)
            hookMode == "EMBEDDED_GOT_PATCH" && containsMount == false -> ctx.getString(R.string.native_embedded_contains_mount_not_hooked)
            hookMode != "EMBEDDED_GOT_PATCH" &&
                    (containsMount == false || startsWith == false || bpf == false) -> ctx.getString(R.string.native_symbols_missing)
            applySuccess == false && nativeGen != null && nativeGen != "0" ->
                ctx.getString(R.string.native_mount_points_push_failed, nativeGen, snapshotGen)
            nativeGen != null && snapshotGen != null -> generationSummary("")
            nativeGen != null -> ctx.getString(R.string.native_generation, nativeGen)
            else -> ""
        }
    }

    private fun dataBusSummary(ctx: android.content.Context, layer: OrchestratedLayerStatus): String {
        val labels = listOf(
            "snapshotRedirectPolicy" to ctx.getString(R.string.databus_snapshot_rules),
            "snapshotReadOnly" to ctx.getString(R.string.databus_snapshot_read_only),
            "snapshotConfiguredMountPoints" to ctx.getString(R.string.databus_snapshot_mount_points),
            "snapshotPlatformCapabilities" to ctx.getString(R.string.databus_snapshot_platform),
        )
        val missing = labels
            .filter { (key, _) -> layer.metrics[key] == "missing" }
            .map { (_, label) -> label }
        if (missing.isNotEmpty()) {
            return ctx.getString(R.string.databus_missing_snapshots, missing.joinToString("、"))
        }
        val hookMode = when (layer.metrics["platformSupportedNativeHookMode"]) {
            "EMBEDDED_GOT_PATCH" -> ctx.getString(R.string.databus_hook_mode_embedded)
            "XHOOK" -> ctx.getString(R.string.databus_hook_mode_system)
            "NONE" -> ctx.getString(R.string.databus_hook_mode_none)
            else -> ""
        }
        return listOf(ctx.getString(R.string.databus_snapshots_ready), hookMode)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun controlPlaneSummary(ctx: android.content.Context, layer: OrchestratedLayerStatus): String {
        val appBinder = layer.metrics["appBinderRegistered"]?.toBooleanStrictOrNull()
        val hooksBridge = layer.metrics["hooksBridgeConnected"]?.toBooleanStrictOrNull()
        val mediaProviderHook = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        val reconnectScheduled = layer.metrics["hooksReconnectScheduled"]?.toBooleanStrictOrNull()
        val wakeScheduled = layer.metrics["mediaProviderWakeScheduled"]?.toBooleanStrictOrNull()
        val cooldownMs = layer.metrics["mediaProviderRecoveryCooldownRemainingMs"]?.toLongOrNull() ?: 0L
        val parts = mutableListOf<String>()
        if (appBinder != null) {
            parts += if (appBinder) {
                ctx.getString(R.string.control_app_binder_registered)
            } else {
                ctx.getString(R.string.control_app_binder_unregistered)
            }
        }
        if (hooksBridge != null) {
            parts += if (hooksBridge) {
                ctx.getString(R.string.control_hook_bridge_connected)
            } else {
                ctx.getString(R.string.control_hook_bridge_disconnected)
            }
        }
        if (mediaProviderHook != null) {
            parts += if (mediaProviderHook) {
                ctx.getString(R.string.control_media_provider_registered)
            } else {
                ctx.getString(R.string.control_media_provider_unregistered)
            }
        }
        if (reconnectScheduled == true) parts += ctx.getString(R.string.control_reconnect_scheduled)
        if (wakeScheduled == true) parts += ctx.getString(R.string.control_wake_scheduled)
        if (cooldownMs > 0L) parts += ctx.getString(R.string.control_cooldown, formatDurationSeconds(cooldownMs))
        return parts.joinToString(" · ")
    }

    private fun formatDurationSeconds(durationMs: Long): String {
        val seconds = ((durationMs + 999L) / 1000L).coerceAtLeast(1L)
        return "${seconds}s"
    }

    private fun firstProblem(ctx: android.content.Context, status: OrchestratedRuntimeStatus): String? {
        val layers = listOf(
            ctx.getString(R.string.runtime_layer_vfs) to status.vfs,
            ctx.getString(R.string.runtime_layer_media_provider_hook) to status.mediaProviderJavaHook,
            ctx.getString(R.string.runtime_layer_fuse_native) to status.fuseNativeHook,
            ctx.getString(R.string.runtime_layer_databus) to status.dataBus,
            ctx.getString(R.string.runtime_layer_control_plane) to status.controlPlane,
        )
        val problem = layers.firstOrNull { (_, layer) ->
            layer.state !in setOf("HEALTHY", "DEGRADED")
        } ?: layers.firstOrNull { (_, layer) ->
            layer.state == "DEGRADED" || layer.lastError != null
        }
        return problem?.let { (name, layer) ->
            val detail = layer.lastError ?: stateLabel(ctx, layer.state)
            ctx.getString(R.string.service_status_problem_detail, name, detail)
        }
    }

    private fun refreshOrchestratedStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = CleanerClient.getOrchestratedStatus()
            withContext(Dispatchers.Main) {
                orchestratedStatus = status
                if (isAdded) updateServiceStatus()
            }
        }
    }

    private fun startServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = ServerStateMachine.start(StartSource.MANUAL, requireContext())
                if (success) {
                    viewModel.loadApps(startServer = false)
                    val packages = ServicePreferences.srPackages
                    val msg = if (packages.isNotEmpty()) {
                        requireContext().getString(R.string.toast_service_started_n_mounted, packages.size)
                    } else {
                        requireContext().getString(R.string.toast_service_started_empty)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), R.string.toast_start_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "startServer: exception", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.toast_start_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopServer() {
        lifecycleScope.launch {
            try {
                ServerStateMachine.stop(StopSource.USER)
                Toast.makeText(requireContext(), R.string.toast_stopped, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "stopServer: exception", e)
            }
        }
    }

    /**
     * Execute [action] with retry on failure (max [maxRetries] attempts, 1s between).
     * Each retry first verifies pingBinder() is still true.
     */
    private suspend fun reloadConfigWithRetry(
        name: String,
        maxRetries: Int = 3,
        action: suspend () -> Unit
    ) {
        var attempt = 0
        while (attempt < maxRetries) {
            try {
                if (!CleanerClient.pingBinder()) {
                    if (BuildConfig.DEBUG) Log.w("CleanerTest", "reloadConfig: $name - Binder lost at attempt ${attempt + 1}")
                    delay(500)  // 给 Binder 一点时间恢复
                    attempt++
                    continue
                }
                action()
                return  // 成功
            } catch (e: Exception) {
                attempt++
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "reloadConfig: $name failed attempt $attempt/$maxRetries", e)
                if (attempt < maxRetries) {
                    delay(1000)
                }
            }
        }
        if (BuildConfig.DEBUG) Log.e("CleanerTest", "reloadConfig: $name FAILED after $maxRetries retries")
    }

    private fun loadMountedApps(adapter: AppListAdapter) {
        lifecycleScope.launch {
            // 如果本会话已手动停止或服务未处于启动/运行态，不等待，直接返回空列表
            if (ServerStateMachine.isSessionManuallyStopped ||
                ServerStateMachine.state.value == ServerState.STOPPED ||
                ServerStateMachine.state.value == ServerState.FAILED
            ) {
                adapter.submitList(emptyList())
                return@launch
            }

            // 等待服务器就绪（最长重试 20 次 = ~10 秒）
            if (!CleanerClient.waitForBinder()) {
                adapter.submitList(emptyList())
                return@launch
            }
            val loaded = withContext(Dispatchers.Default) {
                try {
                    AppListLoader().load()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "AppListFragment.loadMountedApps: failed", e)
                    null
                }
            }
            // Only update if load succeeded; don't overwrite existing data on failure
            if (loaded != null) {
                val mounted = loaded.filter { it.mountRulesCount > 0 }
                adapter.submitList(mounted)
            }
        }
    }
}
