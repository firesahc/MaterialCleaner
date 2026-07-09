package me.gm.cleaner.starter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.BootTargetSource
import me.gm.cleaner.client.BootTargetState
import me.gm.cleaner.client.CleanerServerLauncher
import me.gm.cleaner.client.LaunchReason
import me.gm.cleaner.client.ServiceBootStateStore
import me.gm.cleaner.dao.RootPreferences
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
        if (BuildConfig.DEBUG) {
            Log.i(
                "CleanerTest",
                "BootCompleteReceiver.onReceive: action=${intent.action}, " +
                        "isStartOnBoot=$isStartOnBoot"
            )
        }
        ServiceBootStateStore.ensureInitialized(context)
        val target = ServiceBootStateStore.initializeForBoot(isStartOnBoot)
        if (!isStartOnBoot || target != BootTargetState.RUNNING ||
            ServiceBootStateStore.source != BootTargetSource.BOOT
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                launchForBootWithRetry(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun launchForBootWithRetry(context: Context) {
        repeat(MAX_BOOT_LAUNCH_ATTEMPTS) { attempt ->
            if (!isBootLaunchStillValid()) return
            val success = CleanerServerLauncher.launch(context, LaunchReason.BOOT) {
                isBootLaunchStillValid()
            }
            if (success || !isBootLaunchStillValid()) return
            if (attempt < MAX_BOOT_LAUNCH_ATTEMPTS - 1) {
                delay(bootBackoffMillis(attempt + 1))
            }
        }
    }

    private fun isBootLaunchStillValid(): Boolean =
        ServiceBootStateStore.shouldRun() &&
                ServiceBootStateStore.source == BootTargetSource.BOOT

    private fun bootBackoffMillis(attempt: Int): Long =
        (1L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(4L) * 1000L

    companion object {
        private const val MAX_BOOT_LAUNCH_ATTEMPTS = 3
    }
}
