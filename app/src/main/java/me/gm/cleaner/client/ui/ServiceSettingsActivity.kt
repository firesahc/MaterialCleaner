package me.gm.cleaner.client.ui

import android.os.Bundle
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import me.gm.cleaner.app.BaseActivity
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.databinding.ServiceSettingsActivityBinding
import me.gm.cleaner.starter.Starter

class ServiceSettingsActivity : BaseActivity() {
    private companion object {
        const val TAG = "AppListDebug"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ServiceSettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Try to start the server if not running
        tryStartServerIfNeeded()
    }

    private fun tryStartServerIfNeeded() {
        if (CleanerClient.pingBinder()) {
            Log.d(TAG, "Server already running, no need to start")
            return
        }
        Log.d(TAG, "Server not running, attempting to start...")
        MainScope().launch(Dispatchers.IO) {
            try {
                val shell = Shell.getShell()
                Log.d(TAG, "Shell.isRoot=${shell.isRoot}")
                if (shell.isRoot) {
                    Starter.writeDataFiles(this@ServiceSettingsActivity)
                    Log.d(TAG, "Data files written, command: ${Starter.command}")
                    val result = Shell.cmd(Starter.command).exec()
                    Log.d(TAG, "Server start result: isSuccess=${result.isSuccess}")
                    if (!result.isSuccess) {
                        Log.e(TAG, "Server start failed: ${result.err.joinToString("\n")}")
                    }
                } else {
                    Log.w(TAG, "Root not available, cannot start server")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }
}
