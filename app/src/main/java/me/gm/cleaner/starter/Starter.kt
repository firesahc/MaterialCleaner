package me.gm.cleaner.starter

import android.content.Context
import android.system.Os
import me.gm.cleaner.util.LibUtils
import java.io.*
import java.util.zip.ZipFile

object Starter {
    lateinit var command: String

    fun writeDataFiles(context: Context) {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        val starter = copyStarter(context, dir.resolve("starter"))
        val sh = writeScript(context, dir.resolve("start.sh"), starter)
        command = "sh $sh --apk=${context.applicationInfo.sourceDir}"
    }

    @Throws(IOException::class)
    private fun copyStarter(context: Context, out: File): String {
        val so = LibUtils.getLibEntryName("starter")
        ZipFile(LibUtils.getLibSourceDir(context.applicationInfo)).use { apk ->
            val entry = apk.getEntry(so) ?: throw NoSuchFileException(File(so))
            apk.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return out.absolutePath
        }
    }

    @Throws(IOException::class)
    private fun writeScript(context: Context, out: File, starter: String): String {
        if (!out.exists()) {
            out.createNewFile()
        }
        val script = "#!/system/bin/sh\nexport LD_LIBRARY_PATH=\"\$(dirname \"\$0\")\"\nexec \"$starter\" --apk=\"@SOURCE@\"\n"
        out.writeText(script.replace("@SOURCE@", "\"$starter\""))
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
