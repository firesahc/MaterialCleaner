package me.gm.cleaner.dao

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import me.gm.cleaner.R
import java.util.Locale

object RootPreferences {
    private const val TAG = "RootPreferences"
    private const val LAST_NOTIFIED_UPDATE_VERSION_KEY = "last_notified_update_version"

    // THEME
    lateinit var preferences: SharedPreferences
        private set
    private lateinit var res: Resources

    fun init(context: Context) {
        preferences = try {
            PreferenceManager.getDefaultSharedPreferences(context)
        } catch (e: SecurityException) {
            Log.w(TAG, "init getDefaultSharedPreferences failed", e)
            // 降级为空偏好，避免 Direct Boot 模式下 CE 存储不可用导致 Application 创建失败
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                context.createDeviceProtectedStorageContext()
            )
        }
        res = context.resources
    }

    // ENABLE FUNCTIONS
    val isStartOnBoot: Boolean
        get() = try {
            preferences.getBoolean(res.getString(R.string.start_on_boot_key), false)
        } catch (e: Exception) {
            Log.w(TAG, "read start_on_boot failed", e)
            false
        }
    val isPostNotification: Boolean
        get() = try {
            preferences.getBoolean(res.getString(R.string.post_notification_key), true)
        } catch (e: Exception) {
            Log.w(TAG, "read post_notification failed", e)
            true
        }

    // UPDATE NOTIFICATION
    var lastNotifiedUpdateVersion: String?
        get() = try {
            preferences.getString(LAST_NOTIFIED_UPDATE_VERSION_KEY, null)
        } catch (e: Exception) {
            Log.w(TAG, "read last_notified_update_version failed", e)
            null
        }
        set(value) {
            try {
                if (value == null) {
                    preferences.edit().remove(LAST_NOTIFIED_UPDATE_VERSION_KEY).apply()
                } else {
                    preferences.edit().putString(LAST_NOTIFIED_UPDATE_VERSION_KEY, value).apply()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "write last_notified_update_version failed", e)
            }
        }

    // LANGUAGE
    val locale: Locale
        get() {
            val tag = try {
                preferences.getString(res.getString(R.string.language_key), "SYSTEM")
            } catch (e: Exception) {
                Log.w(TAG, "read language_key failed", e)
                "SYSTEM"
            }
            return if ("SYSTEM" == tag) Locale.getDefault() else Locale.forLanguageTag(tag)
        }

    // THEME
    val material3: Boolean
        get() = try {
            preferences.getBoolean(res.getString(R.string.theme_m3_key), true)
        } catch (e: Exception) {
            Log.w(TAG, "read theme_m3_key failed", e)
            true
        }
}
