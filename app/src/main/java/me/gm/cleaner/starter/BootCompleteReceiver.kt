package me.gm.cleaner.starter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.client.StartSource
import me.gm.cleaner.dao.RootPreferences
import me.gm.cleaner.core.config.ServicePreferences
import me.gm.cleaner.util.FileUtils.toUserId

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Process.myUid().toUserId() > 0) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> {
                if (BuildConfig.DEBUG) {
                    Log.w("CleanerTest", "BootCompleteReceiver ignored action=${intent?.action}")
                }
                return
            }
        }
        val isStartOnBoot = RootPreferences.isStartOnBoot
        val isManuallyStopped = ServicePreferences.isServiceManuallyStopped
        if (BuildConfig.DEBUG) {
            Log.i(
                "CleanerTest",
                "BootCompleteReceiver.onReceive: action=${intent.action}, " +
                        "isStartOnBoot=$isStartOnBoot, isManuallyStopped=$isManuallyStopped"
            )
        }
        if (isStartOnBoot && !isManuallyStopped) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                ServerStateMachine.start(StartSource.BOOT, context)
            }
        }
    }
}
