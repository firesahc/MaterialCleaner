package me.gm.cleaner.client

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import api.SystemService
import me.gm.cleaner.dao.MountRules
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import me.gm.cleaner.server.observer.ObserverManager
import me.gm.cleaner.util.SystemPropertiesUtils
import java.util.function.Consumer

object CleanerHooksClient {

    @Volatile
    private var binder: IBinder? = null
    @Volatile
    private var service: ICleanerHooksService? = null
    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null

    fun onStart(server: CleanerServer) {
        binder = CleanerHooksBinderRetriever.get(server) ?: return
        service = ICleanerHooksService.Stub.asInterface(binder)
        deathRecipient = object : SystemServiceDeathRecipient(binder) {
            override fun binderDied() {
                super.binderDied()
                server.handler.post {
                    ObserverManager.stopAllObservers()
                    server.waitSystemServices()
                    server.onStorageManagerServiceReady()
                }
            }
        }
        try {
            deathRecipient?.let { binder?.linkToDeath(it, 0) }
        } catch (e: RemoteException) {
            Log.e("CleanerHooksClient", "error", e)
        }
    }

    @JvmStatic
    fun pingBinder(): Boolean = binder?.pingBinder() == true

    @JvmStatic
    fun whileAlive(c: Consumer<ICleanerHooksService>) {
        val s = service ?: return
        if (pingBinder()) {
            c.accept(s)
        }
    }

    @JvmStatic
    fun ICleanerHooksService.syncSrPackages() {
        if (SystemPropertiesUtils.getBoolean(
                "persist.sys.vold_app_data_isolation_enabled", false
            )!!
        ) {
            setSrPackages(ServicePreferences.srPackages.toList())
        }
    }

    @JvmStatic
    fun ICleanerHooksService.syncReadOnlyPaths() {
        setReadOnlyPaths(ServicePreferences.getAllReadOnly())
    }

    @JvmStatic
    fun ICleanerHooksService.syncMountPoint() {
        val mountPoint = mutableListOf<String>()
        val userIds = SystemService.getUserIdsNoThrow()
        for (packageName in ServicePreferences.srPackages) {
            for (userId in userIds) {
                val rules = MountRules(
                    ServicePreferences.getPackageSrZipped(packageName, userId)
                )
                mountPoint += rules.mountPoint
            }
        }
        setMountPoint(mountPoint)
    }

    @JvmStatic
    fun ICleanerHooksService.syncRecordExternalAppSpecificStorage() {
        setRecordExternalAppSpecificStorage(ServicePreferences.recordExternalAppSpecificStorage)
    }

    fun onDestroy() {
        if (pingBinder()) {
            try {
                deathRecipient?.let { binder?.unlinkToDeath(it, 0) }
            } catch (e: RemoteException) {
                Log.e("CleanerHooksClient", "error", e)
            }
        }
    }
}
