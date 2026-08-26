package me.gm.cleaner.starter

import android.content.Context
import android.system.Os
import android.util.Log
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.util.LibUtils
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object Starter {
    lateinit var command: String

    fun writeDataFiles(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "Starter.writeDataFiles: dir=${dir.absolutePath}")
        val starter = copyStarter(context, dir.resolve("starter"))
        val sh = writeScript(context, dir.resolve("start.sh"), starter)
        // 一致性断言：start.sh 必须指向当前安装的 APK。覆盖安装会更换
        // /data/app 下的存放目录，若脚本因任何原因未被正确刷新，
        // 服务将以失效路径拉起并报 Can't access——宁可显式失败也不静默使用旧脚本。
        check(java.io.File(sh).readText().contains(context.applicationInfo.sourceDir)) {
            "start.sh does not point to the current apk: $sh"
        }
        command = "sh $sh"
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "Starter.writeDataFiles: command=$command")
    }

    @Throws(IOException::class)
    private fun copyStarter(context: Context, out: File): String {
        val so = LibUtils.getLibEntryName("starter")
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "Starter.copyStarter: so=$so, out=${out.absolutePath}")
        ZipFile(LibUtils.getLibSourceDir(context.applicationInfo)).use { apk ->
            val entry = apk.getEntry(so) ?: throw NoSuchFileException(File(so))
            apk.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            out.setExecutable(true)
            out.setReadable(true)
            return out.absolutePath
        }
    }

    @Throws(IOException::class)
    private fun writeScript(context: Context, out: File, starter: String): String {
        if (!out.exists()) {
            out.createNewFile()
        }
        val apkPath = context.applicationInfo.sourceDir
        val script = "#!/system/bin/sh\nexec \"$starter\" --apk=\"$apkPath\"\n"
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "Starter.writeScript: apkPath=$apkPath, script=$script")
        out.writeText(script)
        out.setExecutable(true)
        return out.absolutePath
    }

    fun writeSourceDir(context: Context): String {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        val out = dir.resolve("source_dir")
        if (!out.exists()) {
            out.createNewFile()
        }
        val sourceDir = context.applicationInfo.sourceDir
        out.outputStream().use {
            it.write(sourceDir.toByteArray())
            it.flush()
        }
        return sourceDir
    }

    fun deleteSourceDir(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        val sourceDir = dir.resolve("source_dir")
        if (sourceDir.exists()) {
            Os.remove(sourceDir.path)
        }
    }
}
