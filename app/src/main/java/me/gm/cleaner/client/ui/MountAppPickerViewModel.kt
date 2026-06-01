package me.gm.cleaner.client.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MountAppPickerViewModel(application: Application) : AppListViewModelBase(application) {

    fun loadApps() {
        viewModelScope.launch {
            loadAppsCommon()
        }
    }

    init {
        loadApps()
    }
}
