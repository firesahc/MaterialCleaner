package me.gm.cleaner.dao

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import me.gm.cleaner.R
import java.util.Locale

object RootPreferences {
    // THEME
    lateinit var preferences: SharedPreferences
        private set
    private lateinit var res: Resources

    fun init(context: Context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        res = context.resources
    }

    // ENABLE FUNCTIONS
    val isStartOnBoot: Boolean
        get() = preferences.getBoolean(res.getString(R.string.start_on_boot_key), false)
    val isPostNotification: Boolean
        get() = preferences.getBoolean(res.getString(R.string.post_notification_key), true)

    // LANGUAGE
    val locale: Locale
        get() {
            val tag = preferences.getString(res.getString(R.string.language_key), "SYSTEM")!!
            return if ("SYSTEM" == tag) Locale.getDefault() else Locale.forLanguageTag(tag)
        }

    // THEME
    val material3: Boolean
        get() = preferences.getBoolean(res.getString(R.string.theme_m3_key), true)
}
