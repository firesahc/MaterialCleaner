package me.gm.cleaner.xposed;

import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import api.SystemService;
import me.gm.cleaner.server.BuildConfig;
import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

public class MediaProviderHooksService extends IMediaProviderHooksService.Stub {
    private final Map<String, List<String>> mPackageNameToReadOnlyPaths = new ConcurrentHashMap<>();
    private volatile ICleanerServerCallback mCleanerServerBinder = null;
    private final IBinder.DeathRecipient mCleanerServerDeathRecipient = () -> {
        mCleanerServerBinder = null;
        // Server died → 尝试重新注册 hooks callback（应对 app 进程重启场景）
        if (sReRegisterCallback != null) {
            Log.i("MC_REDIRECT", "[MediaProviderHooksService] Server died, re-registering with app process...");
            sReRegisterCallback.run();
        }
    };

    /** 当 Server 回调丢失时由 XposedInit 注入的重新注册回调 */
    public static volatile Runnable sReRegisterCallback;

    public void whileAlive(Consumer<ICleanerServerCallback> c) {
        if (mCleanerServerBinder != null) {
            c.accept(mCleanerServerBinder);
        } else {
            Log.w("MC_REDIRECT", "[MediaProviderHooksService] whileAlive: mCleanerServerBinder is NULL! Callback dropped.");
            // 尝试重新注册（最多触发一次，由 server died 后的首次调用触发）
            if (sReRegisterCallback != null) {
                Log.i("MC_REDIRECT", "[MediaProviderHooksService] whileAlive: attempting re-registration...");
                sReRegisterCallback.run();
            }
        }
    }

    @Override
    public int getVersion() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public void setCleanerServerBinder(ICleanerServerCallback iinterface) {
        Log.i("MC_REDIRECT", "[MediaProviderHooksService] setCleanerServerBinder called, binder=" + (iinterface != null));
        mCleanerServerBinder = iinterface;
        try {
            iinterface.asBinder().linkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (final RemoteException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
        }
        // 新 callback 到达 → app 侧存活 → 确保 app 持有最新 Xposed Binder
        // 异步投递到主线程，避免 Binder 事务嵌套导致的死锁
        if (sReRegisterCallback != null) {
            Log.i("MC_REDIRECT", "[MediaProviderHooksService] Re-registering hooks callback after new server binder");
            new Handler(Looper.getMainLooper()).post(sReRegisterCallback);
        }
    }

    @Override
    public void setReadOnlyPaths(@NonNull Map<String, List> packageNameToReadOnlyPaths) {
        mPackageNameToReadOnlyPaths.clear();
        mPackageNameToReadOnlyPaths.putAll(
                (Map<String, List<String>>) (Object) packageNameToReadOnlyPaths);
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
        final var readOnlyPaths = mPackageNameToReadOnlyPaths.get(packages.get(0));
        if (readOnlyPaths == null) {
            return false;
        }
        final var pathAsUser = getPathAsUser(path, 0);
        final var parent = new File(pathAsUser).getParent();
        return readOnlyPaths.stream().anyMatch(readOnlyPath ->
                readOnlyPath.equalsIgnoreCase(pathAsUser) || readOnlyPath.equalsIgnoreCase(parent)
        );
    }

    @Override
    public void setMountPoint(List<String> value) {
        InlineHookConfig.INSTANCE.setMountPoint(value.stream().toArray(String[]::new));
    }

    @Override
    public void setRecordExternalAppSpecificStorage(boolean value) {
        InlineHookConfig.INSTANCE.setRecordExternalAppSpecificStorage(value);
    }
}
