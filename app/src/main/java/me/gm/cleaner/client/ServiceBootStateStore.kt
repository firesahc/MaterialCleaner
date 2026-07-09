package me.gm.cleaner.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import me.gm.cleaner.BuildConfig
import java.io.File

enum class BootTargetState { NONE, RUNNING, STOPPED }
enum class BootTargetSource { BOOT, MANUAL, NOTIFICATION, RECOVERY }

object ServiceBootStateStore {
    private const val TAG = "MC/BootState"
    private const val PREF_NAME = "service_boot_state"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_TARGET_STATE = "target_state"
    private const val KEY_SOURCE = "source"
    private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun init(context: Context) {
        preferences = context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            resetIfBootChangedLocked()
        }
    }

    fun ensureInitialized(context: Context) {
        if (preferences == null) {
            init(context)
        } else {
            resetIfBootChanged()
        }
    }

    val currentBootId: String
        get() = synchronized(this) {
            resetIfBootChangedLocked()
        }

    val targetState: BootTargetState
        get() = synchronized(this) {
            resetIfBootChangedLocked()
            targetStateLocked()
        }

    val source: BootTargetSource?
        get() = synchronized(this) {
            resetIfBootChangedLocked()
            preferencesOrThrow().getString(KEY_SOURCE, null)
                ?.let { runCatching { BootTargetSource.valueOf(it) }.getOrNull() }
        }

    fun resetIfBootChanged() {
        synchronized(this) {
            resetIfBootChangedLocked()
        }
    }

    fun initializeForBoot(startOnBoot: Boolean): BootTargetState = synchronized(this) {
        resetIfBootChangedLocked()
        val current = targetStateLocked()
        if (current != BootTargetState.NONE) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "initializeForBoot: keep existing target=$current source=$source")
            }
            return@synchronized current
        }
        val target = if (startOnBoot) BootTargetState.RUNNING else BootTargetState.STOPPED
        setTargetLocked(target, BootTargetSource.BOOT)
        target
    }

    fun setTarget(state: BootTargetState, source: BootTargetSource) {
        synchronized(this) {
            resetIfBootChangedLocked()
            setTargetLocked(state, source)
        }
    }

    fun shouldRun(): Boolean = targetState == BootTargetState.RUNNING

    fun isStopped(): Boolean = targetState == BootTargetState.STOPPED

    private fun targetStateLocked(): BootTargetState {
        val raw = preferencesOrThrow().getString(KEY_TARGET_STATE, null)
        return raw
            ?.let { runCatching { BootTargetState.valueOf(it) }.getOrNull() }
            ?: BootTargetState.NONE
    }

    private fun setTargetLocked(state: BootTargetState, source: BootTargetSource) {
        val bootId = readBootId()
        preferencesOrThrow().edit {
            putString(KEY_BOOT_ID, bootId)
            if (state == BootTargetState.NONE) {
                remove(KEY_TARGET_STATE)
                remove(KEY_SOURCE)
            } else {
                putString(KEY_TARGET_STATE, state.name)
                putString(KEY_SOURCE, source.name)
            }
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "setTarget: bootId=$bootId state=$state source=$source")
        }
    }

    private fun resetIfBootChangedLocked(): String {
        val bootId = readBootId()
        val preferences = preferencesOrThrow()
        val storedBootId = preferences.getString(KEY_BOOT_ID, null)
        when {
            storedBootId == null -> preferences.edit { putString(KEY_BOOT_ID, bootId) }
            storedBootId != bootId -> preferences.edit {
                clear()
                putString(KEY_BOOT_ID, bootId)
            }
        }
        return bootId
    }

    private fun preferencesOrThrow(): SharedPreferences =
        checkNotNull(preferences) { "ServiceBootStateStore is not initialized" }

    private fun readBootId(): String {
        val bootId = runCatching {
            File(BOOT_ID_PATH).readText(Charsets.UTF_8).trim()
        }.onFailure {
            Log.w(TAG, "Failed to read $BOOT_ID_PATH", it)
        }.getOrDefault("")
        return bootId.ifBlank { "unknown" }
    }
}
