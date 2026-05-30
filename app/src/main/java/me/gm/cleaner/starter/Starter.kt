package me.gm.cleaner.starter

import android.content.Context
import android.system.Os
import android.util.Log
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.util.LibUtils
import java.io.*
import java.util.zip.ZipFile

object Starter {
    lateinit var command: String

    fun writeDataFiles(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "Starter.writeDataFiles: dir=${dir.absolutePath}")
        val starter = copyStarter(context, dir.resolve("starter"))
        val sh = writeScript(context, dir.resolve("start.sh"), starter)
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
        Os.remove(dir.resolve("source_dir").path)
    }
}
