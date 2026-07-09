package me.gm.cleaner.client.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.client.LaunchReason
import me.gm.cleaner.client.ServerStateMachine

class AppListViewModel(application: Application) : AppListViewModelBase(application) {
    /** Expose queryText changes for logging (debug only). */
    val queryTextFlow: Flow<String> get() = _queryTextFlow

    fun loadApps() {
        viewModelScope.launch {
            if (BuildConfig.DEBUG) Log.i("CleanerTest", "AppListViewModel.loadApps: start")
            _appsFlow.value = AppListState.Loading
            ServerStateMachine.recoverIfTargetRunning(getApplication(), LaunchReason.RECOVERY)
            loadAppsCommon()
        }
    }

    init {
        loadApps()
    }
}

sealed class AppListState {
    data object Loading : AppListState()
    data class Done(val list: List<AppListModel>) : AppListState()
    data class Error(val message: String) : AppListState()
}
