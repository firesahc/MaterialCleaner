package me.gm.cleaner.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.regex.Pattern

// Preference key constants shared by XML preferences and runtime config snapshots.
private const val PREF_STORAGE_REDIRECT = "storage_redirect"
private const val READ_ONLY = "read_only"
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
        storageRedirectFile = context.filesDir.resolve(PREF_STORAGE_REDIRECT)
        readOnlyFile = context.filesDir.resolve(READ_ONLY)
        denylistFile = context.filesDir.resolve(DENY_LIST_KEY)
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

    private fun writeUtf8Atomically(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(file)) {
                throw IOException("rename failed: ${tmpFile.path} -> ${file.path}")
            }
        } catch (e: IOException) {
            tmpFile.delete()
            throw e
        } catch (e: RuntimeException) {
            tmpFile.delete()
            throw e
        }
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
            writeUtf8Atomically(storageRedirectFile, json.toString())
            notifyListeners()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write storage redirect", e)
        }
    }

    // @App
    // @Server
    fun readRawStorageRedirect(): String = storageRedirectFile.readText(Charsets.UTF_8)

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
        getPathAsUser(path, userId)
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
            writeUtf8Atomically(readOnlyFile, json.toString())
            notifyListeners()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write read-only config", e)
        }
    }

    // @App
    // @Server
    fun readRawReadOnly(): String = readOnlyFile.readText(Charsets.UTF_8)

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
                writeUtf8Atomically(denylistFile, value.joinToString("\n"))
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

private val APP_DATA_DIR_PATHS: Pattern by lazy {
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
}

private fun getPathAsUser(path: String, userId: Int): String {
    val matcher = APP_DATA_DIR_PATHS.matcher(path)
    if (!matcher.matches()) {
        return path
    }
    val builder = StringBuilder()
    for (i in 1..matcher.groupCount()) {
        val group = matcher.group(i) ?: continue
        if (group.all { it.isDigit() }) {
            builder.append(userId)
        } else {
            builder.append(group)
        }
    }
    return builder.toString()
}

private fun JSONArray.toList(): ArrayList<String> {
    val list = ArrayList<String>(length())
    for (i in 0 until length()) {
        list.add(getString(i))
    }
    return list
}
