package me.gm.cleaner.client.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import java.io.File

fun Fragment.grabLogcatAndShare(context: Context) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time").start()
            val log = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val logFile = File(context.cacheDir, "logcat.txt")
            logFile.writeText(log)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share_logcat))
                )
            }
        } catch (e: Exception) {
            Log.e("CleanerTest", "logcat grab failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "${context.getString(R.string.logcat_failed)} ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
