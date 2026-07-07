package me.gm.cleaner.client;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.gm.cleaner.server.ICleanerHooksService;
import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

/**
 * ContentProvider-based Binder bridge that replaces the Zygisk IPC channel.
 *
 * <p>This provider runs in the app process (me.gm.cleaner) and mediates communication
 * between the root Server process and the Xposed hooks in the MediaProvider process.
 * Both sides access it via {@link android.content.ContentResolver#call}.</p>
 *
 * <p>The Xposed side registers its {@link IMediaProviderHooksService} binder via
 * {@code register_hooks_callback}. The Server side receives an {@link ICleanerHooksService}
 * proxy that forwards relevant sync methods (setCleanerServerBinder, refreshPolicyFromDataBus, etc.) to
 * the registered Xposed service.</p>
 *
 * <p>Authority: {@code me.gm.cleaner.hooks_bridge}</p>
 */
public class HooksBridgeProvider extends ContentProvider {
    private static final String TAG = "HooksBridge";

    /** Xposed-side service registered from MediaProvider process. */
    @GuardedBy("sMediaProviderLock")
    private static volatile IMediaProviderHooksService sMediaProviderService;
    private static final Object sMediaProviderLock = new Object();

    /** DeathRecipient on sMediaProviderService Binder — detects Xposed/MediaProvider crash. */
    private static volatile IBinder.DeathRecipient sXposedDeathRecipient;

    /** 查询 Xposed 模块是否已注册到 HooksBridge */
    public static boolean isMediaProviderConnected() {
        IMediaProviderHooksService service = getAliveMediaProviderService();
        if (service == null) {
            return false;
        }
        try {
            service.getVersion();
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to query MediaProvider hook version", e);
            markMediaProviderDisconnected(service, "query version failed");
            return false;
        }
    }

    @Nullable
    private static IMediaProviderHooksService getAliveMediaProviderService() {
        IMediaProviderHooksService service = sMediaProviderService;
        if (service == null) {
            return null;
        }
        if (service.asBinder().pingBinder()) {
            return service;
        }
        markMediaProviderDisconnected(service, "pingBinder returned false");
        return null;
    }

    @GuardedBy("sMediaProviderLock")
    private static void unlinkXposedDeathRecipientLocked(@Nullable IMediaProviderHooksService service) {
        if (service == null || sXposedDeathRecipient == null) {
            return;
        }
        try {
            service.asBinder().unlinkToDeath(sXposedDeathRecipient, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unlink DeathRecipient from Xposed binder", e);
        }
    }

    private static void markMediaProviderDisconnected(
            @Nullable IMediaProviderHooksService expectedService,
            @NonNull String reason
    ) {
        boolean changed = false;
        synchronized (sMediaProviderLock) {
            if (expectedService == null || sMediaProviderService == expectedService) {
                unlinkXposedDeathRecipientLocked(sMediaProviderService);
                sMediaProviderService = null;
                sXposedDeathRecipient = null;
                changed = true;
            }
        }
        if (changed) {
            Log.w(TAG, "Xposed/MediaProvider binder disconnected: " + reason);
            XposedConnectionState.INSTANCE.onDisconnected();
            ServerStateMachine.INSTANCE.onXposedConnected(false);
        }
    }

    /** Server-side callback registered from root process. */
    private static volatile ICleanerServerCallback sServerCallback;

    /** Flag to indicate server needs to re-send callback when Xposed connects */
    private static volatile boolean sNeedsServerCallbackForward = false;

    /**
     * Singleton {@link ICleanerHooksService} implementation that runs in the app process
     * and forwards calls to the Xposed-side service where appropriate.
     */
    private static final ICleanerHooksService sHooksService = new ICleanerHooksService.Stub() {
        @Override
        public void setCleanerServerBinder(ICleanerServerCallback callback) {
            sServerCallback = callback;
            sNeedsServerCallbackForward = true;
            // Forward to Xposed if registered
            IMediaProviderHooksService xposed = getAliveMediaProviderService();
            Log.i("MC_REDIRECT", "[HooksBridge] setCleanerServerBinder: sMediaProviderService=" + (xposed != null));
            if (xposed != null) {
                try {
                    xposed.setCleanerServerBinder(callback);
                    sNeedsServerCallbackForward = false;
                    Log.i("MC_REDIRECT", "[HooksBridge] Forwarded setCleanerServerBinder to Xposed");
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setCleanerServerBinder to Xposed", e);
                    markMediaProviderDisconnected(xposed, "forward setCleanerServerBinder failed");
                }
            } else {
                Log.w("MC_REDIRECT", "[HooksBridge] sMediaProviderService is null, will forward when Xposed connects");
            }
        }

        @Override
        public void setMediaProviderBinder(IMediaProviderHooksService service) {
            if (service == null) {
                markMediaProviderDisconnected(null, "null binder registered");
                return;
            }
            boolean connected = false;
            boolean linkFailed = false;
            synchronized (sMediaProviderLock) {
                // 取消已有 DeathRecipient（无论 Binder 是否相同，防止堆积）
                IMediaProviderHooksService old = sMediaProviderService;
                unlinkXposedDeathRecipientLocked(old);
                // 注册新 DeathRecipient
                sMediaProviderService = service;
                final IMediaProviderHooksService registeredService = service;
                sXposedDeathRecipient = () -> {
                    markMediaProviderDisconnected(registeredService, "binderDied");
                };
                try {
                    service.asBinder().linkToDeath(sXposedDeathRecipient, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to link DeathRecipient to Xposed binder", e);
                    sMediaProviderService = null;
                    sXposedDeathRecipient = null;
                    linkFailed = true;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to link DeathRecipient to Xposed binder", e);
                    sMediaProviderService = null;
                    sXposedDeathRecipient = null;
                    linkFailed = true;
                }
                connected = !linkFailed;
            }
            if (linkFailed) {
                markMediaProviderDisconnected(null, "linkToDeath failed during registration");
                return;
            }
            if (connected) {
                XposedConnectionState.INSTANCE.onConnected();
                ServerStateMachine.INSTANCE.onXposedConnected(true);
            }
            // 如果 Server 回调已注册，补发给 Xposed
            ICleanerServerCallback callback = sServerCallback;
            Log.i("MC_REDIRECT", "[HooksBridge] setMediaProviderBinder: sServerCallback=" + (callback != null)
                    + " needsForward=" + sNeedsServerCallbackForward);
            if (callback != null && callback.asBinder().pingBinder()) {
                try {
                    service.setCleanerServerBinder(callback);
                    sNeedsServerCallbackForward = false;
                    Log.i("MC_REDIRECT", "[HooksBridge] Forwarded setCleanerServerBinder to new Xposed instance");
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setCleanerServerBinder to Xposed", e);
                    markMediaProviderDisconnected(service, "forward callback to new Xposed instance failed");
                }
            } else if (sNeedsServerCallbackForward) {
                // Server callback was set in a previous app instance but lost on restart.
                // Try to trigger server reconnection by touching the hooks service.
                Log.w("MC_REDIRECT", "[HooksBridge] sServerCallback is null but was previously set. "
                        + "Attempting to trigger server reconnect...");
                // The server will reconnect via CleanerHooksClient.whileAlive when any operation is attempted.
                // We signal the need for reconnection by setting a flag that the app's MainActivity can check.
            } else {
                Log.w("MC_REDIRECT", "[HooksBridge] sServerCallback is null and never was set. "
                        + "Waiting for server to register callback.");
            }
        }

        @Override
        public void refreshPolicyFromDataBus() {
            IMediaProviderHooksService xposed = getAliveMediaProviderService();
            if (xposed != null) {
                try {
                    xposed.refreshPolicyFromDataBus();
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward refreshPolicyFromDataBus to Xposed", e);
                    markMediaProviderDisconnected(xposed, "forward refreshPolicyFromDataBus failed");
                }
            }
        }

        @Override
        public long getNativeMountPointsGeneration() {
            IMediaProviderHooksService xposed = getAliveMediaProviderService();
            if (xposed != null) {
                try {
                    return xposed.getNativeMountPointsGeneration();
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward getNativeMountPointsGeneration to Xposed", e);
                    markMediaProviderDisconnected(xposed, "forward getNativeMountPointsGeneration failed");
                }
            }
            return 0L;
        }

        @Override
        public boolean isMediaProviderHookConnected() {
            return isMediaProviderConnected();
        }

        @Override
        public String getNativeHookStatusJson() {
            IMediaProviderHooksService xposed = getAliveMediaProviderService();
            if (xposed != null) {
                try {
                    return xposed.getNativeHookStatusJson();
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward getNativeHookStatusJson to Xposed", e);
                    markMediaProviderDisconnected(xposed, "forward getNativeHookStatusJson failed");
                }
            }
            return "{\"mediaProviderHookLoaded\":false,\"lastError\":\"MediaProvider hook binder unavailable\"}";
        }
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        switch (method) {
            case "register_hooks_callback": {
                if (extras == null) break;
                IBinder binder = extras.getBinder("binder");
                if (binder != null) {
                    try {
                        sHooksService.setMediaProviderBinder(
                                IMediaProviderHooksService.Stub.asInterface(binder));
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to set media provider binder", e);
                    }
                }
                break;
            }
            case "get_hooks_service": {
                Bundle result = new Bundle();
                result.putBinder("binder", sHooksService.asBinder());
                return result;
            }
        }
        return Bundle.EMPTY;
    }

    // ---- ContentProvider required stubs ----

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
