package me.gm.cleaner.client.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.starter.Starter

class AppListViewModel(application: Application) : AppListViewModelBase(application) {
    /** Expose queryText changes for logging (debug only). */
    val queryTextFlow: Flow<String> get() = _queryTextFlow

    private suspend fun tryStartServer() {
        if (CleanerClient.pingBinder()) {
            if (BuildConfig.DEBUG) Log.d("CleanerTest", "tryStartServer: server already running, pingBinder=true")
            // Force restart server to pick up code changes and fix HooksBridge relay
            // The old server may have stale binder connections
            val shell = Shell.getShell()
            if (shell.isRoot) {
                if (BuildConfig.DEBUG) Log.i("CleanerTest", "tryStartServer: Killing old server to force restart...")
                // Use basic shell tools to find and kill the old server
                Shell.cmd("ps -A | grep cleaner_server | tr -s ' ' | cut -d' ' -f2 | while read pid; do kill -9 \$pid 2>/dev/null; done").exec()
                // Wait for old server to die
                delay(2000)
            } else {
                return
            }
        }
        if (BuildConfig.DEBUG) Log.d("CleanerTest", "tryStartServer: server not running, attempting to start")
        withContext(Dispatchers.IO) {
            try {
                val shell = Shell.getShell()
                if (BuildConfig.DEBUG) Log.d("CleanerTest", "tryStartServer: shell.isRoot=${shell.isRoot}")
                if (!shell.isRoot) {
                    if (BuildConfig.DEBUG) Log.w("CleanerTest", "tryStartServer: no root access, cannot start server")
                    return@withContext
                }
                Starter.writeDataFiles(getApplication())
                val result = Shell.cmd(Starter.command).exec()
                if (BuildConfig.DEBUG) Log.d("CleanerTest", "tryStartServer: result.isSuccess=${result.isSuccess}")
                if (!result.isSuccess) {
                    if (BuildConfig.DEBUG) Log.e("CleanerTest", "tryStartServer: starter command failed: ${result.err.joinToString()}")
                    return@withContext
                }
                // Wait for the server to start and send the binder
                var retries = 0
                while (!CleanerClient.pingBinder() && retries < 20) {
                    delay(500)
                    retries++
                }
                if (BuildConfig.DEBUG) Log.i("CleanerTest", "tryStartServer: server connected after ${retries} retries")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("CleanerTest", "tryStartServer: exception", e)
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: start")
            _appsFlow.value = AppListState.Loading
            if (!ServicePreferences.isServerManuallyStopped) {
                tryStartServer()
            }
            loadAppsCommon(includeServerStart = false)
        }
    }

    init {
        loadApps()
    }
}

sealed class AppListState {
    data object Loading : AppListState()
    data class Done(val list: List<AppListModel>) : AppListState()
}
