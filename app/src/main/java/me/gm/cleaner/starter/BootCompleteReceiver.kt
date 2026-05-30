package me.gm.cleaner.starter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.util.FileUtils.toUserId

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Process.myUid().toUserId() > 0) return
        val isStartOnBoot = RootPreferences.isStartOnBoot
        val pingBinder = CleanerClient.pingBinder()
        if (BuildConfig.DEBUG) Log.i("CleanerTest", "BootCompleteReceiver.onReceive: action=${intent?.action}, isStartOnBoot=$isStartOnBoot, pingBinder=$pingBinder")
        if (isStartOnBoot && !pingBinder) {
            val shell = try {
                Shell.getShell()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "BootCompleteReceiver: failed to get shell", e)
                return
            }
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "BootCompleteReceiver: shell.isRoot=${shell.isRoot}")
            if (shell.isRoot) {
                runCatching {
                    if (RootPreferences.isStartOnBoot) {
                        Starter.writeSourceDir(context)
                    }
                    Starter.writeDataFiles(context)
                    Shell.cmd(Starter.command).exec()
                    if (BuildConfig.DEBUG) Log.i("CleanerTest", "BootCompleteReceiver: server start command executed")
                }.onFailure { e ->
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "BootCompleteReceiver: Failed to start server", e)
                }
            }
        }
    }
}
