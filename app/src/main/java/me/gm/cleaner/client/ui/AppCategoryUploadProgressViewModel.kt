package me.gm.cleaner.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.net.OnlineAppCategory
import java.util.concurrent.atomic.AtomicInteger

class AppCategoryUploadProgressViewModel(private val application: Application) :
    AndroidViewModel(application) {
    private val _progressLiveData: MutableLiveData<AppCategoryUploadState> =
        MutableLiveData<AppCategoryUploadState>(AppCategoryUploadState.Uploading(0, ""))
    val progressLiveData: LiveData<AppCategoryUploadState>
        get() = _progressLiveData

    fun uploadAppCategory(appCategories: List<Pair<String, String>>) {
        viewModelScope.launch {
            val finishedCount = AtomicInteger()
            val jobs = appCategories.map { (packageName, content) ->
                launch(Dispatchers.IO) {
                    ensureActive()
                    runCatching {
                        withContext(Dispatchers.IO) {
                            OnlineAppCategory.buildDefaultURL(application, packageName)
                                .openStream()
                                .close()
                        }
                    }
                    _progressLiveData.postValue(
                        AppCategoryUploadState.Uploading(
                            100 * finishedCount.incrementAndGet() / appCategories.size,
                            packageName
                        )
                    )
                }
            }
            jobs.joinAll()
            _progressLiveData.postValue(AppCategoryUploadState.Done)
        }
    }
}

sealed class AppCategoryUploadState {
    data class Uploading(val progress: Int, val packageName: String) : AppCategoryUploadState()
    data object Done : AppCategoryUploadState()
}
