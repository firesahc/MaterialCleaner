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
            if (ServicePreferences.isServiceManuallyStopped) {
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
            R.id.menu_logcat -> {
                grabLogcatAndShare(requireContext())
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
        val isManuallyStopped = ServicePreferences.isServiceManuallyStopped
        val rootAvailable = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        val orchestrated = orchestratedStatus.takeIf { isRunning }
        val configured = ServicePreferences.srPackages.size

        // ── 手动停止：覆盖显示 ──
        if (isManuallyStopped) {
            statusTitle?.text = "存储重定向已停止"
            statusSubtitle?.text = if (configured > 0) {
                "已配置 $configured 个应用，启动服务后恢复重定向"
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
        btnToggleServer?.text = ctx.getString(R.string.btn_stop_service)

        if (orchestrated == null) {
            updateFallbackStatus(serverState, rootAvailable, isXposedConnected, configured)
            return
        }

        val title = when (orchestrated.health) {
            "HEALTHY" -> if (configured > 0) "存储重定向运行正常" else "存储重定向已就绪"
            "DEGRADED" -> "存储重定向降级运行"
            else -> "存储重定向不可用"
        }
        statusTitle?.text = title
        statusSubtitle?.text = topSummary(ctx, orchestrated, configured)

        setLayerRow(
            ctx,
            l1Indicator,
            l1Text,
            orchestrated.vfs,
            "VFS 主重定向",
            vfsSummary(ctx, orchestrated.vfs, configured),
        )
        setLayerRow(
            ctx,
            l2Indicator,
            l2Text,
            orchestrated.mediaProviderJavaHook,
            "媒体库路径修正",
            hookSummary(orchestrated.mediaProviderJavaHook),
        )
        setLayerRow(
            ctx,
            l3Indicator,
            l3Text,
            orchestrated.fuseNativeHook,
            "FUSE 兼容层",
            nativeSummary(orchestrated.fuseNativeHook),
        )
        setLayerRow(
            ctx,
            l4Indicator,
            l4Text,
            orchestrated.dataBus,
            "数据面快照",
            dataBusSummary(orchestrated.dataBus),
        )
        setLayerRow(
            ctx,
            l5Indicator,
            l5Text,
            orchestrated.controlPlane,
            "控制面",
            controlPlaneSummary(orchestrated.controlPlane),
        )

        val problem = firstProblem(orchestrated)
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
            ServerState.STARTING -> "存储重定向启动中"
            ServerState.RUNNING -> "正在读取运行状态"
            ServerState.FAILED -> "存储重定向启动失败"
            else -> "存储重定向未运行"
        }
        statusTitle?.text = title
        statusSubtitle?.text = when {
            serverState == ServerState.RUNNING -> "守护进程已连接，正在读取编排状态"
            configured == 0 -> ctx.getString(R.string.storage_redirect_no_mount_rules)
            else -> "已配置 $configured 个应用，等待服务就绪"
        }

        setFallbackRow(ctx, l1Indicator, l1Text, "VFS 主重定向", when {
            serverState == ServerState.RUNNING && rootAvailable -> "等待编排状态"
            serverState == ServerState.RUNNING -> "Root 不可用"
            serverState == ServerState.STARTING -> "启动中"
            serverState == ServerState.FAILED -> "启动失败"
            else -> "未运行"
        }, if (serverState == ServerState.RUNNING && rootAvailable) "STARTING" else "UNAVAILABLE")
        setFallbackRow(ctx, l2Indicator, l2Text, "媒体库路径修正", when {
            !isXposedConnected -> "等待 MediaProvider Hook"
            else -> "等待编排状态"
        }, if (isXposedConnected) "STARTING" else "UNAVAILABLE")
        setFallbackRow(ctx, l3Indicator, l3Text, "FUSE 兼容层", when {
            !isXposedConnected -> "等待 Native Hook"
            else -> "等待编排状态"
        }, if (isXposedConnected) "STARTING" else "UNAVAILABLE")
        setFallbackRow(ctx, l4Indicator, l4Text, "数据面快照", "等待状态快照", "STARTING")
        setFallbackRow(ctx, l5Indicator, l5Text, "控制面", if (serverState == ServerState.RUNNING) "App Binder 已连接" else "未连接", if (serverState == ServerState.RUNNING) "HEALTHY" else "UNAVAILABLE")

        val cause = when {
            serverState == ServerState.FAILED -> "问题：守护进程启动失败，请查看日志。"
            serverState == ServerState.RUNNING && !rootAvailable -> "问题：Root 权限不可用，VFS 主重定向无法工作。"
            serverState == ServerState.RUNNING && !isXposedConnected -> "提示：MediaProvider Hook 尚未连接，媒体库修正和 FUSE 兼容层暂不可用。"
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

    private fun stateLabel(state: String): String = when (state) {
        "HEALTHY" -> "正常"
        "DEGRADED" -> "降级"
        "STALE" -> "状态过期"
        "UNAVAILABLE" -> "不可用"
        "RECOVERING" -> "恢复中"
        "STARTING" -> "启动中"
        "UNINITIALIZED" -> "未初始化"
        "DISABLED" -> "已禁用"
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
            append(stateLabel(layer.state))
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
            "HEALTHY" -> if (configured > 0) "主重定向和兼容层均可用" else "服务已就绪，尚未配置挂载规则"
            "DEGRADED" -> "主能力可用性需结合下方各层状态确认"
            else -> "核心重定向不可用，请查看下方问题原因"
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
        val parts = mutableListOf("$configured 个应用")
        if (mountedPackages > 0) parts += "$mountedPackages 已处理"
        if (recordedPids > 0) parts += "$recordedPids 个进程"
        if (failedPids > 0 || failures > 0) parts += "${maxOf(failedPids, failures)} 异常"
        if (attempts > 0) parts += "$attempts 次尝试"
        return parts.joinToString(" · ")
    }

    private fun hookSummary(layer: OrchestratedLayerStatus): String {
        val connected = layer.metrics["binderConnected"]?.toBooleanStrictOrNull()
        val mediaProviderConnected = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        return when (connected) {
            true -> if (mediaProviderConnected == true) {
                "MediaProvider Hook 已连接"
            } else {
                "桥接已连接，等待 MediaProvider Hook"
            }
            false -> "Hook Binder 未连接"
            null -> ""
        }
    }

    private fun nativeSummary(layer: OrchestratedLayerStatus): String {
        val nativeGen = layer.metrics["configuredMountPointsGeneration"]
        val snapshotGen = layer.metrics["snapshotConfiguredMountPointsGeneration"]
        val inlineLoaded = layer.metrics["inlineLibraryLoaded"]?.toBooleanStrictOrNull()
        val fuseLoaded = layer.metrics["fuseLibraryLoaded"]?.toBooleanStrictOrNull()
        val containsMount = layer.metrics["containsMountHooked"]?.toBooleanStrictOrNull()
        val startsWith = layer.metrics["startsWithHooked"]?.toBooleanStrictOrNull()
        val bpf = layer.metrics["isFuseBpfEnabledHooked"]?.toBooleanStrictOrNull()
        val applySuccess = layer.metrics["lastMountPointsApplySuccess"]?.toBooleanStrictOrNull()
        return when {
            inlineLoaded == false -> "libinline 未加载"
            fuseLoaded == false && inlineLoaded == true -> "FUSE native 库未加载"
            containsMount == false || startsWith == false || bpf == false -> "native 符号部分缺失"
            applySuccess == false && nativeGen != null && nativeGen != "0" -> "挂载点推送失败 · generation $nativeGen/$snapshotGen"
            nativeGen != null && snapshotGen != null -> "挂载点 generation $nativeGen/$snapshotGen"
            nativeGen != null -> "native generation $nativeGen"
            else -> ""
        }
    }

    private fun dataBusSummary(layer: OrchestratedLayerStatus): String {
        val labels = listOf(
            "snapshotRedirectPolicy" to "规则",
            "snapshotReadOnly" to "只读",
            "snapshotConfiguredMountPoints" to "挂载点",
            "snapshotPlatformCapabilities" to "平台能力",
        )
        val missing = labels
            .filter { (key, _) -> layer.metrics[key] == "missing" }
            .map { (_, label) -> label }
        return if (missing.isEmpty()) "关键快照完整" else "缺少${missing.joinToString("、")}快照"
    }

    private fun controlPlaneSummary(layer: OrchestratedLayerStatus): String {
        val appBinder = layer.metrics["appBinderRegistered"]?.toBooleanStrictOrNull()
        val hooksBridge = layer.metrics["hooksBridgeConnected"]?.toBooleanStrictOrNull()
        val mediaProviderHook = layer.metrics["mediaProviderHookConnected"]?.toBooleanStrictOrNull()
        val parts = mutableListOf<String>()
        if (appBinder != null) parts += if (appBinder) "App Binder 已注册" else "App Binder 未注册"
        if (hooksBridge != null) parts += if (hooksBridge) "Hook 桥已连接" else "Hook 桥未连接"
        if (mediaProviderHook != null) parts += if (mediaProviderHook) "MediaProvider 已注册" else "MediaProvider 未注册"
        return parts.joinToString(" · ")
    }

    private fun firstProblem(status: OrchestratedRuntimeStatus): String? {
        val layers = listOf(
            "VFS 主重定向" to status.vfs,
            "媒体库路径修正" to status.mediaProviderJavaHook,
            "FUSE 兼容层" to status.fuseNativeHook,
            "数据面快照" to status.dataBus,
            "控制面" to status.controlPlane,
        )
        val problem = layers.firstOrNull { (_, layer) ->
            layer.state !in setOf("HEALTHY", "DEGRADED")
        } ?: layers.firstOrNull { (_, layer) ->
            layer.state == "DEGRADED" || layer.lastError != null
        }
        return problem?.let { (name, layer) ->
            val detail = layer.lastError ?: stateLabel(layer.state)
            "问题：$name - $detail"
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
            // 如果服务器已手动停止，不等待，直接返回空列表
            if (ServicePreferences.isServiceManuallyStopped) {
                adapter.submitList(emptyList())
                return@launch
            }

            // 等待服务器就绪（最长重试 20 次 = ~10 秒）
            CleanerClient.waitForBinder()
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
