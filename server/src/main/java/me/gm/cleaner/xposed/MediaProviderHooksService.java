package me.gm.cleaner.xposed;

import android.os.IBinder;
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
    private volatile boolean mReRegistering = false;
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

    /** 由 XposedInit 注入的重置并重试注册的回调（用于 setCleanerServerBinder 主动触发） */
    public static volatile Runnable sResetReRegister;

    /**
     * 初始化本地策略缓存。
     * 应在 Xposed 模块加载时调用一次，从 DataBus 加载最后一次快照。
     * 即使快照不可用也不会阻塞——后续查询回退到 Binder。
     * 同时尝试从 DataBus 加载 configured_mount_points 推送到 native。
     */
    public void initPolicyCache() {
        HookPolicyCache.INSTANCE.initFromDataBus();
    }

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
        // 先取消旧 DeathRecipient，防止多次 link 导致重复触发
        if (mCleanerServerBinder != null) {
            mCleanerServerBinder.asBinder().unlinkToDeath(mCleanerServerDeathRecipient, 0);
        }
        mCleanerServerBinder = iinterface;
        try {
            iinterface.asBinder().linkToDeath(mCleanerServerDeathRecipient, 0);
        } catch (final RemoteException e) {
            Log.e("MC_REDIRECT", "[MediaProviderHooksService] linkToDeath failed", e);
        }
        // 新 server callback 到达 → app 侧存活 → 确保 app 持有最新 Xposed Binder
        if (sResetReRegister != null && sReRegisterCallback != null && !mReRegistering) {
            mReRegistering = true;
            try {
                sResetReRegister.run();
            } finally {
                mReRegistering = false;
            }
        }
        // 尝试从 DataBus 刷新 native 挂载点（独立于 Binder 同步）
        HookPolicyCache.INSTANCE.tryRefreshNativeMountPoints();
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
        final var packageName = packages.get(0);
        final var pathAsUser = getPathAsUser(path, 0);

        // 1. 优先使用本地 HookPolicyCache（来自 DataBus 快照）
        if (HookPolicyCache.INSTANCE.getReadOnlyGeneration() > 0) {
            return HookPolicyCache.INSTANCE.isReadOnly(packageName, pathAsUser);
        }

        // 2. 回退到 Binder 下发的只读路径
        final var readOnlyPaths = mPackageNameToReadOnlyPaths.get(packageName);
        if (readOnlyPaths == null) {
            return false;
        }
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
