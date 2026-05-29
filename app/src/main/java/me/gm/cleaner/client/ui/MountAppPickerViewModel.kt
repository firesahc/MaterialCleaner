package me.gm.cleaner.client.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.util.PermissionUtils
import me.gm.cleaner.util.collatorComparator

class MountAppPickerViewModel(application: Application) : AndroidViewModel(application) {
    private val _isSearchingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var isSearching: Boolean
        get() = _isSearchingFlow.value
        set(value) {
            _isSearchingFlow.value = value
        }

    private val _queryTextFlow: MutableStateFlow<String> = MutableStateFlow("")
    var queryText: String
        get() = _queryTextFlow.value
        set(value) {
            _queryTextFlow.value = value
        }

    private val _appsFlow: MutableStateFlow<AppListState> = MutableStateFlow(AppListState.Loading)
    val isDone: Boolean
        get() = _appsFlow.value is AppListState.Done

    private val _uninstalledPackagesLiveData: MutableLiveData<List<String>> =
        MutableLiveData<List<String>>(emptyList())
    val uninstalledPackagesLiveData: LiveData<List<String>>
        get() = _uninstalledPackagesLiveData

    val appsFlow: Flow<AppListState> = combine(
        _appsFlow, _isSearchingFlow, _queryTextFlow
    ) { state, isSearching, queryText ->
        when (state) {
            is AppListState.Loading -> return@combine AppListState.Loading
            else -> {}
        }
        val list = (state as AppListState.Done).list
        var sequence = list.asSequence()
        if (ServicePreferences.isHideSystemApp) {
            sequence = sequence.filter {
                it.packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
        }
        if (ServicePreferences.isHideDisabledApp) {
            sequence = sequence.filter {
                it.packageInfo.applicationInfo.enabled
            }
        }
        if (ServicePreferences.isHideNoStoragePermissionApp) {
            sequence = sequence.filter {
                PermissionUtils.containsStoragePermissions(it.packageInfo)
            }
        }
        if (isSearching) {
            sequence = sequence.filter {
                it.label.contains(queryText, true) ||
                        it.packageInfo.packageName.contains(queryText, true) ||
                        (it.packageInfo.sharedUserId ?: "").contains(queryText, true)
            }
        }
        sequence = when (ServicePreferences.sortBy) {
            ServicePreferences.SORT_BY_NAME ->
                sequence.sortedWith(collatorComparator { it.label })

            ServicePreferences.SORT_BY_UPDATE_TIME ->
                sequence.sortedByDescending { it.packageInfo.lastUpdateTime }

            else -> throw IllegalArgumentException()
        }
        if (ServicePreferences.ruleCount) {
            sequence = sequence.sortedByDescending {
                val c1 = if (it.mountRulesCount > 0) 2 else 0
                val c2 = if (it.readOnlyCount > 0) 1 else 0
                c1 + c2
            }
        }
        if (ServicePreferences.mountState) {
            sequence = sequence.sortedByDescending {
                it.mountState
            }
        }
        AppListState.Done(sequence.toList())
    }

    fun loadApps() {
        viewModelScope.launch {
            Log.i("CleanerTest", "MountAppPickerViewModel.loadApps: start")
            _appsFlow.value = AppListState.Loading

            val list = try {
                AppListLoader().load()
            } catch (e: Exception) {
                Log.e("CleanerTest", "MountAppPickerViewModel.loadApps: failed to load apps", e)
                emptyList()
            }
            Log.i("CleanerTest", "MountAppPickerViewModel.loadApps: list.size=${list.size}")

            val installedPackages = list
                .asSequence()
                .map { it.packageInfo.packageName }
                .toSet()
            val uninstalledPackages =
                (ServicePreferences.getUninstalledSrPackages(installedPackages) +
                        ServicePreferences.getUninstalledReadOnlyPackages(installedPackages) +
                        ServicePreferences.denylist.toSet() - installedPackages).distinct()
            Log.i("CleanerTest", "MountAppPickerViewModel.loadApps: uninstalledPackages=${uninstalledPackages.size}")
            if (uninstalledPackages.isNotEmpty()) {
                _uninstalledPackagesLiveData.postValue(uninstalledPackages.toMutableList())
            }
            _appsFlow.value = AppListState.Done(list)
        }
    }

    fun updateAppsRuleCount() {
        viewModelScope.launch {
            val value = _appsFlow.value
            if (value is AppListState.Done) {
                _appsFlow.value = AppListState.Loading
                val list = AppListLoader().updateRuleCount(value.list)
                _appsFlow.value = AppListState.Done(list)
            }
        }
    }

    init {
        loadApps()
    }
}
