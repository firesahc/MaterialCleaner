package me.gm.cleaner.runtime.server;

import android.os.Binder;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import api.SystemService;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import me.gm.cleaner.server.ICleanerServerCallback;

/**
 * Server-side DataBus proxy exposed to the MediaProvider Hook process.
 *
 * The Hook layer reads policies and writes events through DataBus. The callback
 * only provides a privileged server-side proxy when direct filesystem access is
 * unavailable; it must not become a policy or UI side-effect endpoint again.
 */
public class CleanerServerCallback extends ICleanerServerCallback.Stub {
    private static final String TAG = "CleanerServerCallback";
    private static final String[] MEDIA_PROVIDER_PACKAGES = {
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module"
    };

    private static boolean isPrivilegedServerUid(int uid) {
        final var appId = RuntimeFileUtils.INSTANCE.toAppId(uid);
        return appId == Process.ROOT_UID ||
                appId == Process.SYSTEM_UID ||
                appId == Process.SHELL_UID;
    }

    private static boolean isKnownMediaProviderPackage(String packageName) {
        for (final var candidate : MEDIA_PROVIDER_PACKAGES) {
            if (candidate.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownMediaProviderUid(int uid) {
        final var packages = SystemService.getPackagesForUidNoThrow(uid);
        for (final var packageName : packages) {
            if (isKnownMediaProviderPackage(packageName)) {
                return true;
            }
        }

        final var userId = RuntimeFileUtils.INSTANCE.toUserId(uid);
        final var callerAppId = RuntimeFileUtils.INSTANCE.toAppId(uid);
        for (final var packageName : MEDIA_PROVIDER_PACKAGES) {
            final var pi = SystemService.getPackageInfoNoThrow(packageName, 0, userId);
            if (pi != null && RuntimeFileUtils.INSTANCE.toAppId(pi.applicationInfo.uid) == callerAppId) {
                return true;
            }
        }
        return false;
    }

    private static void enforceHookCaller(String method) {
        final var callingPid = Binder.getCallingPid();
        final var callingUid = Binder.getCallingUid();
        if (callingPid == Os.getpid() ||
                isPrivilegedServerUid(callingUid) ||
                isKnownMediaProviderUid(callingUid)) {
            return;
        }
        Log.w(TAG, "Rejected callback caller: method=" + method +
                " uid=" + callingUid + " pid=" + callingPid);
        throw new SecurityException("Unauthorized CleanerServerCallback caller: " + method);
    }

    private static boolean isHookWritableSnapshot(String name) {
        return DataBus.SNAPSHOT_NATIVE_HOOK_STATUS.equals(name);
    }

    private static boolean isHookWritableSignal(String name) {
        return DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED.equals(name) ||
                DataBus.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED.equals(name) ||
                DataBus.SIGNAL_QUERY_SESSION_LEASES_CHANGED.equals(name) ||
                DataBus.SIGNAL_NATIVE_HOOK_STATUS_CHANGED.equals(name);
    }

    @Override
    public String readDataBusSnapshot(String name) {
        enforceHookCaller("readDataBusSnapshot");
        final var snapshot = DataBus.INSTANCE.readSnapshot(name);
        return snapshot == null ? "" : snapshot;
    }

    @Override
    public long getDataBusSignalTimestamp(String name) {
        enforceHookCaller("getDataBusSignalTimestamp");
        return DataBus.INSTANCE.getSignalTimestamp(name);
    }

    @Override
    public long writeDataBusEvent(String queue, String content) {
        enforceHookCaller("writeDataBusEvent");
        return DataBus.INSTANCE.writeEvent(queue, content);
    }

    @Override
    public boolean writeDataBusLease(String category, String key, String content) {
        enforceHookCaller("writeDataBusLease");
        return DataBus.INSTANCE.writeLease(category, key, content);
    }

    @Override
    public boolean writeDataBusSnapshot(String name, String content) {
        enforceHookCaller("writeDataBusSnapshot");
        if (!isHookWritableSnapshot(name)) {
            return false;
        }
        return DataBus.INSTANCE.ensureInitialized() &&
                DataBus.INSTANCE.writeSnapshot(name, content);
    }

    @Override
    public boolean signalDataBus(String name) {
        enforceHookCaller("signalDataBus");
        if (!isHookWritableSignal(name)) {
            return false;
        }
        return DataBus.INSTANCE.signal(name);
    }
}
