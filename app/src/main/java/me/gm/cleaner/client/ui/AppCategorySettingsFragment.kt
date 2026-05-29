package me.gm.cleaner.client.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ui.storageredirect.MountWizard
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.net.OnlineAppCategory
import me.gm.cleaner.net.Website
import me.gm.cleaner.settings.MaterialEditTextPreferenceDialogFragmentCompat
import me.gm.cleaner.util.FileUtils
import me.gm.cleaner.util.fitsSystemWindowInsets
import me.gm.cleaner.util.fixEdgeEffect
import me.gm.cleaner.util.overScrollIfContentScrollsPersistent
import me.gm.cleaner.util.startActivitySafe

class AppCategorySettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        addPreferencesFromResource(R.xml.app_category_preferences)

        findPreference<EditTextPreference>(getString(R.string.app_category_repo_key))?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference, newValue ->
                if ((newValue as String).isEmpty()) {
                    (preference as EditTextPreference).text =
                        getString(R.string.app_category_default)
                    return@OnPreferenceChangeListener false
                }
                true
            }

        findPreference<Preference>(getString(R.string.app_category_refresh_key))?.setOnPreferenceClickListener {
            AppCategoryCacheSyncDialog()
                .show(childFragmentManager, null)
            true
        }

        findPreference<Preference>(getString(R.string.upload_app_category_key))?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                val downloadApps = linkedMapOf<String, List<String>>()
                withContext(Dispatchers.Default) {
                    val downloadDir =
                        FileUtils.externalStorageDir.resolve(Environment.DIRECTORY_DOWNLOADS).path
                    for (packageName in ServicePreferences.srPackages) {
                        val downloadDirsOfTheApp = ServicePreferences
                            .getPackageSrZipped(packageName)
                            .asSequence()
                            .filter { (source, target) ->
                                FileUtils.startsWith(downloadDir, source)
                            }
                            .filterNot {
                                OnlineAppCategory.buildURL(requireContext(), packageName)
                                    .hasNonNullCache()
                            }
                            .unzip()
                            .second
                        if (downloadDirsOfTheApp.isNotEmpty()) {
                            downloadApps[packageName] = downloadDirsOfTheApp
                        }
                    }
                }
                AppCategoryUploadDialog
                    .newInstance(downloadApps)
                    .show(childFragmentManager, null)
            }
            true
        }

        findPreference<Preference>(getString(R.string.app_category_repo_correct_errors_key))?.setOnPreferenceClickListener {
            startActivitySafe(
                Intent(Intent.ACTION_VIEW).apply {
                    data = Website.appsTypeMarksRepo.toUri()
                }
            )
            true
        }

        findPreference<Preference>(getString(R.string.apply_online_rules_to_key))?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                AppPickerDialog()
                    .apply {
                        val ruledApps =
                            CleanerClient.getInstalledPackages(0).filter { packageInfo ->
                                OnlineAppCategory
                                    .buildURL(
                                        this@AppCategorySettingsFragment.requireContext(),
                                        packageInfo.packageName
                                    )
                                    .hasNonNullCache()
                            }
                        setAllAppsSupplier { ruledApps }
                        addOnPositiveButtonClickListener { checkedApps ->
                            val context = requireParentFragment().requireContext()
                            MainScope().launch(Dispatchers.IO) {
                                ServicePreferences.beginBatchOperation()
                                val selectedApps = checkedApps.mapNotNull { packageInfo ->
                                    ruledApps.firstOrNull { it.packageName == packageInfo.packageName }
                                }
                                for (pi in selectedApps) {
                                    val wizard = MountWizard(pi)
                                    val answers = wizard.retrodictAnswers(
                                        ServicePreferences.getPackageSrZipped(pi.packageName)
                                    )
                                    OnlineAppCategory.fetch(context, pi)
                                        .onSuccess { appCategory ->
                                            appCategory ?: return@onSuccess
                                            wizard.answerBasedOnRecord(
                                                answers, emptyList(), appCategory
                                            )
                                        }
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
            }
            true
        }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?
    ): RecyclerView {
        val list = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        list.fixEdgeEffect()
        list.overScrollIfContentScrollsPersistent()
        list.fitsSystemWindowInsets()
        return list
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val f = when (preference) {
            is EditTextPreference -> {
                MaterialEditTextPreferenceDialogFragmentCompat.newInstance(preference.key)
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
