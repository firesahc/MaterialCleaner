package me.gm.cleaner.client.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ServerStateMachine
import me.gm.cleaner.client.StartSource

class AppListViewModel(application: Application) : AppListViewModelBase(application) {
    /** Expose queryText changes for logging (debug only). */
    val queryTextFlow: Flow<String> get() = _queryTextFlow

    /**
     * 自动启动服务器（仅在未运行时启动，不杀死已有进程）。
     * 启动后重载配置和挂载点。
     */
    private suspend fun tryStartServer() {
        if (BuildConfig.DEBUG) Log.d("CleanerTest", "tryStartServer: delegating to ServerStateMachine.start(AUTO)")
        ServerStateMachine.start(StartSource.AUTO, getApplication())
    }

    /**
     * 加载应用列表。
     * @param startServer 若为 true 且服务器尚未运行，则请求状态机按自动链路启动服务器。
     */
    fun loadApps(startServer: Boolean = false) {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: start, startServer=$startServer")
            _appsFlow.value = AppListState.Loading
            if (startServer && !CleanerClient.pingBinder()) {
                tryStartServer()
            }
            loadAppsCommon()
        }
    }

    init {
        loadApps(startServer = true)
    }
}

sealed class AppListState {
    data object Loading : AppListState()
    data class Done(val list: List<AppListModel>) : AppListState()
    data class Error(val message: String) : AppListState()
}
