package me.gm.cleaner.runtime.server.hookbridge

import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import java.util.concurrent.atomic.AtomicBoolean
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

    /** 最近一次 Bridge 重连尝试的单调时钟时间；0 表示从未尝试。 */
    @Volatile
    private var lastBridgeAttemptUptimeMs: Long = 0L

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
        val newService = ICleanerHooksService.Stub.asInterface(newBinder)
        if (newService == null) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: binder did not expose hooks service")
            return false
        }
        val newBinderDied = AtomicBoolean(false)
        val newDeathRecipient = object : SystemServiceDeathRecipient(newBinder) {
            override fun binderDied() {
                newBinderDied.set(true)
                super.binderDied()
                Log.w("MC_REDIRECT", "[CleanerHooksClient] binderDied ($reason session), notifying LayerOrchestrator")
                if (clearDeadConnection(newBinder, this)) {
                    server?.layerOrchestrator?.onHooksBinderDied()
                }
            }
        }

        try {
            newBinder.linkToDeath(newDeathRecipient, 0)
        } catch (e: RemoteException) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: linkToDeath failed", e)
            return false
        } catch (e: RuntimeException) {
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: linkToDeath failed", e)
            return false
        }

        try {
            callbackRegisteredBeforePublish(
                registerCallback = {
                    newService.setCleanerServerBinder(ctx.mCleanerServerCallback)
                    if (newBinderDied.get() || !newBinder.isBinderAlive) {
                        throw RemoteException("hooks service died during callback registration")
                    }
                },
                publish = {
                    val oldBinder = binder
                    val oldService = service
                    val oldDeathRecipient = deathRecipient
                    // Recheck inside the synchronized publish step. The death callback can set the
                    // flag before waiting to clear global state, so a Binder that died during
                    // registration cannot replace the previously working connection.
                    if (newBinderDied.get() || !newBinder.isBinderAlive) {
                        throw RemoteException("hooks service died before connection publish")
                    }
                    binder = newBinder
                    service = newService
                    deathRecipient = newDeathRecipient
                    if (newBinderDied.get() || !newBinder.isBinderAlive) {
                        binder = oldBinder
                        service = oldService
                        deathRecipient = oldDeathRecipient
                        throw RemoteException("hooks service died while publishing connection")
                    }
                    unlinkDeathRecipient(oldBinder, oldDeathRecipient)
                },
            )
        } catch (e: RemoteException) {
            unlinkDeathRecipient(newBinder, newDeathRecipient)
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: callback registration failed", e)
            return false
        } catch (e: RuntimeException) {
            unlinkDeathRecipient(newBinder, newDeathRecipient)
            Log.e("MC_REDIRECT", "[CleanerHooksClient] $reason: callback registration failed", e)
            return false
        }
        Log.i("MC_REDIRECT", "[CleanerHooksClient] $reason: connected")
        return true
    }

    private fun unlinkCurrentDeathRecipient() {
        unlinkDeathRecipient(binder, deathRecipient)
    }

    private fun unlinkDeathRecipient(oldBinder: IBinder?, oldDeathRecipient: IBinder.DeathRecipient?) {
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
    @Synchronized
    fun clearDeadConnection(
        expectedBinder: IBinder? = null,
        expectedDeathRecipient: IBinder.DeathRecipient? = null,
    ): Boolean {
        if (expectedBinder != null &&
            (binder !== expectedBinder || deathRecipient !== expectedDeathRecipient)
        ) {
            return false
        }
        binder = null
        service = null
        deathRecipient = null
        return true
    }

    /**
     * 尝试重新建立 Hooks 服务连接。
     * 获取到 binder 并包装 service stub 后，会通过回调通知 orchestrator。
     *
     * @param respectThrottle 懒调用路径（如 whileAlive）应保持 true 以限制
     *   App 冻结/重启期间的无边界 Binder 查询；协调器恢复路径自带长周期
     *   退避与冷却，显式传 false 绕过本层节流。
     * @return true 表示成功建立连接，false 表示获取 binder 失败或被节流
     */
    fun tryReconnect(ctx: CleanerServer, respectThrottle: Boolean = true): Boolean {
        val nowUptimeMs = SystemClock.elapsedRealtime()
        if (respectThrottle && !HookBridgeReconnectThrottlePolicy.shouldAttempt(
                nowUptimeMs, lastBridgeAttemptUptimeMs)
        ) {
            Log.i("MC_REDIRECT", "[CleanerHooksClient] tryReconnect: throttled")
            return false
        }
        lastBridgeAttemptUptimeMs = nowUptimeMs
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

/** 回调注册是连接发布的前置条件；注册抛错时不得运行发布步骤。 */
internal inline fun <T> callbackRegisteredBeforePublish(
    registerCallback: () -> Unit,
    publish: () -> T,
): T {
    registerCallback()
    return publish()
}

