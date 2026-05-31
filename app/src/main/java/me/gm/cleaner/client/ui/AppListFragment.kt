package me.gm.cleaner.client.ui

import android.annotation.SuppressLint
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.HooksBridgeProvider
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
    private var hasRoot: Boolean = false

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

        // Check root once at setup
        checkRootAccess()

        // Initial status update
        updateServiceStatus()

        // Apply system window insets to root layout so content starts below toolbar+tabs+status bar
        view.fitsSystemWindowInsets()

        // Toggle server button — start or stop
        btnToggleServer?.setOnClickListener {
            if (CleanerClient.pingBinder()) {
                stopServer()
            } else {
                startServer()
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
                    }
                }
            }
        }

        // Observe preferences changes → refresh rule count and mounted list
        ServicePreferences.preferencesChangeLiveData.observe(viewLifecycleOwner) {
            viewModel.updateAppsRuleCount()
        }

        // Load initial mounted apps（添加重试等待服务器就绪）
        loadMountedApps(adapter)

        super.onCreateView(inflater, container, savedInstanceState)
        return view
    }

    @SuppressLint("RepeatOnLifecycleWrongUsage")
    override fun onResume() {
        super.onResume()
        // Refresh mounted apps list when returning from mount creation/edit
        viewModel.loadApps()
        // Poll pingBinder() every 3 seconds while resumed
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(3000)
                    if (isAdded) updateServiceStatus()
                }
            }
        }
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

    private fun checkRootAccess() {
        try {
            hasRoot = Shell.getShell().isRoot
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("CleanerTest", "AppListFragment: failed to check root", e)
            hasRoot = false
        }
    }

    private fun updateServiceStatus() {
        if (!isAdded) return

        val isRunning = CleanerClient.pingBinder()
        val isManuallyStopped = ServicePreferences.isServerManuallyStopped

        // 手动停止状态：显示已暂停
        if (!isRunning && isManuallyStopped) {
            val ctx = requireContext()
            statusTitle?.visibility = android.view.View.GONE
            statusSubtitle?.text = ctx.getString(R.string.server_stopped_manually)
            mountedCountTextView?.visibility = android.view.View.GONE
            btnToggleServer?.text = ctx.getString(R.string.btn_start_server)
            statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
            statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, android.R.color.holo_orange_dark)
            )
            return
        }

        val isXposedConnected = HooksBridgeProvider.isMediaProviderConnected()
        val mountedCount = ServicePreferences.srPackages.size
        val totalRules = ServicePreferences.srPackages.sumOf { pkg ->
            ServicePreferences.getPackageSrCount(pkg)
        }

        statusTitle?.visibility = android.view.View.GONE

        // 构建三行状态信息
        val ctx = requireContext()
        val rootStatus = ctx.getString(if (hasRoot) R.string.root_available else R.string.root_unavailable)
        val serverPid = CleanerClient.service?.serverPid?.toString() ?: "-"
        val xposedStatus = when {
            !isRunning -> ctx.getString(R.string.server_waiting)
            isXposedConnected -> ctx.getString(R.string.xposed_connected)
            else -> ctx.getString(R.string.waiting_media_provider)
        }

        statusSubtitle?.text = buildString {
            appendLine(ctx.getString(R.string.server_status_daemon, serverPid, rootStatus))
            appendLine(ctx.getString(R.string.server_status_xposed, xposedStatus))
            append(ctx.getString(R.string.server_status_mounted, mountedCount, totalRules))
        }

        mountedCountTextView?.visibility = android.view.View.GONE
        btnToggleServer?.text = ctx.getString(if (isRunning) R.string.btn_stop_server else R.string.btn_start_server)

        // 只有全部条件满足才显示绿色对勾
        val allReady = isRunning && hasRoot && isXposedConnected
        val context = requireContext()
        if (allReady) {
            statusDot?.setImageResource(R.drawable.ic_baseline_check_circle_24)
            statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, android.R.color.holo_green_dark)
            )
        } else {
            statusDot?.setImageResource(R.drawable.ic_baseline_error_24)
            statusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, android.R.color.holo_red_dark)
            )
        }
    }

    private fun startServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: 清除"停止"标志
                withContext(Dispatchers.Main) {
                    ServicePreferences.isServerManuallyStopped = false
                }

                // Step 2: 杀死旧服务器进程
                val shell = Shell.getShell()
                if (shell.isRoot) {
                    Shell.cmd("ps -A | grep cleaner_server | tr -s ' ' | cut -d' ' -f2 | while read pid; do kill -9 \$pid 2>/dev/null; done").exec()
                    delay(2000)
                }

                // Step 3: 启动新服务器
                Starter.writeDataFiles(requireContext())
                val result = Shell.cmd(Starter.command).exec()
                if (!result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), R.string.toast_start_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Step 4: 等待 Binder 就绪（重试最多 20 次）
                var retries = 0
                while (!CleanerClient.pingBinder() && retries < 20) {
                    delay(500)
                    retries++
                }
                if (!CleanerClient.pingBinder()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), R.string.toast_start_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Step 5: 重载配置并重新挂载（每个核心点带重试）
                val svc = CleanerClient.service
                reloadConfigWithRetry("notifyPreferencesChanged") { svc?.notifyPreferencesChanged() }
                reloadConfigWithRetry("notifySrChanged") { svc?.notifySrChanged() }
                reloadConfigWithRetry("notifyReadOnlyChanged") { svc?.notifyReadOnlyChanged() }

                // Step 6: 重新挂载所有已配置的应用
                val packages = ServicePreferences.srPackages.toTypedArray()
                if (packages.isNotEmpty()) {
                    reloadConfigWithRetry("remount") { svc?.remount(packages) }
                }

                // Step 7: 重载应用列表并更新 UI
                withContext(Dispatchers.Main) {
                    viewModel.loadApps()
                    updateServiceStatus()
                    val msg = if (packages.isNotEmpty()) {
                        requireContext().getString(R.string.toast_server_started_n_mounted, packages.size)
                    } else {
                        requireContext().getString(R.string.toast_server_started_empty)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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
                // 设置"已停止"标志 → 阻止自动重启
                ServicePreferences.isServerManuallyStopped = true

                // 优雅退出再强制杀死
                CleanerClient.exit()
                CleanerClient.resetConnection()

                // 备份：root shell 强制杀死
                val shell = Shell.getShell()
                if (shell.isRoot) {
                    Shell.cmd("ps -A | grep cleaner_server | tr -s ' ' | cut -d' ' -f2 | while read pid; do kill -9 \$pid 2>/dev/null; done").exec()
                }

                updateServiceStatus()
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
            if (ServicePreferences.isServerManuallyStopped) {
                adapter.submitList(emptyList())
                return@launch
            }

            // 等待服务器就绪（最长重试 20 次 = ~10 秒）
            var retries = 0
            while (!CleanerClient.pingBinder() && retries < 20) {
                delay(500)
                retries++
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
