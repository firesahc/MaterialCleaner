package me.gm.cleaner.client.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.net.OnlineAppCategory
import java.util.concurrent.atomic.AtomicInteger

class AppCategoryCacheSyncViewModel(private val application: Application) :
    AndroidViewModel(application) {
    private val _progressLiveData: MutableLiveData<AppCategoryCacheSyncState> =
        MutableLiveData<AppCategoryCacheSyncState>(
            AppCategoryCacheSyncState.Downloading(0, "")
        )
    val progressLiveData: LiveData<AppCategoryCacheSyncState>
        get() = _progressLiveData

    init {
        viewModelScope.launch {
            val installedNonSystemApps = CleanerClient.getInstalledPackages(0).filter {
                it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
            val finishedCount = AtomicInteger()
            val jobs = installedNonSystemApps.map { packageInfo ->
                launch(Dispatchers.IO) {
                    ensureActive()
                    val packageName = packageInfo.packageName
                    runCatching {
                        withContext(Dispatchers.IO) {
                            OnlineAppCategory.buildURL(application, packageName)
                                .invalidate()
                                .openStream()
                                .close()
                        }
                    }.onFailure {
                        // onFailure means the marks not exist
                    }
                    _progressLiveData.postValue(
                        AppCategoryCacheSyncState.Downloading(
                            100 * finishedCount.incrementAndGet() / installedNonSystemApps.size,
                            packageName
                        )
                    )
                }
            }
            jobs.joinAll()
            _progressLiveData.postValue(AppCategoryCacheSyncState.Done)
        }
    }
}

sealed class AppCategoryCacheSyncState {
    data class Downloading(val progress: Int, val packageName: String) :
        AppCategoryCacheSyncState()

    data object Done : AppCategoryCacheSyncState()
}
