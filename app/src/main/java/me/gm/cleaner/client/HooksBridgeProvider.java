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

import java.util.List;
import java.util.Map;

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
 * proxy that forwards relevant sync methods (setReadOnlyPaths, setMountPoint, etc.) to
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
        return sMediaProviderService != null;
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
            IMediaProviderHooksService xposed = sMediaProviderService;
            Log.i("MC_REDIRECT", "[HooksBridge] setCleanerServerBinder: sMediaProviderService=" + (xposed != null));
            if (xposed != null) {
                try {
                    xposed.setCleanerServerBinder(callback);
                    sNeedsServerCallbackForward = false;
                    Log.i("MC_REDIRECT", "[HooksBridge] Forwarded setCleanerServerBinder to Xposed");
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setCleanerServerBinder to Xposed", e);
                }
            } else {
                Log.w("MC_REDIRECT", "[HooksBridge] sMediaProviderService is null, will forward when Xposed connects");
            }
        }

        @Override
        public void setMediaProviderBinder(IMediaProviderHooksService service) {
            synchronized (sMediaProviderLock) {
                // 取消旧 DeathRecipient（无论 Binder 是否相同，防止堆积）
                IMediaProviderHooksService old = sMediaProviderService;
                if (old != null && sXposedDeathRecipient != null) {
                    old.asBinder().unlinkToDeath(sXposedDeathRecipient, 0);
                }
                // 注册新 DeathRecipient
                sMediaProviderService = service;
                sXposedDeathRecipient = () -> {
                    Log.w(TAG, "Xposed/MediaProvider binder died");
                    XposedConnectionState.INSTANCE.onDisconnected();
                    ServerStateMachine.INSTANCE.onXposedConnected(false);
                    synchronized (sMediaProviderLock) {
                        sMediaProviderService = null;
                    }
                };
                try {
                    service.asBinder().linkToDeath(sXposedDeathRecipient, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to link DeathRecipient to Xposed binder", e);
                }
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
        public void setReadOnlyPaths(Map<String, List> paths) {
            IMediaProviderHooksService xposed = sMediaProviderService;
            if (xposed != null) {
                try {
                    xposed.setReadOnlyPaths(paths);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setReadOnlyPaths to Xposed", e);
                }
            }
        }

        @Override
        public void setMountPoint(List<String> value) {
            IMediaProviderHooksService xposed = sMediaProviderService;
            if (xposed != null) {
                try {
                    xposed.setMountPoint(value);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setMountPoint to Xposed", e);
                }
            }
        }

        @Override
        public void setRecordExternalAppSpecificStorage(boolean value) {
            IMediaProviderHooksService xposed = sMediaProviderService;
            if (xposed != null) {
                try {
                    xposed.setRecordExternalAppSpecificStorage(value);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to forward setRecordExternalAppSpecificStorage to Xposed", e);
                }
            }
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
