package me.gm.cleaner.client

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import api.SystemService
import me.gm.cleaner.dao.MountRules
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import me.gm.cleaner.server.ICleanerServerCallback
import me.gm.cleaner.server.observer.ObserverManager
import java.util.function.Consumer

object CleanerHooksClient {

    @Volatile
    private var binder: IBinder? = null
    @Volatile
    private var service: ICleanerHooksService? = null
    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null
    @Volatile
    private var server: CleanerServer? = null
    @Volatile
    private var serverCallback: ICleanerServerCallback? = null

    fun onStart(server: CleanerServer) {
        this.server = server
        Log.i("MC_REDIRECT", "[CleanerHooksClient] onStart: attempting to get hooks service binder...")
        binder = CleanerHooksBinderRetriever.get(server)
        if (binder == null) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] onStart: FAILED to get hooks service binder!")
            return
        }
        Log.i("MC_REDIRECT", "[CleanerHooksClient] onStart: got binder, creating service stub")
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
        // Try to get/refresh the hooks service if not available
        if (service == null || !pingBinder()) {
            Log.w("MC_REDIRECT", "[CleanerHooksClient] whileAlive: reconnecting (service=" 
                    + (service != null) + " ping=" + (service?.let { pingBinder() } ?: false) + ")")
            val ctx = server ?: return
            val newBinder = CleanerHooksBinderRetriever.get(ctx)
            if (newBinder != null) {
                binder = newBinder
                service = ICleanerHooksService.Stub.asInterface(newBinder)
                Log.i("MC_REDIRECT", "[CleanerHooksClient] Reconnected, re-registering callback")
                try {
                    service?.setCleanerServerBinder(ctx.mCleanerServerCallback)
                } catch (e: RemoteException) {
                    Log.e("MC_REDIRECT", "[CleanerHooksClient] Failed to re-register callback", e)
                }
            } else {
                Log.e("MC_REDIRECT", "[CleanerHooksClient] Reconnect failed: getBinder returned null")
                return
            }
        }
        val s = service ?: return
        if (pingBinder()) {
            c.accept(s)
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
