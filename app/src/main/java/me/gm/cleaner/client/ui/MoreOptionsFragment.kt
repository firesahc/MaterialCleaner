package me.gm.cleaner.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.app.ConfirmationDialog
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ui.storageredirect.MountWizard
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.core.storage.redirect.domain.MountRules
import me.gm.cleaner.net.NOTIFICATION_CHANNEL
import me.gm.cleaner.settings.BaseSettingsFragment
import me.gm.cleaner.settings.theme.ThemeUtil
import me.gm.cleaner.starter.Starter
import me.gm.cleaner.util.FileUtils
import me.gm.cleaner.util.FileUtils.toUserId
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
            lifecycleScope.launch {
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
                                    .filter { ServicePreferences.getPackageSrCount(it) == 0 }
                                    .toSet()
                            )
                            addOnPositiveButtonClickListener { checkedApps ->
                                MainScope().launch(Dispatchers.IO) {
                                    ServicePreferences.beginBatchOperation()
                                    for (packageInfo in checkedApps) {
                                        val list = mutableListOf<Pair<String, String>>()
                                        val rules = input.getJSONArray(packageInfo.packageName)
                                        for (i in 0 until rules.length()) {
                                            val rule = rules.getJSONArray(i)
                                            list.add(rule.getString(0) to rule.getString(1))
                                        }
                                        ServicePreferences.putStorageRedirect(
                                            list, listOf(packageInfo.packageName)
                                        )
                                    }
                                    ServicePreferences.endBatchOperation()
                                    CleanerClient.service?.notifySrChanged()
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        addPreferencesFromResource(R.xml.service_more_options_preferences)
        addPreferencesFromResource(R.xml.root_preferences)

        val aggressivelyPromptForReadingMediaFiles = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.shared.R.string.aggressively_prompt_for_reading_media_files_key)
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
                    val installedNonsystemApps = CleanerClient.getInstalledPackages(0).filter {
                        it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
                    }
                    setAllAppsSupplier { installedNonsystemApps }
                    addOnPositiveButtonClickListener { checkedApps ->
                        val readOnlyPaths =
                            ServiceMoreOptionsPreferences.editReadOnlyTemplate.sorted()
                        MainScope().launch(Dispatchers.IO) {
                            ServicePreferences.beginBatchOperation()
                            val selectedApps = checkedApps.mapNotNull { packageInfo ->
                                installedNonsystemApps.firstOrNull { it.packageName == packageInfo.packageName }
                            }
                            for (pi in selectedApps) {
                                val rules = MountRules(
                                    ServicePreferences.getPackageSrZipped(pi.packageName)
                                )
                                val mountedReadOnlyPaths = readOnlyPaths.asSequence()
                                    .map { path ->
                                        rules.getMountedPath(path)
                                    }
                                    .filterNot { path ->
                                        FileUtils.isKnownAppDirPaths(path, pi.packageName)
                                    }
                                    .toList()
                                ServicePreferences.putReadOnly(
                                    mountedReadOnlyPaths, listOf(pi.packageName)
                                )
                            }
                            ServicePreferences.endBatchOperation()
                            CleanerClient.service?.notifyReadOnlyChanged()
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
                    val installedNonSystemApps = CleanerClient.getInstalledPackages(0).filter {
                        it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
                    }
                    setAllAppsSupplier { installedNonSystemApps }
                    addOnPositiveButtonClickListener { checkedApps ->
                        val answers = ServiceMoreOptionsPreferences.editMountRulesTemplate
                        MainScope().launch(Dispatchers.IO) {
                            ServicePreferences.beginBatchOperation()
                            val selectedApps = checkedApps.mapNotNull { packageInfo ->
                                installedNonSystemApps.firstOrNull { it.packageName == packageInfo.packageName }
                            }
                            for (pi in selectedApps) {
                                val wizard = MountWizard(pi)
                                ServicePreferences.putStorageRedirect(
                                    wizard.createRules(answers), listOf(pi.packageName)
                                )
                            }
                            ServicePreferences.endBatchOperation()
                            CleanerClient.service?.notifySrChanged()
                        }
                    }
                }
                .show(childFragmentManager, null)
            true
        }

        val autoLogging = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.shared.R.string.auto_logging_key)
        )!!
        autoLogging.onPreferenceChangeListener = notifyPreferencesChangedListener

        val recordSharedStorage = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.shared.R.string.record_shared_storage_key)
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
            getString(me.gm.cleaner.shared.R.string.record_external_app_specific_storage_key)
        )
        recordExternalAppSpecificStorage?.onPreferenceChangeListener = object :
            NotifyServerPreferenceChangeListener() {

            override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean =
                super.onPreferenceChange(preference, newValue)
        }

        val upsert = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.shared.R.string.upsert_key)
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
