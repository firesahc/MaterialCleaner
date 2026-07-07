package me.gm.cleaner.runtime.server;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;

import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.runtime.server.observer.PackageInfoMapper;

/**
 * Handles storage redirect configuration change commands.
 *
 * CleanerService remains the Binder facade; this controller owns the side
 * effects needed after config writes: preference reload, snapshot publishing,
 * Hook refresh and VFS remount.
 */
public class StorageRedirectConfigController {
    private static final String TAG = "StorageRedirectConfigController";

    private final CleanerServer mServer;

    public StorageRedirectConfigController(final CleanerServer server) {
        mServer = server;
    }

    public void onPreferencesChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        reloadSharedPreferencesFromDisk();
        SnapshotPublisher.INSTANCE.publishRedirectPolicy();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
        remountAffectedStorageRedirectPackages(previousPackages);
    }

    public void onStorageRedirectChanged() {
        final var previousPackages = currentStorageRedirectPackages();
        ServicePreferences.INSTANCE.invalidateSrCache();
        PackageInfoMapper.invalidate();
        SnapshotPublisher.INSTANCE.publishStorageRedirectPolicySet();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
        remountAffectedStorageRedirectPackages(previousPackages);
    }

    public void onReadOnlyChanged() {
        ServicePreferences.INSTANCE.invalidateReadOnlyCache();
        SnapshotPublisher.INSTANCE.publishReadOnly();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    private LinkedHashSet<String> currentStorageRedirectPackages() {
        return new LinkedHashSet<>(
                VfsRuntimeConfigStore.INSTANCE.getStorageRedirectPackages()
        );
    }

    private void remountAffectedStorageRedirectPackages(Set<String> previousPackages) {
        final var affectedPackages = new LinkedHashSet<String>();
        if (previousPackages != null) {
            affectedPackages.addAll(previousPackages);
        }
        affectedPackages.addAll(VfsRuntimeConfigStore.INSTANCE.getStorageRedirectPackages());
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
