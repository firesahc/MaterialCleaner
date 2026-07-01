package me.gm.cleaner.client

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import api.SystemService
import me.gm.cleaner.dao.ServicePreferences
import me.gm.cleaner.dao.policy.RedirectPolicyBuilder
import me.gm.cleaner.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import me.gm.cleaner.server.ICleanerServerCallback
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
                Log.w("MC_REDIRECT", "[CleanerHooksClient] binderDied: notifying LayerOrchestrator")
                clearDeadConnection()
                server?.layerOrchestrator?.onHooksBinderDied()
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

    /**
     * 清除已死亡连接的所有引用。
     * 在 binderDied 回调中调用，确保后续重连从干净状态开始。
     */
    fun clearDeadConnection() {
        binder = null
        service = null
        deathRecipient = null
    }

    /**
     * 尝试重新建立 Hooks 服务连接。
     * 获取新 binder、创建 service stub、设置死亡回调（递归通知 orchestrator）。
     * @return true 如果成功建立连接，false 如果获取 binder 失败
     */
    fun tryReconnect(ctx: CleanerServer): Boolean {
        Log.i("MC_REDIRECT", "[CleanerHooksClient] tryReconnect: attempting...")
        val newBinder = CleanerHooksBinderRetriever.get(ctx)
        if (newBinder == null) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] tryReconnect: FAILED to get binder")
            return false
        }
        binder = newBinder
        service = ICleanerHooksService.Stub.asInterface(newBinder)

        // 设置新的死亡回调：递归通知 orchestrator
        val newDeathRecipient = object : SystemServiceDeathRecipient(newBinder) {
            override fun binderDied() {
                super.binderDied()
                Log.w("MC_REDIRECT", "[CleanerHooksClient] binderDied (reconnected session), re-notifying orchestrator")
                clearDeadConnection()
                server?.layerOrchestrator?.onHooksBinderDied()
            }
        }
        deathRecipient = newDeathRecipient
        try {
            newBinder.linkToDeath(newDeathRecipient, 0)
        } catch (e: RemoteException) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] tryReconnect: linkToDeath failed", e)
            clearDeadConnection()
            return false
        }

        Log.i("MC_REDIRECT", "[CleanerHooksClient] tryReconnect: SUCCESS")
        return true
    }

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
        val userIds = SystemService.getUserIdsNoThrow()
        val policy = RedirectPolicyBuilder.build(userIds)
        val snapshot = RedirectPolicyBuilder.buildConfiguredMountPoints(policy)
        setMountPoint(snapshot.points)
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
