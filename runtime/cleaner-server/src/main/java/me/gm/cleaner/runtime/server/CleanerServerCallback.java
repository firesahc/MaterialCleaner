package me.gm.cleaner.runtime.server;

import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import api.SystemService;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.core.storage.redirect.domain.RedirectPolicyDeriver;
import me.gm.cleaner.runtime.server.observer.ActivityManagerLogsObserver;
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver;
import me.gm.cleaner.runtime.server.observer.FileSystemObserver;
import me.gm.cleaner.runtime.server.observer.ObserverManager;
import me.gm.cleaner.server.ICleanerServerCallback;

public class CleanerServerCallback extends ICleanerServerCallback.Stub {
    private final CleanerServer mServer;

    public CleanerServerCallback(final CleanerServer cleanerServer) {
        mServer = cleanerServer;
    }

    @Override
    public boolean waitMount(String packageName, int pid, int uid) throws RemoteException {
        final var ob = ObserverManager.fastGetObserver(ActivityManagerLogsObserver.class);
        if (ob == null) {
            return false;
        }
        return ob.waitMount(packageName, pid, uid);
    }

    @Override
    public void onFileSystemEvent(long timeMillis, String packageName, String path, int flags)
            throws RemoteException {
        final var ob = ObserverManager.fastGetObserver(FileSystemObserver.class);
        if (ob == null) {
            return;
        }
        ob.onEvent(timeMillis, packageName, path, flags);
    }

    // ── 通知助手（隔离路径计算与 UI 广播的副作用） ──

    /**
     * 根据当前偏好设置决定是否发送 ACTION_MEDIA_NOT_FOUND 广播。
     * 架构第十章：将 UI 广播从路径计算方法中分离到此通知层。
     * 路径计算方法不得直接产生 UI 副作用，应委托本方法处理。
     */
    private void notifyMediaNotFound(final String packageName, final String path) {
        mServer.broadcastIntent(broadcastIntent -> {
            broadcastIntent
                    .setAction(ServerConstants.ACTION_MEDIA_NOT_FOUND)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME,
                            SystemService.getPackageInfoNoThrow(packageName, 0, 0))
                    .putExtra(Intent.EXTRA_TEXT, path)
                    .putExtra(Intent.EXTRA_STREAM,
                            RuntimeFileUtils.INSTANCE.getPathAsUser(path, 0));
        });
    }

    // For insert
    @Override
    public String getMountedPath(final String packageName, String path, final int type) {
        try {
            path = new File(path).getCanonicalPath();
        } catch (final IOException e) {
            Log.w("MC_REDIRECT", "[ServerCallback] getCanonicalPath failed", e);
        }
        final var userId = RuntimeFileUtils.INSTANCE.extractUserIdFromPath(path, 0);
        final var policy = RuntimeRedirectPolicyFactory.INSTANCE.build(
                Collections.singletonList(userId));
        final var mountedPath = RedirectPolicyDeriver.INSTANCE.getMountedPath(
                policy, packageName, userId, path);
        Log.i("MC_REDIRECT", "[ServerCallback] getMountedPath pkg=" + packageName
                + " original=" + path + " mounted=" + mountedPath
                + " policies=" + policy.getStorageRedirectRules().size() + " type=" + type);
        // 注意：UI 广播已从本方法移除。
        // InsertHooker 侧通过 DataBus redirect_notice 事件通知 UI，
        // 由 RedirectNoticeConsumer 消费后触发 showRedirectNotice。
        return mountedPath;
    }

    // For query
    private final Map<String, Map<String, String>> mPackageNameToMountedPathToPath =
            Collections.synchronizedMap(new HashMap<>());

    @Override
    public boolean setQueriedPaths(final String packageName, final List<String> paths) {
        if (ServicePreferences.INSTANCE.getDenylist().contains(packageName)) {
            return false;
        }
        final var userId = RuntimeFileUtils.INSTANCE.extractUserIdFromPath(paths.get(0), 0);
        final var policy = RuntimeRedirectPolicyFactory.INSTANCE.build(
                Collections.singletonList(userId));
        for (final var path : paths) {
            final var mountedPath = RedirectPolicyDeriver.INSTANCE.getMountedPath(
                    policy, packageName, userId, path);
            if (!mountedPath.equals(path)) {
                if (ServicePreferences.INSTANCE.getRecordExternalAppSpecificStorage()) {
                    final var mountedPathToPath = mPackageNameToMountedPathToPath
                            .compute(packageName, (key, oldValue) -> oldValue == null ?
                                    new ConcurrentHashMap<>() : oldValue);
                    mountedPathToPath.put(mountedPath, path);
                    new File(mountedPath).mkdirs();
                } else if (ServicePreferences.INSTANCE.getAggressivelyPromptForReadingMediaFiles()) {
                    notifyMediaNotFound(packageName, path);
                    return false;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean onMaybeAccessQueriedPaths(final String packageName, final String mountedPath) {
        final var mountedPathToPath = mPackageNameToMountedPathToPath.get(packageName);
        if (mountedPathToPath == null) {
            return false;
        }
        final var path = mountedPathToPath.get(mountedPath);
        if (path == null) {
            return false;
        }
        if (RuntimeFileUtils.INSTANCE.startsWith(RuntimeFileUtils.INSTANCE.getAndroidDataDir(), path) &&
                !RuntimeFileUtils.INSTANCE.isKnownAppDirPaths(path, packageName)) {
            return false;
        }
        if (!ServicePreferences.INSTANCE.getDenylist().contains(packageName)) {
            notifyMediaNotFound(packageName, path);
        }
        return true;
    }

    @Override
    public void onReleaseQueriedPaths(final String packageName) {
        final var mountedPathToPath = mPackageNameToMountedPathToPath.remove(packageName);
        if (mountedPathToPath != null) {
            for (final var mountedPath : mountedPathToPath.keySet()) {
                rmdirSafe(mountedPath);
            }
        }
    }

    public void releaseAll() {
        for (final var mountedPathToPath : mPackageNameToMountedPathToPath.values()) {
            for (final var mountedPath : mountedPathToPath.keySet()) {
                rmdirSafe(mountedPath);
            }
        }
    }

    private void rmdirSafe(final String dir) {
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer != null) {
            final var mountedDirs = observer.getMountedDirs();
            rmdirRecursively(dir, new HashSet<>(mountedDirs));
        }
    }

    private void rmdirRecursively(final String dir, final Set<String> exceptions) {
        if (dir == null || exceptions.contains(dir)) {
            return;
        }
        final var parent = new File(dir).getParent();
        if (RuntimeFileUtils.INSTANCE.rm_dir(dir) == 0) {
            rmdirRecursively(parent, exceptions);
        }
    }
}
