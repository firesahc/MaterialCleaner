package me.gm.cleaner.runtime.server.hookbridge

import android.os.RemoteException
import me.gm.cleaner.client.CleanerHooksClient
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.runtime.server.CleanerServer
import me.gm.cleaner.server.ICleanerHooksService
import org.json.JSONObject
import java.util.function.Consumer

/**
 * Server-side gateway to the MediaProvider Java Hook runtime.
 *
 * This is a logical boundary before the runtime modules are physically split.
 * Server components should depend on this gateway instead of depending on
 * CleanerHooksClient or xposed implementation classes directly.
 */
object MediaProviderHookGateway {

    fun start(server: CleanerServer) {
        CleanerHooksClient.onStart(server)
    }

    fun tryReconnect(server: CleanerServer): Boolean =
        CleanerHooksClient.tryReconnect(server)

    @JvmStatic
    fun whileAlive(action: Consumer<ICleanerHooksService>) {
        CleanerHooksClient.whileAlive(action)
    }

    @JvmStatic
    fun pingBinder(): Boolean =
        CleanerHooksClient.pingBinder()

    fun registerCleanerServerBinder(server: CleanerServer) {
        whileAlive { service ->
            service.setCleanerServerBinder(server.mCleanerServerCallback)
        }
    }

    fun syncAll(server: CleanerServer) {
        whileAlive { service ->
            try {
                service.setCleanerServerBinder(server.mCleanerServerCallback)
                with(CleanerHooksClient) {
                    service.syncReadOnlyPaths()
                    service.syncMountPoint()
                    service.syncRecordExternalAppSpecificStorage()
                }
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    @JvmStatic
    fun syncReadOnlyPaths() {
        whileAlive { service ->
            with(CleanerHooksClient) {
                service.syncReadOnlyPaths()
            }
        }
    }

    @JvmStatic
    fun syncMountPoint() {
        whileAlive { service ->
            with(CleanerHooksClient) {
                service.syncMountPoint()
            }
        }
    }

    @JvmStatic
    fun syncRecordExternalAppSpecificStorage() {
        whileAlive { service ->
            with(CleanerHooksClient) {
                service.syncRecordExternalAppSpecificStorage()
            }
        }
    }

    @JvmStatic
    fun reconnectIfNeeded() {
        whileAlive {
            // Trigger CleanerHooksClient's lazy reconnect path.
        }
    }

    fun nativeMountPointsGeneration(): Long =
        DataBus.readSnapshot(DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
            ?.let { json ->
                runCatching { JSONObject(json).optLong("generation", 0L) }.getOrDefault(0L)
            } ?: 0L

    @JvmStatic
    fun onDestroy() {
        CleanerHooksClient.onDestroy()
    }
}
