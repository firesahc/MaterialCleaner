package me.gm.cleaner.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

// Preference key constants shared by XML preferences and runtime config snapshots.
private const val DENY_LIST_KEY = "deny_list"
private const val SORT_BY_KEY = "sort_by"
private const val MENU_RULE_COUNT_KEY = "rule_count"
private const val MENU_MOUNT_STATE_KEY = "mount_state"
private const val MENU_HIDE_SYSTEM_APP_KEY = "hide_system_app"
private const val MENU_HIDE_DISABLED_APP_KEY = "hide_disabled_app"
private const val MENU_HIDE_NO_STORAGE_PERMISSIONS_KEY = "hide_no_storage_permissions"
private const val MENU_HIDE_APP_SPECIFIC_STORAGE_KEY = "hide_app_specific_storage"
private const val SERVICE_MANUALLY_STOPPED_KEY = "service_manually_stopped"
private const val AGGRESSIVELY_PROMPT_FOR_READING_MEDIA_FILES_KEY = "aggressively_prompt_for_reading_media_files"
private const val AUTO_LOGGING_KEY = "auto_logging"
private const val RECORD_SHARED_STORAGE_KEY = "record_shared_storage"
private const val RECORD_EXTERNAL_APP_SPECIFIC_STORAGE_KEY = "record_external_app_specific_storage"
private const val UPSERT_KEY = "upsert"

object ServicePreferences {
    private val TAG = "ServicePreferences"
    const val SORT_BY_NAME: Int = 0
    const val SORT_BY_UPDATE_TIME: Int = 1
    @Volatile
    private var broadcasting: Boolean = false
    private val _preferencesChangeLiveData: MutableLiveData<SharedPreferences> = MutableLiveData()
    val preferencesChangeLiveData: LiveData<SharedPreferences>
        get() = _preferencesChangeLiveData
    lateinit var preferences: SharedPreferences
        private set

    private lateinit var denylistFile: File
    private var denylistCache: List<String>? = null

    // @App
    // @Server
    fun init(context: Context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        denylistFile = context.filesDir.resolve(DENY_LIST_KEY)
        denylistCache = null
        LegacyStoragePolicyAdapter.init(context.filesDir)
        ConfiguredPolicyStoreProvider.initialize(context.filesDir)
    }

    /** 供纯策略投影测试及早期启动诊断判断普通偏好是否已就绪。 */
    fun isInitialized(): Boolean = ::preferences.isInitialized

    private fun notifyListeners() {
        if (broadcasting) {
            return
        }
        broadcasting = true
        _preferencesChangeLiveData.postValue(preferences)
        broadcasting = false
    }

    // APP LIST CONFIG
    // @App
    var sortBy: Int
        get() = preferences.getInt(SORT_BY_KEY, SORT_BY_NAME)
        set(value) {
            preferences.edit {
                putInt(SORT_BY_KEY, value)
            }
            notifyListeners()
        }

    // @App
    var ruleCount: Boolean
        get() = preferences.getBoolean(MENU_RULE_COUNT_KEY, true)
        set(value) = putBoolean(MENU_RULE_COUNT_KEY, value)

    // @App
    var mountState: Boolean
        get() = preferences.getBoolean(MENU_MOUNT_STATE_KEY, true)
        set(value) = putBoolean(MENU_MOUNT_STATE_KEY, value)

    // @App
    var isHideSystemApp: Boolean
        get() = preferences.getBoolean(MENU_HIDE_SYSTEM_APP_KEY, true)
        set(value) = putBoolean(MENU_HIDE_SYSTEM_APP_KEY, value)

    // @App
    var isHideDisabledApp: Boolean
        get() = preferences.getBoolean(MENU_HIDE_DISABLED_APP_KEY, true)
        set(value) = putBoolean(MENU_HIDE_DISABLED_APP_KEY, value)

    // @App
    var isHideNoStoragePermissionApp: Boolean
        get() = preferences.getBoolean(MENU_HIDE_NO_STORAGE_PERMISSIONS_KEY, false)
        set(value) = putBoolean(MENU_HIDE_NO_STORAGE_PERMISSIONS_KEY, value)

    // @App
    var isHideAppSpecificStorage: Boolean
        get() = preferences.getBoolean(MENU_HIDE_APP_SPECIFIC_STORAGE_KEY, false)
        set(value) = putBoolean(MENU_HIDE_APP_SPECIFIC_STORAGE_KEY, value)

    // @App
    var isServiceManuallyStopped: Boolean
        get() = preferences.getBoolean(SERVICE_MANUALLY_STOPPED_KEY, true)
        set(value) {
            preferences.edit { putBoolean(SERVICE_MANUALLY_STOPPED_KEY, value) }
            notifyListeners()
        }

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit {
            putBoolean(key, value)
        }
        notifyListeners()
    }

    // STORAGE REDIRECT：委托旧策略适配层，行为不变。
    // @App
    @Synchronized
    fun putStorageRedirect(rawRules: List<Pair<String, String>>, packageNames: List<String>) =
        LegacyStoragePolicyAdapter.putStorageRedirect(rawRules, packageNames)

    // @App
    @Synchronized
    fun removeStorageRedirect(packageNames: List<String>) =
        LegacyStoragePolicyAdapter.removeStorageRedirect(packageNames)

    // @App
    fun getUninstalledSrPackages(installedPackages: Set<String>): List<String> =
        LegacyStoragePolicyAdapter.getUninstalledSrPackages(installedPackages)

    // @App
    // @Server
    val srPackages: Set<String>
        get() = LegacyStoragePolicyAdapter.srPackages

    // @App
    // @Server
    val srRulesCount: Int
        get() = LegacyStoragePolicyAdapter.srRulesCount

    // @App
    // @Server
    fun getPackageSrCount(packageName: String): Int =
        LegacyStoragePolicyAdapter.getPackageSrCount(packageName)

    // @App
    // @Server
    fun getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> =
        LegacyStoragePolicyAdapter.getPackageSr(packageName, userId)

    // @App
    // @Server
    fun getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> =
        LegacyStoragePolicyAdapter.getPackageSrZipped(packageName, userId)

    // @Server
    @Synchronized
    fun invalidateSrCache() = LegacyStoragePolicyAdapter.invalidateSrCache()

    // @App
    @Synchronized
    fun beginBatchOperation() = LegacyStoragePolicyAdapter.beginBatchOperation()

    // @App
    @Synchronized
    fun endBatchOperation() = LegacyStoragePolicyAdapter.endBatchOperation()

    // @App
    // @Server
    fun readRawStorageRedirect(): String = LegacyStoragePolicyAdapter.readRawStorageRedirect()

    // READ ONLY：委托旧策略适配层，行为不变。
    // @App
    @Synchronized
    fun putReadOnly(rawRules: List<String>, packageNames: List<String>) =
        LegacyStoragePolicyAdapter.putReadOnly(rawRules, packageNames)

    // @App
    @Synchronized
    fun removeReadOnly(packageNames: List<String>) =
        LegacyStoragePolicyAdapter.removeReadOnly(packageNames)

    // @App
    fun getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> =
        LegacyStoragePolicyAdapter.getUninstalledReadOnlyPackages(installedPackages)

    // @App
    fun getPackageReadOnly(packageName: String, userId: Int = 0): List<String> =
        LegacyStoragePolicyAdapter.getPackageReadOnly(packageName, userId)

    // @Server
    fun getAllReadOnly(): Map<String, List<String>> =
        LegacyStoragePolicyAdapter.getAllReadOnly()

    // @Server
    @Synchronized
    fun invalidateReadOnlyCache() = LegacyStoragePolicyAdapter.invalidateReadOnlyCache()

    // @App
    // @Server
    fun readRawReadOnly(): String = LegacyStoragePolicyAdapter.readRawReadOnly()

    // FILE SYSTEM RECORD
    // @Server
    var denylist: List<String>
        @Synchronized
        get() = try {
            if (denylistCache == null) {
                denylistCache = denylistFile.readText(Charsets.UTF_8)
                    .lineSequence()
                    .filterNot { it.isBlank() }
                    .toList()
            }
            denylistCache!!
        } catch (e: IOException) {
            if (e !is FileNotFoundException) {
                Log.w(TAG, "Failed to read denylist", e)
            }
            emptyList()
        }
        @Synchronized
        set(value) {
            try {
                denylistCache = value
                LegacyStoragePolicyAdapter.writeUtf8Atomically(denylistFile, value.joinToString("\n"))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write denylist", e)
            }
        }

    // EXTRA
    // @App
    // @Server
    val aggressivelyPromptForReadingMediaFiles: Boolean
        get() = preferences.getBoolean(AGGRESSIVELY_PROMPT_FOR_READING_MEDIA_FILES_KEY, true)

    // @App
    // @Server
    val autoLogging: Boolean
        get() = preferences.getBoolean(AUTO_LOGGING_KEY, true)

    // @App
    // @Server
    val recordSharedStorage: Boolean
        get() = preferences.getBoolean(RECORD_SHARED_STORAGE_KEY, false)

    // @App
    // @Server
    val recordExternalAppSpecificStorage: Boolean
        get() = recordSharedStorage && preferences.getBoolean(RECORD_EXTERNAL_APP_SPECIFIC_STORAGE_KEY, false)

    // @App
    // @Server
    val upsert: Boolean
        get() = preferences.getBoolean(UPSERT_KEY, true)
}
