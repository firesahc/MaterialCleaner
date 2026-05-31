package me.gm.cleaner.dao

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import me.gm.cleaner.SharedConstants
import me.gm.cleaner.util.FileUtils
import me.gm.cleaner.util.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

// Preference key constants (values match existing R.string values to preserve user data)
private const val SORT_BY_KEY = "sort_by"
private const val MENU_RULE_COUNT_KEY = "rule_count"
private const val MENU_MOUNT_STATE_KEY = "mount_state"
private const val MENU_HIDE_SYSTEM_APP_KEY = "hide_system_app"
private const val MENU_HIDE_DISABLED_APP_KEY = "hide_disabled_app"
private const val MENU_HIDE_NO_STORAGE_PERMISSIONS_KEY = "hide_no_storage_permissions"
private const val MENU_HIDE_APP_SPECIFIC_STORAGE_KEY = "hide_app_specific_storage"
private const val SERVER_MANUALLY_STOPPED_KEY = "server_manually_stopped"
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

    private lateinit var storageRedirectFile: File
    private var inBatch: Boolean = false
    private var storageRedirectCache: JSONObject? = null

    private lateinit var readOnlyFile: File
    private var readOnlyCache: JSONObject? = null

    private lateinit var denylistFile: File
    private var denylistCache: List<String>? = null

    // @App
    // @Server
    fun init(context: Context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        storageRedirectFile = context.filesDir.resolve(SharedConstants.PREF_STORAGE_REDIRECT)
        readOnlyFile = context.filesDir.resolve(SharedConstants.READ_ONLY)
        denylistFile = context.filesDir.resolve(SharedConstants.DENY_LIST_KEY)
        readStorageRedirect()
    }

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
    var isServerManuallyStopped: Boolean
        get() = preferences.getBoolean(SERVER_MANUALLY_STOPPED_KEY, true)
        set(value) {
            preferences.edit { putBoolean(SERVER_MANUALLY_STOPPED_KEY, value) }
            notifyListeners()
        }

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit {
            putBoolean(key, value)
        }
        notifyListeners()
    }

    // STORAGE REDIRECT
    // @App
    @Synchronized
    fun putStorageRedirect(rawRules: List<Pair<String, String>>, packageNames: List<String>) {
        if (rawRules.isEmpty()) {
            removeStorageRedirect(packageNames)
            return
        }
        val rules = JSONArray()
        rawRules.forEach { rules.put(JSONArray(it.toList())) }
        val all = readStorageRedirect()
        packageNames.forEach { all.put(it, rules) }
        writeStorageRedirect(all)
    }

    // @App
    @Synchronized
    fun removeStorageRedirect(packageNames: List<String>) {
        val all = readStorageRedirect()
        packageNames.forEach { all.remove(it) }
        writeStorageRedirect(all)
    }

    // @App
    fun getUninstalledSrPackages(installedPackages: Set<String>): List<String> {
        val packages = readStorageRedirect().keys().asSequence()
        return (packages - installedPackages).toList()
    }

    // @App
    // @Server
    val srPackages: Set<String>
        get() = readStorageRedirect().keys().asSequence().toSet()

    // @App
    // @Server
    val srRulesCount: Int
        get() {
            var count = 0
            val all = readStorageRedirect()
            all.keys().forEach {
                count += all.getJSONArray(it).length()
            }
            return count
        }

    // @App
    // @Server
    fun getPackageSrCount(packageName: String): Int {
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            return all.getJSONArray(packageName).length()
        }
        return 0
    }

    // @App
    // @Server
    fun getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> {
        val source = mutableListOf<String>()
        val target = mutableListOf<String>()
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            val rules = all.getJSONArray(packageName)
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONArray(i)
                source.add(getPathAsUserQuickly(rule.getString(0), userId))
                target.add(getPathAsUserQuickly(rule.getString(1), userId))
            }
        }
        return source to target
    }

    // @App
    // @Server
    fun getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val all = readStorageRedirect()
        if (all.has(packageName)) {
            val rules = all.getJSONArray(packageName)
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONArray(i)
                list.add(
                    Pair(
                        getPathAsUserQuickly(rule.getString(0), userId),
                        getPathAsUserQuickly(rule.getString(1), userId)
                    )
                )
            }
        }
        return list
    }

    // @Server
    @Synchronized
    fun invalidateSrCache() {
        storageRedirectCache = null
    }

    // @App
    @Synchronized
    fun beginBatchOperation() {
        inBatch = true
    }

    // @App
    @Synchronized
    fun endBatchOperation() {
        inBatch = false
        writeStorageRedirect(storageRedirectCache!!)
    }

    @Synchronized
    private fun writeStorageRedirect(json: JSONObject) {
        storageRedirectCache = json
        if (inBatch) {
            return
        }
        try {
            storageRedirectFile.createNewFile()
            val bb = ByteBuffer.wrap(json.toString().toByteArray())
            storageRedirectFile.outputStream().use { it.channel.write(bb) }
            notifyListeners()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write storage redirect", e)
        }
    }

    // @App
    // @Server
    fun readRawStorageRedirect(): String = storageRedirectFile.readText()

    @Synchronized
    private fun readStorageRedirect(): JSONObject {
        if (storageRedirectCache == null) {
            storageRedirectCache = try {
                JSONObject(readRawStorageRedirect())
            } catch (e: Exception) {
                if (e !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read storage redirect", e)
                }
                JSONObject()
            }
        }
        return storageRedirectCache!!
    }

    private fun getPathAsUserQuickly(path: String, userId: Int): String = if (userId == 0) {
        path
    } else {
        FileUtils.getPathAsUser(path, userId)
    }

    // READ ONLY
    // @App
    @Synchronized
    fun putReadOnly(rawRules: List<String>, packageNames: List<String>) {
        if (rawRules.isEmpty()) {
            removeReadOnly(packageNames)
            return
        }
        val rules = JSONArray(rawRules)
        val all = readReadOnly()
        packageNames.forEach { all.put(it, rules) }
        writeReadOnly(all)
    }

    // @App
    @Synchronized
    fun removeReadOnly(packageNames: List<String>) {
        val all = readReadOnly()
        packageNames.forEach { all.remove(it) }
        writeReadOnly(all)
    }

    // @App
    fun getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> {
        val packages = readReadOnly().keys().asSequence()
        return (packages - installedPackages).toList()
    }

    // @App
    fun getPackageReadOnly(packageName: String, userId: Int = 0): List<String> {
        val all = readReadOnly()
        if (!all.has(packageName)) {
            return emptyList()
        }
        return all.getJSONArray(packageName).toList().map { path ->
            getPathAsUserQuickly(path, userId)
        }
    }

    // @Server
    fun getAllReadOnly(): Map<String, List<String>> {
        val ret = mutableMapOf<String, List<String>>()
        val all = readReadOnly()
        all.keys().forEach { ret[it] = all.getJSONArray(it).toList() }
        return ret
    }

    // @Server
    @Synchronized
    fun invalidateReadOnlyCache() {
        readOnlyCache = null
    }

    @Synchronized
    private fun writeReadOnly(json: JSONObject) {
        readOnlyCache = json
        try {
            readOnlyFile.createNewFile()
            val bb = ByteBuffer.wrap(json.toString().toByteArray())
            readOnlyFile.outputStream().use { it.channel.write(bb) }
            notifyListeners()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write read-only config", e)
        }
    }

    // @App
    // @Server
    fun readRawReadOnly(): String {
        readOnlyFile.inputStream().use {
            val bb = ByteBuffer.allocate(readOnlyFile.length().toInt())
            val bytesRead = it.channel.read(bb)
            if (bytesRead <= 0) {
                Log.w("ServicePreferences", "readRawReadOnly: channel.read returned $bytesRead")
            }
            return String(bb.array())
        }
    }

    @Synchronized
    private fun readReadOnly(): JSONObject {
        if (readOnlyCache == null) {
            readOnlyCache = try {
                JSONObject(readRawReadOnly())
            } catch (e: Exception) {
                if (e !is FileNotFoundException) {
                    Log.w(TAG, "Failed to read read-only config", e)
                }
                JSONObject()
            }
        }
        return readOnlyCache!!
    }

    // FILE SYSTEM RECORD
    // @Server
    var denylist: List<String>
        @Synchronized
        get() = try {
            if (denylistCache == null) {
                denylistFile.inputStream().use { input ->
                    val bb = ByteBuffer.allocate(denylistFile.length().toInt())
                    val bytesRead = input.channel.read(bb)
                    if (bytesRead <= 0) {
                        Log.w("ServicePreferences", "denylist: channel.read returned $bytesRead")
                    }
                    denylistCache = String(bb.array())
                        .split('\n')
                        .filterNot { it.isBlank() }
                }
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
                denylistFile.createNewFile()
                val bb = ByteBuffer.wrap(value.joinToString("\n").toByteArray())
                denylistFile.outputStream().use { it.channel.write(bb) }
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
