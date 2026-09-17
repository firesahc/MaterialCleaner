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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.client.ServerState
import me.gm.cleaner.client.StartSource
import me.gm.cleaner.client.StopSource
import me.gm.cleaner.client.XposedConnectionState
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.util.fitsSystemWindowInsets

/**
 * 应用列表页：RecyclerView / 导航 / 4 路 collect 保留在此。
 *
 * 状态卡（五层详情 + fallback + 手动停止覆盖）已抽取：
 * - 文案/色值纯计算 → [ServiceStatusFormatter]；
 * - 数据源（CleanerClient.getOrchestratedStatus）+ 生命周期守卫 + View 绑定 →
 *   [StatusCardController]，对外吐 StatusCardViewState。
 * 本类对状态卡只做 collect + 转发，不直接拼文案。
 */
class AppListFragment : BaseServiceSettingsFragment() {
    override val viewModel: AppListViewModel by viewModels()

    private lateinit var statusController: StatusCardController

    private fun currentServerInputs(): Pair<ServerState, Boolean> =
        ServerStateMachine.state.value to XposedConnectionState.isConnected.value

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConfiguredPolicyStoreProvider.instance.snapshots.collect {
                    viewModel.updateAppsRuleCount()
                    val (serverState, xposedConnected) = currentServerInputs()
                    statusController.refresh(lifecycleScope, serverState, xposedConnected)
                    statusController.render(serverState, xposedConnected)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.applist_fragment_main, container, false)

        if (!::statusController.isInitialized) {
            statusController = StatusCardController(
                strings = StatusStrings { id, args -> requireContext().getString(id, *args) },
                guard = { isAdded },
            )
            statusController.isExpanded = savedInstanceState?.getBoolean(
                SAVED_STATUS_DETAILS_EXPANDED,
            ) ?: false
        }
        statusController.attach(
            StatusCardViews(
                statusDot = view.findViewById(R.id.status_dot),
                statusTitle = view.findViewById(R.id.status_title),
                statusSubtitle = view.findViewById(R.id.status_subtitle),
                btnToggleServer = view.findViewById(R.id.btn_toggle_server),
                btnToggleStatusDetails = view.findViewById(R.id.btn_toggle_status_details),
                statusDetails = view.findViewById(R.id.status_details),
                l1Indicator = view.findViewById(R.id.l1_indicator),
                l1Text = view.findViewById(R.id.l1_text),
                l2Indicator = view.findViewById(R.id.l2_indicator),
                l2Text = view.findViewById(R.id.l2_text),
                l3Indicator = view.findViewById(R.id.l3_indicator),
                l3Text = view.findViewById(R.id.l3_text),
                l4Indicator = view.findViewById(R.id.l4_indicator),
                l4Text = view.findViewById(R.id.l4_text),
                l5Indicator = view.findViewById(R.id.l5_indicator),
                l5Text = view.findViewById(R.id.l5_text),
                statusCause = view.findViewById(R.id.status_cause),
            )
        )
        val btnToggleServer = view.findViewById<MaterialButton>(R.id.btn_toggle_server)
        val btnToggleStatusDetails = view.findViewById<ImageView>(R.id.btn_toggle_status_details)
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
        currentServerInputs().let { (serverState, xposedConnected) ->
            statusController.render(serverState, xposedConnected)
        }

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

        // 状态详情收起/展开箭头
        btnToggleStatusDetails?.setOnClickListener {
            statusController.toggleExpansion()
        }

        // "新建挂载" button → navigate to MountAppPickerFragment
        btnNewMount?.setOnClickListener {
            findNavController().navigate(
                ServiceSettingsFragmentDirections.serviceSettingsToMountAppPickerAction()
            )
        }

        // Observe server version changes for passive UI updates
        CleanerClient.serverVersionLiveData.observe(viewLifecycleOwner) {
            currentServerInputs().let { (serverState, xposedConnected) ->
                statusController.render(serverState, xposedConnected)
            }
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
                    // 服务器刚进入运行态 → 异步加载编排状态；非运行态直接渲染 fallback。
                    if (serverState == ServerState.RUNNING) {
                        statusController.refresh(lifecycleScope, serverState, xposedConnected)
                    } else {
                        statusController.render(serverState, xposedConnected)
                    }
                }.collect { }
            }
        }

        // Observe preferences changes → refresh rule count, mount list, and status display
        ServicePreferences.preferencesChangeLiveData.observe(viewLifecycleOwner) {
            viewModel.updateAppsRuleCount()
            currentServerInputs().let { (serverState, xposedConnected) ->
                statusController.refresh(lifecycleScope, serverState, xposedConnected)
                statusController.render(serverState, xposedConnected)
            }
        }

        // Load initial mounted apps（添加重试等待服务器就绪）
        loadMountedApps(adapter)

        super.onCreateView(inflater, container, savedInstanceState)
        return view
    }

    override fun onDestroyView() {
        if (::statusController.isInitialized) {
            statusController.detach()
        }
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            SAVED_STATUS_DETAILS_EXPANDED,
            if (::statusController.isInitialized) statusController.isExpanded else false
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Refresh mounted apps list when returning from mount creation/edit
        viewModel.loadApps()
        if (::statusController.isInitialized) {
            currentServerInputs().let { (serverState, xposedConnected) ->
                statusController.refresh(lifecycleScope, serverState, xposedConnected)
            }
        }
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
    // TODO(Phase-2): startServer / stopServer / loadMountedApps 执行面迁入 ViewModel，
    // Fragment 只保留 collect + 转发。本次 Phase-1 为控制范围，保持原实现不动。

    private fun startServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = ServerStateMachine.start(StartSource.MANUAL, requireContext())
                if (success) {
                    viewModel.loadApps()
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

    companion object {
        private const val SAVED_STATUS_DETAILS_EXPANDED = "status_details_expanded"
    }
}
