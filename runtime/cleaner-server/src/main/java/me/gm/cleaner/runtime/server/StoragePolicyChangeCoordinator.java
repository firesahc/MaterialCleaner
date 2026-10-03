package me.gm.cleaner.runtime.server;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;

import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.runtime.server.process.PackageInfoMapper;
import me.gm.cleaner.runtime.server.VfsRuntimePolicy;

/**
 * Handles storage redirect configuration change commands.
 *
 * CleanerService remains the Binder facade; this controller owns the side
 * effects needed after config writes: preference reload, snapshot publishing,
 * Hook refresh and VFS remount.
 */
public class StoragePolicyChangeCoordinator {
    private static final String TAG = "StoragePolicyChangeCoordinator";

    private final CleanerServer mServer;

    public StoragePolicyChangeCoordinator(final CleanerServer server) {
        mServer = server;
    }

    public void onPreferencesChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        reloadSharedPreferencesFromDisk();
        // 顺序治理：先构建并更新内存策略（Mounter 的数据源），
        // VFS remount 先切，发布快照与 Hook 刷新后置——上层切换不领先于底层挂载视图。
        // P0：CORRUPT 时 build 抛 IllegalArgumentException，旧内存快照与 DataBus 旧文件保持不变，
        // 这里显式阻断 remount/publish，避免 Binder 异常穿透，保留 last-known-good。
        final me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot snapshot;
        try {
            snapshot = VfsRuntimePolicy.INSTANCE.refreshPolicy();
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "onPreferencesChanged: configured policy CORRUPT, keep last-known-good", e);
            return;
        }
        remountAffectedStorageRedirectPackages(previousPackages);
        SnapshotPublisher.INSTANCE.publishRedirectPolicy(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    public void onStorageRedirectChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        ServicePreferences.INSTANCE.invalidateSrCache();
        PackageInfoMapper.invalidate();
        // 同 M1：refreshPolicy 前置 → VFS 先切 → 发布同一份策略快照 → Hook 异步跟进。
        final me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot snapshot;
        try {
            snapshot = VfsRuntimePolicy.INSTANCE.refreshPolicy();
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "onStorageRedirectChanged: configured policy CORRUPT, keep last-known-good", e);
            return;
        }
        remountAffectedStorageRedirectPackages(previousPackages);
        SnapshotPublisher.INSTANCE.publishStorageRedirectPolicySet(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    public void onReadOnlyChanged() {
        ServicePreferences.INSTANCE.invalidateReadOnlyCache();
        final me.gm.cleaner.core.storage.redirect.domain.RedirectPolicySnapshot snapshot;
        try {
            snapshot = VfsRuntimePolicy.INSTANCE.refreshPolicy();
        } catch (final IllegalArgumentException e) {
            Log.e(TAG, "onReadOnlyChanged: configured policy CORRUPT, keep last-known-good", e);
            return;
        }
        SnapshotPublisher.INSTANCE.publishReadOnly(snapshot);
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    private LinkedHashSet<String> currentStorageRedirectPackages() {
        return new LinkedHashSet<>(
                VfsRuntimePolicy.INSTANCE.getStorageRedirectPackages()
        );
    }

    private void remountAffectedStorageRedirectPackages(Set<String> previousPackages) {
        final var affectedPackages = new LinkedHashSet<String>();
        if (previousPackages != null) {
            affectedPackages.addAll(previousPackages);
        }
        affectedPackages.addAll(VfsRuntimePolicy.INSTANCE.getStorageRedirectPackages());
        if (!affectedPackages.isEmpty()) {
            mServer.vfsLayerController.remount(affectedPackages.toArray(new String[0]));
        }
    }

    /**
     * SharedPreferences.Editor.apply() may notify before data reaches disk.
     * The server process explicitly reloads the backing preferences before it
     * rebuilds policy snapshots.
     */
    private void reloadSharedPreferencesFromDisk() {
        try {
            final var sps = new SharedPreferences[]{
                    ServicePreferences.INSTANCE.getPreferences()
            };
            final var spImplCls = Class.forName("android.app.SharedPreferencesImpl");
            final var method = spImplCls.getDeclaredMethod("startLoadFromDisk");
            method.setAccessible(true);
            for (final var sp : sps) {
                method.invoke(sp);
            }
        } catch (final ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                       InvocationTargetException e) {
            Log.w(TAG, "Failed to reload SharedPreferences", e);
        }
    }
}
