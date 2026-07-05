package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.function.Consumer;
import java.util.regex.Pattern;

import api.SystemService;
import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

public class MediaProviderHooksService extends IMediaProviderHooksService.Stub {
    private volatile ICleanerServerCallback mCleanerServerBinder = null;
    private final IBinder.DeathRecipient mCleanerServerDeathRecipient = () -> {
        mCleanerServerBinder = null;
        requestReRegister("server callback died");
    };

    /** 当 Server 回调丢失时由 XposedInit 注入的重新注册回调 */
    public static volatile Runnable sReRegisterCallback;

    /**
     * 初始化本地策略缓存 + 启动定时刷新调度器。
     * 应在 Xposed 模块加载时调用一次。
     */
    public void initPolicyCache() {
        HookPolicyCache.INSTANCE.initFromDataBus();
        // 启动定时刷新调度器（每 5s 检查信号并刷新 native 挂载点）
        HookPolicyRefreshScheduler.INSTANCE.start();
    }

    public void whileAlive(Consumer<ICleanerServerCallback> c) {
        if (mCleanerServerBinder != null) {
            c.accept(mCleanerServerBinder);
        } else {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] whileAlive: mCleanerServerBinder is NULL! Callback dropped.");
            requestReRegister("server callback missing while dispatching event");
        }
    }

    public static void requestReRegister(@NonNull String reason) {
        final var callback = sReRegisterCallback;
        if (callback == null) {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] Re-register requested before callback is ready: " + reason);
            return;
        }
        Log.i("MC_REDIRECT", "[MediaProviderHooksService] Re-registering hooks callback: " + reason);
        callback.run();
    }

    private void unlinkCleanerServerDeathRecipient(ICleanerServerCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.asBinder().unlinkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (RuntimeException e) {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] unlinkToDeath failed", e);
        }
    }

    @Override
    public int getVersion() {
        return HookRuntimeConfig.VERSION_CODE;
    }

    @Override
    public void setCleanerServerBinder(ICleanerServerCallback iinterface) {
        Log.i("MC_REDIRECT", "[MediaProviderHooksService] setCleanerServerBinder called, binder=" + (iinterface != null));
        // 先取消旧 DeathRecipient，防止多次 link 导致重复触发
        unlinkCleanerServerDeathRecipient(mCleanerServerBinder);
        mCleanerServerBinder = iinterface;
        if (iinterface == null) {
            HookPolicyCache.INSTANCE.tryRefreshNativeMountPoints();
            return;
        }
        try {
            iinterface.asBinder().linkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (final RemoteException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
            mCleanerServerBinder = null;
            requestReRegister("server callback linkToDeath failed");
            return;
        } catch (final RuntimeException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
            mCleanerServerBinder = null;
            requestReRegister("server callback linkToDeath failed");
            return;
        }
        // 尝试从 DataBus 刷新 native 挂载点（独立于 Binder 同步）
        HookPolicyCache.INSTANCE.refreshFromDataBus();
    }

    private static final Pattern PATHS_HAVE_USER_ID = Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/.*)?");

    private String getPathAsUser(@NonNull String path, int userId) {
        final var m = PATHS_HAVE_USER_ID.matcher(path);
        if (!m.matches()) {
            return path;
        }
        final var sb = new StringBuilder();
        for (int i = 1; i <= m.groupCount(); i++) {
            final var group = m.group(i);
            if (group == null) {
                continue;
            } else if (TextUtils.isDigitsOnly(group)) {
                sb.append(userId);
            } else {
                sb.append(group);
            }
        }
        return sb.toString();
    }

    public boolean isReadOnly(@NonNull String path, int uid) {
        final var packages = SystemService.getPackagesForUidNoThrow(uid);
        if (packages.isEmpty()) {
            return false;
        }
        final var packageName = packages.get(0);
        final var pathAsUser = getPathAsUser(path, 0);

        // 使用 HookPolicyCache（来自 DataBus 快照）
        // DataBus 是只读策略的唯一分发通道；Binder fallback 路径已移除。
        if (HookPolicyCache.INSTANCE.getReadOnlyGeneration() > 0) {
            return HookPolicyCache.INSTANCE.isReadOnly(packageName, pathAsUser);
        }
        return false;
    }

    @Override
    public void refreshPolicyFromDataBus() {
        HookPolicyCache.INSTANCE.refreshFromDataBus();
    }

    @Override
    public long getNativeMountPointsGeneration() {
        return HookPolicyCache.INSTANCE.getNativeMountPointsGeneration();
    }
}
