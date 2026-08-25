package me.gm.cleaner.client;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;

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
    private static final String METHOD_REGISTER_HOOKS_CALLBACK = "register_hooks_callback";
    private static final String METHOD_GET_HOOKS_SERVICE = "get_hooks_service";
    private static final String EXTRA_BINDER = "binder";
    private static final String EXTRA_REGISTERED = "registered";
    private static final int AID_USER_OFFSET = 100000;
    private static final String[] MEDIA_PROVIDER_PACKAGES = {
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module"
    };

    private static volatile Context sAppContext;

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

    private static int toAppId(int uid) {
        return uid % AID_USER_OFFSET;
    }

    private static boolean isSelfUid(int uid) {
        return uid == Process.myUid();
    }

    private static boolean isPrivilegedServerUid(int uid) {
        int appId = toAppId(uid);
        return appId == Process.ROOT_UID
                || appId == Process.SYSTEM_UID
                || appId == Process.SHELL_UID;
    }

    private static boolean isKnownMediaProviderPackage(@Nullable String packageName) {
        if (packageName == null) {
            return false;
        }
        for (String candidate : MEDIA_PROVIDER_PACKAGES) {
            if (candidate.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownMediaProviderUid(@Nullable Context context, int uid) {
        if (context == null) {
            return false;
        }
        // Query with the provider app identity so Android 11+ package visibility is stable.
        final long token = Binder.clearCallingIdentity();
        try {
            PackageManager packageManager = context.getPackageManager();
            String[] packages = packageManager.getPackagesForUid(uid);
            if (packages != null) {
                for (String packageName : packages) {
                    if (isKnownMediaProviderPackage(packageName)) {
                        return true;
                    }
                }
            }

            int callerAppId = toAppId(uid);
            for (String packageName : MEDIA_PROVIDER_PACKAGES) {
                try {
                    int packageUid = packageManager.getPackageUid(packageName, 0);
                    if (toAppId(packageUid) == callerAppId) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to verify MediaProvider caller uid=" + uid, e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isAuthorizedGetHooksServiceCaller(int uid) {
        return isSelfUid(uid) || isPrivilegedServerUid(uid);
    }

    private static boolean isAuthorizedRegisterHooksCallbackCaller(@Nullable Context context, int uid) {
        return isSelfUid(uid) || isKnownMediaProviderUid(context, uid);
    }

    private static String describePackagesForUid(@Nullable Context context, int uid) {
        if (context == null) {
            return "context_unavailable";
        }
        final long token = Binder.clearCallingIdentity();
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages == null ? "[]" : Arrays.toString(packages);
        } catch (RuntimeException e) {
            return "query_failed:" + e.getClass().getSimpleName();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void rejectUnauthorizedCaller(
            @NonNull String entryPoint,
            @NonNull String method,
            int uid,
            int pid
    ) {
        Context context = sAppContext;
        String packages = describePackagesForUid(context, uid);
        Log.w(TAG, "Rejected unauthorized " + entryPoint + ": method=" + method
                + " uid=" + uid + " pid=" + pid + " packages=" + packages);
        throw new SecurityException("Unauthorized HooksBridge caller: " + method);
    }

    private static void enforceHooksServiceServerCaller(@NonNull String method) {
        int uid = Binder.getCallingUid();
        if (isAuthorizedGetHooksServiceCaller(uid)) {
            return;
        }
        rejectUnauthorizedCaller("service", method, uid, Binder.getCallingPid());
    }

    private static void enforceHooksServiceMediaProviderCaller(@NonNull String method) {
        int uid = Binder.getCallingUid();
        if (isAuthorizedRegisterHooksCallbackCaller(sAppContext, uid)) {
            return;
        }
        rejectUnauthorizedCaller("service", method, uid, Binder.getCallingPid());
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
            enforceHooksServiceServerCaller("setCleanerServerBinder");
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
            enforceHooksServiceMediaProviderCaller("setMediaProviderBinder");
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
                // We signal the need for reconnection by setting a flag that the foreground UI can check.
            } else {
                Log.w("MC_REDIRECT", "[HooksBridge] sServerCallback is null and never was set. "
                        + "Waiting for server to register callback.");
            }
        }

        @Override
        public void refreshPolicyFromDataBus() {
            enforceHooksServiceServerCaller("refreshPolicyFromDataBus");
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
            enforceHooksServiceServerCaller("getNativeMountPointsGeneration");
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
            enforceHooksServiceServerCaller("isMediaProviderHookConnected");
            return isMediaProviderConnected();
        }

        @Override
        public String getNativeHookStatusJson() {
            enforceHooksServiceServerCaller("getNativeHookStatusJson");
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
        Context context = getContext();
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        return true;
    }

    private void enforceProviderCaller(@NonNull String method) {
        int uid = Binder.getCallingUid();
        boolean allowed;
        switch (method) {
            case METHOD_REGISTER_HOOKS_CALLBACK:
                allowed = isAuthorizedRegisterHooksCallbackCaller(getContext(), uid);
                break;
            case METHOD_GET_HOOKS_SERVICE:
                allowed = isAuthorizedGetHooksServiceCaller(uid);
                break;
            default:
                allowed = false;
                break;
        }
        if (!allowed) {
            rejectUnauthorizedCaller("provider", method, uid, Binder.getCallingPid());
        }
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        switch (method) {
            case METHOD_REGISTER_HOOKS_CALLBACK: {
                enforceProviderCaller(method);
                // 显式确认协议：回传 registered 让 Hook 侧区分
                // "调用未抛异常"与"Binder 真实接入"两件事。
                IBinder binder = extras == null ? null : extras.getBinder(EXTRA_BINDER);
                boolean registered = false;
                if (binder != null && binder.pingBinder()) {
                    try {
                        sHooksService.setMediaProviderBinder(
                                IMediaProviderHooksService.Stub.asInterface(binder));
                        registered = true;
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to set media provider binder", e);
                    }
                }
                Bundle result = new Bundle();
                result.putBoolean(EXTRA_REGISTERED, registered);
                return result;
            }
            case METHOD_GET_HOOKS_SERVICE: {
                enforceProviderCaller(method);
                Bundle result = new Bundle();
                result.putBinder(EXTRA_BINDER, sHooksService.asBinder());
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
