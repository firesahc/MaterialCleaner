package me.gm.cleaner.client.ui

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.model.PackageStatus

class AppListLoader(private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default) {

    suspend fun load(): List<AppListModel> = withContext(defaultDispatcher) {
        Log.i("CleanerTest", "AppListLoader.load: start loading packages")
        val installedPackages = try {
            CleanerClient.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            Log.e("CleanerTest", "AppListLoader.load: failed to load packages", e)
            emptyList()
        }
        Log.i("CleanerTest", "AppListLoader.load: installedPackages=${installedPackages.size}")
        AppLabelCache.updatePackageLabelCacheInBulk(installedPackages, true)
        val srPackageStatus = try {
            CleanerClient.service?.getSrPackagesStatus(
                PackageStatus.GET_FROM_ALL_PROCESS
            ) ?: emptyMap()
        } catch (e: Exception) {
            Log.e("CleanerTest", "AppListLoader.load: failed to load srPackageStatus", e)
            emptyMap()
        }
        Log.i("CleanerTest", "AppListLoader.load: srPackageStatus size=${srPackageStatus.size}")
        val result = installedPackages.map { pi ->
            ensureActive()
            AppListModel(
                pi,
                AppLabelCache.getPackageLabel(pi),
                ServicePreferences.getPackageSrCount(pi.packageName),
                ServicePreferences.getPackageReadOnly(pi.packageName).size,
                parseMountState(srPackageStatus[pi.packageName])
            )
        }
        Log.i("CleanerTest", "AppListLoader.load: result=${result.size} apps")
        result
    }

    private fun parseMountState(packageStatus: PackageStatus?): Int {
        packageStatus ?: return AppListModel.STATE_UNMOUNTED
        val mountedPids = mutableListOf<Int>()
        val unknownPids = mutableListOf<Int>()
        packageStatus.pidFlags.forEachIndexed { index, pidFlag ->
            if (pidFlag and PackageStatus.PID_FLAG_MOUNTED != 0) {
                mountedPids += packageStatus.pids[index]
            }
            if (pidFlag and PackageStatus.PID_FLAG_UNKNOWN != 0) {
                unknownPids += packageStatus.pids[index]
            }
        }
        return if (packageStatus.pids.size == mountedPids.size) {
            AppListModel.STATE_MOUNTED
        } else if (unknownPids.isNotEmpty()) {
            AppListModel.STATE_UNKNOWN
        } else {
            AppListModel.STATE_MOUNT_EXCEPTION
        }
    }

    suspend fun updateRuleCount(old: List<AppListModel>): List<AppListModel> =
        withContext(defaultDispatcher) {
            old.map {
                it.copy(
                    mountRulesCount = ServicePreferences.getPackageSrCount(it.packageInfo.packageName),
                    readOnlyCount = ServicePreferences.getPackageReadOnly(it.packageInfo.packageName).size
                )
            }
        }
}
