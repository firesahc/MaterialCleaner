package me.gm.cleaner.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import me.gm.cleaner.core.common.RuntimeFileUtils
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
private const val FUSE_BPF_BLOCK_ALL_KEY = "fuse_bpf_block_all"
private const val AUDIT_MEDIA_INSERT_KEY = "audit_media_insert"
private const val DENY_MEDIA_INSERT_WRITES_KEY = "deny_media_insert_writes"
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

    // STORAGE REDIRECT：读写与批量已直迁配置存储；仅分享导出读原始文件。
    // @App
    // @Server
    fun readRawStorageRedirect(): String = ConfiguredPolicyStoreProvider.instance.readRawRedirect()

    // READ ONLY：读写与批量已直迁配置存储。
    // @App
    // @Server
    fun readRawReadOnly(): String = ConfiguredPolicyStoreProvider.instance.readRawReadOnly()

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
                RuntimeFileUtils.writeTextAtomically(denylistFile, value.joinToString("\n"))
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

    /**
     * FUSE BPF 拦截范围开关（决策 D1），默认关闭。
     *
     * 真相归偏好层：本字段是该开关在偏好侧的唯一真相，下游 Cache 只做透传，
     * 不得在此处或 Cache 侧按 ROM 做条件改写。
     * native 侧移除语义恒放行（见 bpf_hook.cpp），本开关经策略快照透传到
     * commitPolicy 第三参后，由 native 侧解释非移除语义的拦截范围。
     */
    // @App
    // @Server
    val fuseBpfBlockAll: Boolean
        get() = preferences.getBoolean(FUSE_BPF_BLOCK_ALL_KEY, false)

    // @App
    // @Server
    val upsert: Boolean
        get() = preferences.getBoolean(UPSERT_KEY, true)

    /**
     * 媒体库写入审计总开关，默认关闭。
     *
     * 关闭时 InsertHooker 在未命中重定向规则的情况下完全静默（与历史行为一致）；
     * 打开后每次 insert 都会留一条结构化审计流水（日志 + DataBus 事件），
     * 用来回答「应用到底有没有通过 MediaStore 建条目」这个问题。
     *
     * 真相归偏好层：本字段是该开关在偏好侧的唯一真相，下游 Cache 只做透传。
     */
    // @App
    // @Server
    val auditMediaInsert: Boolean
        get() = preferences.getBoolean(AUDIT_MEDIA_INSERT_KEY, false)

    /**
     * 媒体库写入**拒绝**总开关，默认关闭。
     *
     * 打开后 {@code InsertHooker} 在命中「不可访问」或「只读」规则时短路本次
     * {@code insertFile}（{@code setResult(null)}），使应用**无法**经 MediaStore
     * 建条目 —— 补上「挂载层之外还有一条写入通路」这个缺口。
     *
     * 默认 false ⇒ 老快照下行为与历史完全一致（放行）。
     *
     * 真相归偏好层：本字段是该开关在偏好侧的唯一真相，下游 Cache 只做透传。
     */
    // @App
    // @Server
    val denyMediaInsertWrites: Boolean
        get() = preferences.getBoolean(DENY_MEDIA_INSERT_WRITES_KEY, false)
}
