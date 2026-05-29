package me.gm.cleaner.dao

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import me.gm.cleaner.client.ui.storageredirect.WizardAnswers
import me.gm.cleaner.util.toParcelable

// Preference key constants (values match existing R.string values to preserve user data)
private const val OPEN_WIZARD_BY_DEFAULT_KEY = "open_wizard_by_default"
private const val APP_CATEGORY_REPO_KEY = "app_category_repo"
private const val APP_CATEGORY_DEFAULT = "https://raw.githubusercontent.com/MaterialCleaner/AppsTypeMarks/main/"
private const val AUTO_COMPLETE_BY_RECORD_MERGE_KEY = "auto_complete_by_record_merge"
private const val AUTO_COMPLETE_BY_RECORD_RESPECT_KEY = "auto_complete_by_record_respect"
private const val EDIT_MOUNT_RULES_TEMPLATE_KEY = "edit_mount_rules_template"
private const val EDIT_READ_ONLY_TEMPLATE_KEY = "edit_read_only_template"
private const val APPLY_TEMPLATE_ON_PACKAGE_ADDED_KEY = "apply_template_on_package_added"

object ServiceMoreOptionsPreferences {
    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
    }

    val openWizardByDefault: Boolean
        get() = preferences.getBoolean(OPEN_WIZARD_BY_DEFAULT_KEY, false)

    val appCategoryRepo: String
        get() = preferences.getString(
            APP_CATEGORY_REPO_KEY,
            APP_CATEGORY_DEFAULT
        )!!

    val isUsingDefaultRepo: Boolean
        get() = appCategoryRepo == APP_CATEGORY_DEFAULT

    val autoCompleteByRecordMerge: Boolean
        get() = preferences.getBoolean(AUTO_COMPLETE_BY_RECORD_MERGE_KEY, true)

    val autoCompleteByRecordRespect: Boolean
        get() = preferences.getBoolean(AUTO_COMPLETE_BY_RECORD_RESPECT_KEY, true)

    val editMountRulesTemplate: WizardAnswers
        get() = try {
            preferences.getString(EDIT_MOUNT_RULES_TEMPLATE_KEY, null)
                ?.toParcelable()
                ?: WizardAnswers(true)
        } catch (e: Throwable) {
            TempCodeRecords.fixBug("2.0.1")
            preferences.edit {
                remove(EDIT_MOUNT_RULES_TEMPLATE_KEY)
            }
            WizardAnswers(true)
        }

    val editReadOnlyTemplate: Set<String>
        get() = preferences.getStringSet(EDIT_READ_ONLY_TEMPLATE_KEY, emptySet())!!

    val applyTemplateOnPackageAdded: Boolean
        get() = preferences.getBoolean(APPLY_TEMPLATE_ON_PACKAGE_ADDED_KEY, false)
}
