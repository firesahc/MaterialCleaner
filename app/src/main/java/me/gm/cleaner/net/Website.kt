package me.gm.cleaner.net

import me.gm.cleaner.dao.RootPreferences
import java.util.Locale

const val NOTIFICATION_CHANNEL: String = "update"

object Website {
    const val appsTypeMarksRepo: String =
        "https://github.com/MaterialCleaner/AppsTypeMarks/fork"
    const val mediaProviderManager: String =
        "https://github.com/MaterialCleaner/Media-Provider-Manager"
    val wikiInstallZygiskModule: String
        get() = when (RootPreferences.locale) {
            Locale.SIMPLIFIED_CHINESE -> "https://github.com/MaterialCleaner/MaterialCleaner/wiki/Zygisk-%E6%A8%A1%E5%9D%97%E5%AE%89%E8%A3%85"
            else -> "https://github.com/MaterialCleaner/MaterialCleaner/wiki/Install-Zygisk-Module"
        }
}
