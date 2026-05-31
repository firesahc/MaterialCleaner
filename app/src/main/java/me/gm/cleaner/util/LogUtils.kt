package me.gm.cleaner.util

/**
 * 文件日志写入工具：将日志写入 filesDir/log/ 目录下的文件。
 * 用于捕获和持久化运行时日志。
 * 请勿与 [LogcatUtils]（logcat 抓取和分享）混淆。
 */
import android.content.Context
import java.io.File

object LogUtils {
    private lateinit var logDir: File

    fun init(context: Context) {
        logDir = context.filesDir.resolve("log")
    }
}
