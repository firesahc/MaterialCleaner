package me.gm.cleaner.util

/**
 * 文件日志写入工具：将日志写入 filesDir/log/ 目录下的文件。
 * 用于捕获和持久化运行时日志。
 * 请勿与 [LogcatUtils]（logcat 抓取和分享）混淆。
 */
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.RestrictTo
import me.gm.cleaner.shared.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter

object LogUtils {
    private lateinit var logDir: File

    fun init(context: Context) {
        logDir = context.filesDir.resolve("log")
    }

    fun handleThrowable(tr: Throwable) {
        Log.e(BuildConfig.LIBRARY_PACKAGE_NAME, Log.getStackTraceString(tr))
        write(Log.getStackTraceString(tr))
    }

    @JvmStatic
    fun write(log: String) {
        if (!BuildConfig.DEBUG) return
        try {
            logDir.mkdirs()
            val out = File(logDir, System.currentTimeMillis().toString() + ".txt")
            if (!out.exists()) {
                out.createNewFile()
            }
            val writer = PrintWriter(FileWriter(out))
            writer.write(log)
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun printStackTraceString() {
        Log.e(BuildConfig.LIBRARY_PACKAGE_NAME, Log.getStackTraceString(Exception()))
    }

    @RestrictTo(RestrictTo.Scope.TESTS)
    @JvmStatic
    fun e(msg: Any?) {
        Log.e("clr", msg.toString())
    }

    @RestrictTo(RestrictTo.Scope.TESTS)
    @JvmStatic
    fun e(tag: Any?, msg: Any?) {
        Log.e("clr", "$tag: $msg")
    }

    @JvmStatic
    fun i(msg: Any) {
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, msg.toString())
    }

    @JvmStatic
    fun assertMainThread() {
        check(Looper.getMainLooper().thread === Thread.currentThread()) {
            "Needs to be invoked on the main thread."
        }
    }
}
