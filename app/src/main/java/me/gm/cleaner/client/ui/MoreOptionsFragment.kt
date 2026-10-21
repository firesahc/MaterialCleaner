package me.gm.cleaner.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceRecyclerViewAccessibilityDelegate
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.internal.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.app.ConfirmationDialog
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ui.storageredirect.MountWizard
import me.gm.cleaner.client.ui.storageredirect.RedirectReachabilityAnalyzer
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.core.config.ConfiguredPolicyStoreProvider
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.config.BatchCommitResult
import me.gm.cleaner.core.config.StoragePolicyBatchEdit
import me.gm.cleaner.core.config.getPackageSrCount
import me.gm.cleaner.core.config.getPackageSrZipped
import me.gm.cleaner.net.NOTIFICATION_CHANNEL
import me.gm.cleaner.settings.BaseSettingsFragment
import me.gm.cleaner.settings.theme.ThemeUtil
import me.gm.cleaner.starter.Starter
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.core.common.RuntimeFileUtils.toUserId
import me.gm.cleaner.util.PermissionUtils
import me.gm.cleaner.util.RequesterFragment
import me.gm.cleaner.util.fitsSystemWindowInsets
import me.gm.cleaner.util.fixEdgeEffect
import me.gm.cleaner.util.overScrollIfContentScrollsPersistent
import org.json.JSONObject
import java.util.Locale

class MoreOptionsFragment : BaseSettingsFragment() {
    private lateinit var shareLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private val notifyPreferencesChangedListener: NotifyServerPreferenceChangeListener
        get() = NotifyServerPreferenceChangeListener()

    private var logStatsPollingJob: kotlinx.coroutines.Job? = null
    private var lastStatsAvailable: Boolean = true

    class PostNotificationRequesterFragment : RequesterFragment() {
        @SuppressLint("InlinedApi")
        override val requiredPermissions: Array<String> =
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        private val preference: SwitchPreferenceCompat by lazy {
            (parentFragment as MoreOptionsFragment)
                .findPreference(getString(R.string.post_notification_key))!!
        }
        private var rationaleShowed: Boolean = false

        override fun dispatchRequestPermissions(
            permissions: Array<String>, savedInstanceState: Bundle?
        ) {
            rationaleShowed = false
            super.dispatchRequestPermissions(permissions, savedInstanceState)
        }

        override fun onRequestPermissionsSuccess(
            permissions: Set<String>, savedInstanceState: Bundle?
        ) {
            if (permissions.contains(Manifest.permission.POST_NOTIFICATIONS)) {
                preference.isChecked = true
            }
        }

        override fun onRequestPermissionsFailure(
            shouldShowRationale: Set<String>, permanentlyDenied: Set<String>,
            haveAskedUser: Boolean, savedInstanceState: Bundle?
        ) {
            if (shouldShowRationale.isNotEmpty()) {
                if (!haveAskedUser) {
                    rationaleShowed = true
                    onRequestPermissions(shouldShowRationale.toTypedArray(), savedInstanceState)
                }
            } else if (permanentlyDenied.isNotEmpty() && !rationaleShowed) {
                PermissionUtils.startNotificationSettings(requireContext())
            }
        }
    }

    private open inner class NotifyServerPreferenceChangeListener :
        Preference.OnPreferenceChangeListener {

        override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean = try {
            true
        } finally {
            lifecycleScope.launch(Dispatchers.IO) {
                delay(100)
                preferenceManager.sharedPreferences?.edit(true) { }
                CleanerClient.service?.notifyPreferencesChanged()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        shareLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/*")
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        val bb = ServicePreferences.readRawStorageRedirect().toByteArray()
                        it.write(bb)
                    }
                }
            }
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val input = requireContext().contentResolver.openInputStream(uri)!!.use {
                        JSONObject(String(it.readBytes()))
                    }
                    val inputPackageNames = input.keys().asSequence().toList()
                    AppPickerDialog()
                        .apply {
                            val inputApps = inputPackageNames.mapNotNull {
                                CleanerClient.service?.getPackageInfo(it, 0)
                            }
                            setAllAppsSupplier { inputApps }
                            setSelection(
                                inputPackageNames.asSequence()
                                    .filter { ConfiguredPolicyStoreProvider.instance.getPackageSrCount(it) == 0 }
                                    .toSet()
                            )
                            addOnPositiveButtonClickListener { checkedApps ->
                                MainScope().launch(Dispatchers.IO) {
                                    val tx = StoragePolicyBatchEdit()
                                    for (packageInfo in checkedApps) {
                                        val list = mutableListOf<Pair<String, String>>()
                                        val rules = input.getJSONArray(packageInfo.packageName)
                                        for (i in 0 until rules.length()) {
                                            val rule = rules.getJSONArray(i)
                                            list.add(rule.getString(0) to rule.getString(1))
                                        }
                                        tx.putRedirect(list, listOf(packageInfo.packageName))
                                    }
                                    val r = tx.commitStructured()
                                    when (r.overall) {
                                        BatchCommitResult.Overall.SUCCESS -> CleanerClient.service?.notifySrChanged()
                                        BatchCommitResult.Overall.FAILURE -> {
                                            Log.e("MC/Policy", "import template batch commit failed: stageFailed=${r.stageFailed}, redirect=${r.redirect?.error}")
                                            return@launch
                                        }
                                        BatchCommitResult.Overall.PARTIAL -> {
                                            Log.e("MC/Policy", "unexpected partial commit: redirect=${r.redirect}, readOnly=${r.readOnly}")
                                            return@launch
                                        }
                                    }
                                }
                            }
                        }
                        .show(childFragmentManager, null)
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main.immediate) {
                        Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shareLauncher.unregister()
        importLauncher.unregister()
    }

    override fun onResume() {
        super.onResume()
        startLogStatsPolling()
    }

    override fun onPause() {
        super.onPause()
        stopLogStatsPolling()
    }

    private fun startLogStatsPolling() {
        if (logStatsPollingJob?.isActive == true) return
        logStatsPollingJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    // 同步 Binder 调用及目录遍历放在 IO 线程，避免阻塞 UI 主线程。
                    val stats = withContext(Dispatchers.IO) {
                        CleanerClient.service?.getLogDirStats()
                    }
                    if (stats != null) {
                        val available = stats.getBoolean("available")
                        val totalBytes = stats.getLong("totalBytes")
                        val fileCount = stats.getInt("fileCount")
                        updateAutoLoggingSummary(available, totalBytes, fileCount)
                        if (available != lastStatsAvailable) {
                            Log.d("MC/MoreOptions", "Log stats available changed: $available")
                            lastStatsAvailable = available
                        }
                    } else {
                        // Cleaner service is not bound/running: show an actionable hint
                        // instead of the generic "temporarily unavailable" message.
                        updateAutoLoggingServiceUnavailable()
                        if (lastStatsAvailable) {
                            Log.w("MC/MoreOptions", "Cleaner service unavailable, cannot fetch log stats")
                            lastStatsAvailable = false
                        }
                    }
                } catch (e: CancellationException) {
                    // 页面离开或协程被取消，不应当作为统计故障处理
                    throw e
                } catch (e: Exception) {
                    Log.w("MC/MoreOptions", "Failed to fetch log stats", e)
                    updateAutoLoggingSummary(false, 0L, 0)
                    if (lastStatsAvailable) {
                        lastStatsAvailable = false
                    }
                }
                delay(10_000)
            }
        }
    }

    private fun stopLogStatsPolling() {
        logStatsPollingJob?.cancel()
        logStatsPollingJob = null
    }

    private fun updateAutoLoggingSummary(available: Boolean, totalBytes: Long, fileCount: Int) {
        val autoLogging = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.auto_logging_key)
        ) ?: return

        val baseSummary = getString(R.string.auto_logging_summary)
        val statsLine = if (available) {
            val sizeStr = android.text.format.Formatter.formatShortFileSize(
                requireContext(), totalBytes
            )
            getString(R.string.auto_logging_stats_summary, sizeStr, fileCount)
        } else {
            getString(R.string.log_stats_unavailable)
        }
        autoLogging.summary = baseSummary + "\n" + statsLine
    }

    private fun updateAutoLoggingServiceUnavailable() {
        val autoLogging = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.auto_logging_key)
        ) ?: return
        val baseSummary = getString(R.string.auto_logging_summary)
        autoLogging.summary = baseSummary + "\n" + getString(R.string.log_stats_service_unavailable)
    }

    /**
     * 呈现手动检查更新的结果。
     *
     * 三种结局各自给出可见反馈：已是最新、检查失败、发现新版本（当场弹窗，
     * 而不是把用户丢给通知栏）。
     */
    private fun showUpdateCheckResult(result: me.gm.cleaner.net.UpdateChecker.UpdateCheckResult) {
        when (result) {
            is me.gm.cleaner.net.UpdateChecker.UpdateCheckResult.UpToDate -> {
                Snackbar.make(
                    requireView(),
                    getString(R.string.update_check_up_to_date, result.current),
                    Snackbar.LENGTH_SHORT,
                ).show()
            }

            is me.gm.cleaner.net.UpdateChecker.UpdateCheckResult.Failed -> {
                Snackbar.make(
                    requireView(),
                    getString(R.string.update_check_failed),
                    Snackbar.LENGTH_LONG,
                ).show()
            }

            is me.gm.cleaner.net.UpdateChecker.UpdateCheckResult.Available -> {
                val release = result.release
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.new_version_available, release.tagName))
                    .setMessage(release.body?.take(600))
                    .setPositiveButton(R.string.click_to_view_release) { _, _ ->
                        openReleasePage(release.htmlUrl)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** 打开发布页；失败时提示而不抛出。 */
    private fun openReleasePage(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
            )
        }.onFailure {
            Snackbar.make(
                requireView(),
                getString(R.string.update_check_failed),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        addPreferencesFromResource(R.xml.service_more_options_preferences)

        val aggressivelyPromptForReadingMediaFiles = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.aggressively_prompt_for_reading_media_files_key)
        )!!
        aggressivelyPromptForReadingMediaFiles.onPreferenceChangeListener = object :
            NotifyServerPreferenceChangeListener() {

            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean =
                if (!CleanerClient.pingBinder()) {
                    ConfirmationDialog
                        .newInstance(getString(R.string.no_root_access))
                        .show(childFragmentManager, null)
                    false
                } else {
                    super.onPreferenceChange(preference, newValue)
                }
        }

        findPreference<Preference>(getString(R.string.share_key))?.setOnPreferenceClickListener {
            shareLauncher.launch("storage_redirect")
            true
        }

        findPreference<Preference>(getString(R.string.import_key))?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("*/*"))
            true
        }

        val applyReadOnlyTemplateTo = findPreference<Preference>(
            getString(R.string.apply_read_only_template_to_key)
        )
        applyReadOnlyTemplateTo?.setOnPreferenceClickListener {
            AppPickerDialog()
                .apply {
                    val installedNonsystemApps = CleanerClient.getInstalledPackagesOrNull(0)?.filter {
                        it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
                    } ?: emptyList()
                    setAllAppsSupplier { installedNonsystemApps }
                    addOnPositiveButtonClickListener { checkedApps ->
                        val readOnlyPaths =
                            ServiceMoreOptionsPreferences.editReadOnlyTemplate.sorted()
                        MainScope().launch(Dispatchers.IO) {
                            val tx = StoragePolicyBatchEdit()
                            val selectedApps = checkedApps.mapNotNull { packageInfo ->
                                installedNonsystemApps.firstOrNull { it.packageName == packageInfo.packageName }
                            }
                            for (pi in selectedApps) {
                                val rules = ConfiguredPolicyStoreProvider.instance
                                    .getPackageSrZipped(pi.packageName)
                                val mountedReadOnlyPaths = readOnlyPaths.asSequence()
                                    .map { path ->
                                        RedirectReachabilityAnalyzer.mountedPath(rules, path)
                                    }
                                    .filterNot { path ->
                                        RuntimeFileUtils.isKnownAppDirPaths(path, pi.packageName)
                                    }
                                    .toList()
                                tx.putReadOnly(mountedReadOnlyPaths, listOf(pi.packageName))
                            }
                            val r = tx.commitStructured()
                            when (r.overall) {
                                BatchCommitResult.Overall.SUCCESS -> CleanerClient.service?.notifyReadOnlyChanged()
                                BatchCommitResult.Overall.FAILURE -> {
                                    Log.e("MC/Policy", "read-only template batch commit failed: stageFailed=${r.stageFailed}, readOnly=${r.readOnly?.error}")
                                    return@launch
                                }
                                BatchCommitResult.Overall.PARTIAL -> {
                                    Log.e("MC/Policy", "unexpected partial commit: redirect=${r.redirect}, readOnly=${r.readOnly}")
                                    return@launch
                                }
                            }
                        }
                    }
                }
                .show(childFragmentManager, null)
            true
        }

        val applyMountRulesTemplateTo = findPreference<Preference>(
            getString(R.string.apply_mount_rules_template_to_key)
        )
        applyMountRulesTemplateTo?.setOnPreferenceClickListener {
            AppPickerDialog()
                .apply {
                    val installedNonSystemApps = CleanerClient.getInstalledPackagesOrNull(0)?.filter {
                        it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
                    } ?: emptyList()
                    setAllAppsSupplier { installedNonSystemApps }
                    addOnPositiveButtonClickListener { checkedApps ->
                        val answers = ServiceMoreOptionsPreferences.editMountRulesTemplate
                        MainScope().launch(Dispatchers.IO) {
                            val tx = StoragePolicyBatchEdit()
                            val selectedApps = checkedApps.mapNotNull { packageInfo ->
                                installedNonSystemApps.firstOrNull { it.packageName == packageInfo.packageName }
                            }
                            for (pi in selectedApps) {
                                val wizard = MountWizard(pi)
                                tx.putRedirect(wizard.createRules(answers), listOf(pi.packageName))
                            }
                            val r = tx.commitStructured()
                            when (r.overall) {
                                BatchCommitResult.Overall.SUCCESS -> CleanerClient.service?.notifySrChanged()
                                BatchCommitResult.Overall.FAILURE -> {
                                    Log.e("MC/Policy", "mount template batch commit failed: stageFailed=${r.stageFailed}, redirect=${r.redirect?.error}")
                                    return@launch
                                }
                                BatchCommitResult.Overall.PARTIAL -> {
                                    Log.e("MC/Policy", "unexpected partial commit: redirect=${r.redirect}, readOnly=${r.readOnly}")
                                    return@launch
                                }
                            }
                        }
                    }
                }
                .show(childFragmentManager, null)
            true
        }

        val autoLogging = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.auto_logging_key)
        )!!
        autoLogging.onPreferenceChangeListener = notifyPreferencesChangedListener

        val recordSharedStorage = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.record_shared_storage_key)
        )
        recordSharedStorage?.onPreferenceChangeListener = object :
            NotifyServerPreferenceChangeListener() {

            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean =
                if (!CleanerClient.pingBinder()) {
                    ConfirmationDialog
                        .newInstance(getString(R.string.no_root_access))
                        .show(childFragmentManager, null)
                    false
                } else {
                    super.onPreferenceChange(preference, newValue)
                }
        }

        val recordExternalAppSpecificStorage = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.record_external_app_specific_storage_key)
        )
        recordExternalAppSpecificStorage?.onPreferenceChangeListener = object :
            NotifyServerPreferenceChangeListener() {

            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean =
                super.onPreferenceChange(preference, newValue)
        }

        // 决策 D1：BPF 拦截范围开关，属“记录外部应用专属目录”的子开关。
        // 走 Notify 链（偏好层真相 → 服务端投影 → redirect_policy 快照 → Hook Cache 透传），
        // 不在此直调 JNI。
        val fuseBpfBlockAll = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.fuse_bpf_block_all_key)
        )
        fuseBpfBlockAll?.onPreferenceChangeListener = object :
            NotifyServerPreferenceChangeListener() {

            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean =
                super.onPreferenceChange(preference, newValue)
        }

        val upsert = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.R.string.upsert_key)
        )
        upsert?.onPreferenceChangeListener = notifyPreferencesChangedListener

        bindAppPreferences()
    }

    @SuppressLint("RestrictedApi")
    private fun bindAppPreferences() {
        val isStartOnBoot = findPreference<Preference>(getString(R.string.start_on_boot_key))!!
        isStartOnBoot.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !Utils.isRootImpossible() && Process.myUid().toUserId() == 0
        isStartOnBoot.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                try {
                    if (newValue as Boolean) {
                        Starter.writeSourceDir(requireContext())
                    } else {
                        Starter.deleteSourceDir(requireContext())
                    }
                } catch (e: Throwable) {
                    Snackbar
                        .make(requireView(), e.message.toString(), Snackbar.LENGTH_SHORT)
                        .show()
                    return@OnPreferenceChangeListener false
                }
                true
            }

        val postNotification =
            findPreference<SwitchPreferenceCompat>(getString(R.string.post_notification_key))!!
        postNotification.isVisible = true
        postNotification.isChecked = RootPreferences.isPostNotification &&
                PermissionUtils.checkSelfPostNotificationPermission(
                    requireContext(), NOTIFICATION_CHANNEL
                )
        postNotification.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val hasPermission = PermissionUtils.checkSelfPostNotificationPermission(
                    requireContext(), NOTIFICATION_CHANNEL
                )
                if (newValue as Boolean && !hasPermission) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionUtils.requestPermissions(
                            childFragmentManager, PostNotificationRequesterFragment()
                        )
                    } else {
                        PermissionUtils.startNotificationSettings(requireContext())
                    }
                }
                hasPermission
            }

        val checkUpdate = findPreference<Preference>(getString(R.string.check_update_key))!!
        checkUpdate.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            // 手动检查：当场反馈结果，不受自动路径"已通知过"的去重约束——
            // 用户主动询问时必须得到回答，否则与点击无响应无异。
            checkUpdate.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val result = me.gm.cleaner.net.UpdateChecker.check()
                withContext(Dispatchers.Main) {
                    checkUpdate.isEnabled = true
                    showUpdateCheckResult(result)
                }
            }
            true
        }

        val about = findPreference<Preference>(getString(R.string.about_key))!!
        about.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(
                    "${getString(R.string.app_name)}\n" +
                            getString(R.string.about_version, me.gm.cleaner.BuildConfig.VERSION_NAME) +
                            "\n" + getString(R.string.about_project_url)
                )
                .setPositiveButton(R.string.open_project_url) { _, _ ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("https://github.com/firesahc/MaterialCleaner")
                    }
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        val language = findPreference<ListPreference>(getString(R.string.language_key))!!
        language.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                ActivityCompat.recreate(requireActivity())
                true
            }
        val entries = language.entries
        val userLocale = RootPreferences.locale
        val isFollowSystem = "SYSTEM" == language.value
        var summary: String? = null
        for (i in 1 until entries.size) {
            val locale = Locale.forLanguageTag(entries[i].toString())
            val localeName = if (!locale.script.isNullOrEmpty()) {
                locale.getDisplayScript(locale)
            } else {
                locale.getDisplayName(locale)
            }
            val localeNameUser = if (!locale.script.isNullOrEmpty()) {
                locale.getDisplayScript(userLocale)
            } else {
                locale.getDisplayName(userLocale)
            }
            if (!isFollowSystem && localeName == localeNameUser) {
                summary = localeName
                entries[i] = localeName
            } else {
                entries[i] = "$localeName - $localeNameUser"
            }
        }
        language.summary = if (isFollowSystem) {
            getString(R.string.follow_system)
        } else {
            summary
        }

        findPreference<Preference>(getString(R.string.theme_color_key))?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                ActivityCompat.recreate(requireActivity())
                true
            }

        findPreference<Preference>(getString(R.string.theme_m3_key))?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                ActivityCompat.recreate(requireActivity())
                true
            }

        findPreference<Preference>(getString(R.string.dark_theme_key))?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (RootPreferences.preferences.getString(
                        getString(R.string.dark_theme_key), ThemeUtil.MODE_NIGHT_FOLLOW_SYSTEM
                    ) != newValue
                ) {
                    ActivityCompat.recreate(requireActivity())
                }
                true
            }

        findPreference<Preference>(getString(R.string.black_dark_theme_key))?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                ActivityCompat.recreate(requireActivity())
                true
            }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    override fun onCreateRecyclerView(
        inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?
    ): RecyclerView {
        val list = inflater.inflate(
            R.layout.service_preference_recyclerview, parent, false
        ) as RecyclerView
        list.layoutManager = onCreateLayoutManager()
        list.setAccessibilityDelegateCompat(PreferenceRecyclerViewAccessibilityDelegate(list))
        list.fixEdgeEffect()
        list.overScrollIfContentScrollsPersistent()
        list.fitsSystemWindowInsets(fixScroll = savedInstanceState == null)
        return list
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val f = when (preference) {
            is EditMountRulesTemplatePreference -> {
                EditMountRulesTemplatePreferenceFragmentCompat.newInstance(preference.key)
            }

            else -> {
                super.onDisplayPreferenceDialog(preference)
                return
            }
        }
        f.setTargetFragment(this, 0)
        f.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
    }

    companion object {
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
    }
}
