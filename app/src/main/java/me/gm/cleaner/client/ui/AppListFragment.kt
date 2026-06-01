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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.HooksBridgeProvider
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.client.ServerState
import me.gm.cleaner.client.StartSource
import me.gm.cleaner.client.StopSource
import me.gm.cleaner.client.XposedConnectionState
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.starter.Starter
import me.gm.cleaner.util.fitsSystemWindowInsets

class AppListFragment : BaseServiceSettingsFragment() {
    override val viewModel: AppListViewModel by viewModels()

    private var statusDot: ImageView? = null
    private var statusTitle: TextView? = null
    private var statusSubtitle: TextView? = null
    private var mountedCountTextView: TextView? = null
    private var btnToggleServer: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.applist_fragment_main, container, false)

        statusDot = view.findViewById(R.id.status_dot)
        statusTitle = view.findViewById(R.id.status_title)
        statusSubtitle = view.findViewById(R.id.status_subtitle)
        mountedCountTextView = view.findViewById(R.id.mounted_count)
        btnToggleServer = view.findViewById(R.id.btn_toggle_server)
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

        // 用 ServerStateMachine + XposedConnectionState 的 Flow combine 替代 3s 轮询
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ServerStateMachine.state,
                    XposedConnectionState.isConnected
                ) { serverState, xposedConnected ->
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

    private fun updateServiceStatus(
        serverState: ServerState = ServerStateMachine.state.value,
        isXposedConnected: Boolean = XposedConnectionState.isConnected.value
    ) {
        if (!isAdded) return

        val isRunning = serverState == ServerState.RUNNING
        val isManuallyStopped = ServicePreferences.isServiceManuallyStopped
        val rootAvailable = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        val isServiceOpen = isRunning && !isManuallyStopped && rootAvailable && isXposedConnected

        val ctx = requireContext()

        // 1) 手动停止状态（用户意图优先）：显示已暂停
        if (isManuallyStopped) {
            statusTitle?.visibility = android.view.View.GONE
            statusSubtitle?.text = ctx.getString(R.string.service_stopped_manually)
            mountedCountTextView?.visibility = android.view.View.GONE
            btnToggleServer?.text = ctx.getString(R.string.btn_start_service)
            statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
            statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
            )
            return
        }

        val mountedCount = ServicePreferences.srPackages.size
        val totalRules = ServicePreferences.srPackages.sumOf { pkg ->
            ServicePreferences.getPackageSrCount(pkg)
        }

        statusTitle?.visibility = android.view.View.GONE

        // 构建三行状态信息
        val rootStatus = ctx.getString(if (rootAvailable) R.string.root_available else R.string.root_unavailable)
        val serverPid = CleanerClient.service?.serverPid?.toString() ?: "-"
        val xposedStatus = when {
            !isRunning -> ctx.getString(R.string.service_waiting)
            isXposedConnected -> ctx.getString(R.string.xposed_connected)
            else -> ctx.getString(R.string.waiting_media_provider)
        }

        statusSubtitle?.text = buildString {
            appendLine(ctx.getString(R.string.service_status_daemon, serverPid, rootStatus))
            appendLine(ctx.getString(R.string.service_status_xposed, xposedStatus))
            append(ctx.getString(R.string.service_status_mounted, mountedCount, totalRules))
        }

        mountedCountTextView?.visibility = android.view.View.GONE
        btnToggleServer?.text = if (ServicePreferences.isServiceManuallyStopped) {
            ctx.getString(R.string.btn_start_service)
        } else {
            ctx.getString(R.string.btn_stop_service)
        }

        // 状态点
        when {
            isServiceOpen -> {
                // 完全就绪 → 绿色
                statusDot?.setImageResource(R.drawable.ic_baseline_check_circle_24)
                statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.holo_green_dark)
                )
            }
            ServicePreferences.isServiceManuallyStopped -> {
                // 用户手动停止 → 橙色，可启动
                statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
                statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
                )
            }
            else -> {
                // 异常状态（FAILED/STARTING/缺条件）→ 红色，点"停止"重置
                statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
                statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, android.R.color.holo_red_dark)
                )
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
