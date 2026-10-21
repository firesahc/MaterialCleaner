package me.gm.cleaner.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.NotificationService
import me.gm.cleaner.dao.RootPreferences
import org.json.JSONObject
import java.net.URL

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/firesahc/MaterialCleaner/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val body: String? = null,
    )

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_RELEASE_URL).openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            conn.getInputStream().bufferedReader().use { it.readText() }
        }.mapCatching { text ->
            val json = JSONObject(text)
            ReleaseInfo(
                tagName = json.optString("tag_name", ""),
                htmlUrl = json.optString("html_url", ""),
                body = json.optString("body").takeIf { it.isNotEmpty() }
            )
        }.getOrNull()
    }

    fun isNewer(current: String, latest: String): Boolean {
        fun normalize(v: String) = v.trim().removePrefix("v").removePrefix("V")
        val a = normalize(current).split('.')
        val b = normalize(latest).split('.')
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val ai = a.getOrElse(i) { "0" }
            val bi = b.getOrElse(i) { "0" }
            val cmp = try {
                ai.toLong().compareTo(bi.toLong())
            } catch (e: NumberFormatException) {
                ai.compareTo(bi)
            }
            if (cmp != 0) return cmp < 0
        }
        return false
    }

    /**
     * 检查结果。
     *
     * 职责边界：**检查（查询）与呈现（投递）分离**。本对象只判定结果，
     * 由调用方按场景决定如何呈现——手动检查需要当场反馈，自动检查才发通知。
     * 二者混在一个函数里会导致自动路径的去重规则污染手动路径：
     * 手动检查命中"已通知过"后静默，用户得不到任何回答。
     */
    sealed interface UpdateCheckResult {
        /** 已是最新版本。 */
        data class UpToDate(val current: String) : UpdateCheckResult

        /** 存在新版本。 */
        data class Available(val release: ReleaseInfo) : UpdateCheckResult

        /** 检查失败（网络不可达或响应无法解析）。 */
        data class Failed(val reason: String? = null) : UpdateCheckResult
    }

    /**
     * 只检查不呈现：返回结果供调用方决定呈现方式。
     */
    suspend fun check(): UpdateCheckResult {
        val release = fetchLatestRelease() ?: return UpdateCheckResult.Failed()
        val current = BuildConfig.VERSION_NAME
        if (!isNewer(current, release.tagName)) {
            return UpdateCheckResult.UpToDate(current)
        }
        return UpdateCheckResult.Available(release)
    }

    /**
     * 自动路径：发现新版本时发通知。
     *
     * 面向无人值守场景（App 启动、开机自启），因此失败与"已是最新"都不打扰用户；
     * 同一版本只通知一次。手动检查走 [check] 并当场反馈，不受此去重约束。
     */
    suspend fun checkAndNotify(context: Context) {
        val result = check()
        if (result !is UpdateCheckResult.Available) return
        val release = result.release
        if (release.tagName == RootPreferences.lastNotifiedUpdateVersion) return
        NotificationService.notifyUpdateAvailable(context, release)
        RootPreferences.lastNotifiedUpdateVersion = release.tagName
    }
}
