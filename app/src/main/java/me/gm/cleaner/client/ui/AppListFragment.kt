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
        val list = view.findViewById<RecyclerView>(R.id.list)

        // Setup RecyclerView
        val adapter = AppListAdapter(this)
        list.adapter = adapter
        list.layoutManager = GridLayoutManager(requireContext(), 1)
        list.setHasFixedSize(true)

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
                        }
                        else -> {}
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
                Starter.writeDataFiles(requireContext())
                val result = Shell.cmd(Starter.command).exec()
                if (result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), R.string.toast_starting, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "startServer: command failed: ${result.err.joinToString()}")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "startServer: exception", e)
            }
        }
    }

    private fun stopServer() {
        lifecycleScope.launch {
            try {
                CleanerClient.exit()
                CleanerClient.resetConnection()
                updateServiceStatus()
                Toast.makeText(requireContext(), R.string.toast_stopped, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "stopServer: exception", e)
            }
        }
    }

    private fun loadMountedApps(adapter: AppListAdapter) {
        lifecycleScope.launch {
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
                    emptyList()
                }
            }
            val mounted = loaded.filter { it.mountRulesCount > 0 }
            adapter.submitList(mounted)
        }
    }
}
