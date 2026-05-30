package me.gm.cleaner.client.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceRecyclerViewAccessibilityDelegate
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.app.ConfirmationDialog
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ui.storageredirect.MountWizard
import me.gm.cleaner.dao.MountRules
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.settings.PathListPreference
import me.gm.cleaner.settings.PathListPreferenceFragmentCompat
import me.gm.cleaner.util.FileUtils
import me.gm.cleaner.util.fitsSystemWindowInsets
import me.gm.cleaner.util.fixEdgeEffect
import me.gm.cleaner.util.overScrollIfContentScrollsPersistent
import org.json.JSONObject

class MoreOptionsFragment : PreferenceFragmentCompat() {
    private lateinit var shareLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private val notifyPreferencesChangedListener: NotifyServerPreferenceChangeListener
        get() = NotifyServerPreferenceChangeListener()

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

        val enableAtime = findPreference<SwitchPreferenceCompat>(
            getString(me.gm.cleaner.shared.R.string.enable_relatime_key)
        )!!
        enableAtime.onPreferenceChangeListener = notifyPreferencesChangedListener

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

        val openWizardByDefault = findPreference<SwitchPreferenceCompat>(
            getString(R.string.open_wizard_by_default_key)
        )!!

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

            is PathListPreference -> {
                PathListPreferenceFragmentCompat.newInstance(preference.key)
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
