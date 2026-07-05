package me.gm.cleaner.runtime.server.hookbridge

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
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

    fun onStart(server: CleanerServer) {
        this.server = server
        Log.i("MC_REDIRECT", "[CleanerHooksClient] onStart: attempting to get hooks service binder...")
        establishConnection(server, "onStart")
    }

    @Synchronized
    private fun establishConnection(ctx: CleanerServer, reason: String): Boolean {
        val newBinder = CleanerHooksBinderRetriever.get(ctx)
        if (newBinder == null) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: FAILED to get binder")
            return false
        }

        unlinkCurrentDeathRecipient()

        val newService = ICleanerHooksService.Stub.asInterface(newBinder)
        val newDeathRecipient = object : SystemServiceDeathRecipient(newBinder) {
            override fun binderDied() {
                super.binderDied()
                Log.w("MC_REDIRECT", "[CleanerHooksClient] binderDied ($reason session), notifying LayerOrchestrator")
                clearDeadConnection()
                server?.layerOrchestrator?.onHooksBinderDied()
            }
        }

        try {
            newBinder.linkToDeath(newDeathRecipient, 0)
        } catch (e: RemoteException) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: linkToDeath failed", e)
            clearDeadConnection()
            return false
        } catch (e: RuntimeException) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: linkToDeath failed", e)
            clearDeadConnection()
            return false
        }

        binder = newBinder
        service = newService
        deathRecipient = newDeathRecipient
        Log.i("MC_REDIRECT", "[CleanerHooksClient] $reason: connected")
        return true
    }

    private fun unlinkCurrentDeathRecipient() {
        val oldBinder = binder
        val oldDeathRecipient = deathRecipient
        if (oldBinder == null || oldDeathRecipient == null) {
            return
        }
        try {
            oldBinder.unlinkToDeath(oldDeathRecipient, 0)
        } catch (e: RuntimeException) {
            Log.w("MC_REDIRECT", "[CleanerHooksClient] unlinkToDeath failed", e)
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
        server = ctx
        return establishConnection(ctx, "tryReconnect")
    }

    @JvmStatic
    fun whileAlive(c: Consumer<ICleanerHooksService>) {
        // Try to get/refresh the hooks service if not available
        if (service == null || !pingBinder()) {
            Log.w("MC_REDIRECT", "[CleanerHooksClient] whileAlive: reconnecting (service=" 
                    + (service != null) + " ping=" + (service?.let { pingBinder() } ?: false) + ")")
            val ctx = server ?: return
            if (!tryReconnect(ctx)) {
                Log.e("MC_REDIRECT", "[CleanerHooksClient] whileAlive: reconnect failed")
                return
            }
            try {
                service?.setCleanerServerBinder(ctx.mCleanerServerCallback)
            } catch (e: RemoteException) {
                Log.e("MC_REDIRECT", "[CleanerHooksClient] Failed to re-register callback", e)
                clearDeadConnection()
                return
            }
        }
        val s = service ?: return
        if (pingBinder()) {
            c.accept(s)
        }
    }

    fun onDestroy() {
        unlinkCurrentDeathRecipient()
        clearDeadConnection()
    }
}

