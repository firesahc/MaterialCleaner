package me.gm.cleaner.runtime.server;

import static hidden.HiddenApiBridge.PackageInfo_isOverlayPackage;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import api.SystemService;
import hidden.HiddenApiBridge;
import me.gm.cleaner.runtime.server.BuildConfig;
import kotlin.io.path.PathsKt;
import me.gm.cleaner.browser.IRootFileService;
import me.gm.cleaner.browser.IRootWorkerService;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.model.BulkCursor;
import me.gm.cleaner.model.FileModel;
import me.gm.cleaner.model.FileSystemEvent;
import me.gm.cleaner.model.OrchestratedStatus;
import me.gm.cleaner.model.PackageStatus;
import me.gm.cleaner.model.ParceledListSlice;
import me.gm.cleaner.core.common.nio.RootFileService;
import me.gm.cleaner.core.common.nio.RootWorkerService;
import me.gm.cleaner.server.ICleanerService;
import me.gm.cleaner.server.IFileChangeObserver;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.runtime.server.observer.ActivityManagerLogsObserver;
import me.gm.cleaner.runtime.server.observer.FileSystemObserver;
import me.gm.cleaner.runtime.server.observer.ObserverManager;
import me.gm.cleaner.runtime.server.observer.StorageEventListenerDelegate;
import me.gm.cleaner.runtime.server.observer.StorageMountObserver;

public class CleanerService extends ICleanerService.Stub {
    private static final String TAG = "CleanerService";
    private final CleanerServer mServer;
    private final int mManagerAid;
    private final RemoteCallbackList<IFileChangeObserver> mFileChangeObservers = new RemoteCallbackList<>();
    private final StorageRedirectConfigController mStorageRedirectConfigController;

    public CleanerService(final CleanerServer service, final int uid) {
        mServer = service;
        mManagerAid = uid;
        mStorageRedirectConfigController = new StorageRedirectConfigController(service);
    }

    private void enforceManager(final Object func) {
        final var callingPid = Binder.getCallingPid();
        final var callingUid = Binder.getCallingUid();
        if (callingPid == Os.getpid() || RuntimeFileUtils.INSTANCE.toAppId(callingUid) == mManagerAid) {
            return;
        }
        throw new SecurityException(String.valueOf(func));
    }

    @Override
    public int getServerVersion() {
        if (RuntimeFileUtils.INSTANCE.toAppId(Binder.getCallingUid()) != mManagerAid) {
            return 0;
        }
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public int getServerException() {
        final var observers = ObserverManager.INSTANCE.getObservers();
        if (observers.isEmpty() && !MediaProviderHookGateway.pingBinder()) {
            return 4;
        }
        for (final var observer : observers) {
            if (observer instanceof final ActivityManagerLogsObserver activityManagerObserver) {
                if (activityManagerObserver.isLogcatShutdown()) {
                    return 2;
                }
                if (!activityManagerObserver.hasAmStart()) {
                    return 3;
                }
            } else if (observer instanceof StorageMountObserver) {
                if (!StorageEventListenerDelegate.isPrimaryStorageMounted) {
                    return 6;
                }
            }
        }
        return 0;
    }

    @Override
    public int getServerPid() {
        return Process.myPid();
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackages(final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getInstalledPackages" : 3);
        final var res = new ArrayList<>(SystemService.getInstalledPackagesFromAllUsersNoThrow(flags));
        res.removeIf(pi -> (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 &&
                PackageInfo_isOverlayPackage(pi));
        return new ParceledListSlice<>(res);
    }

    @Override
    public PackageInfo getPackageInfo(@Nonnull final String packageName, final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getPackageInfo" : 4);
        return SystemService.getPackageInfoNoThrow(packageName, flags, 0);
    }

    private int getOpMode(final ApplicationInfo appInfo, final String permissionName)
            throws RemoteException {
        final var opCode = HiddenApiBridge.permissionToOpCode(permissionName);
        // minSdk=26 (Android 8.0/O)，getUidOps 在所有支持的设备上始终可用
        final var opToMode = SystemService.getUidOps(appInfo.uid, new int[]{opCode});
        if (opToMode == null) {
            return AppOpsManager.MODE_ERRORED;
        }
        return HiddenApiBridge.mapOpToMode(opToMode).get(0).getSecond();
    }

    @Override
    public int getPackagePermission(final ApplicationInfo appInfo, final String permissionName,
                                    final boolean isRuntime) throws RemoteException {
        enforceManager(BuildConfig.DEBUG ? "getPackagePermission" : 5);
        var runtimeResult = PackageManager.PERMISSION_DENIED;
        if (isRuntime) {
            runtimeResult = SystemService.checkPermission(permissionName, appInfo.uid);
        }
        // 始终检查 AppOps 模式，即使 runtime 权限检查返回 DENIED
        // 因为 setPackagePermission 同时设置了 runtime 权限和 AppOps 模式
        // 在 Android 15+ 上，READ/WRITE_EXTERNAL_STORAGE 已废弃，
        // grantRuntimePermission 可能失败，但 AppOps 仍然有效
        switch (getOpMode(appInfo, permissionName)) {
            case AppOpsManager.MODE_ALLOWED:
                return PackageManager.PERMISSION_GRANTED;
            case AppOpsManager.MODE_IGNORED:
                return AppOpsManager.MODE_IGNORED;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_ERRORED:
                if (isRuntime) {
                    return runtimeResult;
                }
            default:
                return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public void setPackagePermission(final ApplicationInfo appInfo, final String permissionName,
                                     final boolean isRuntime, final int userId, final boolean grant)
            throws RemoteException {
        enforceManager(BuildConfig.DEBUG ? "setPackagePermission" : 6);
        if (isRuntime) {
            try {
                if (grant) {
                    SystemService.grantRuntimePermission(appInfo.packageName, permissionName, userId);
                } else {
                    SystemService.revokeRuntimePermission(appInfo.packageName, permissionName, userId);
                }
            } catch (NoSuchMethodError e) {
                // Android 15+ 上 IPermissionManager 接口方法签名可能已变更
                // 跳过 runtime 权限操作，仅设置 AppOps 模式
                Log.w(TAG, "setPackagePermission: runtime permission API not available, " +
                        "falling back to AppOps only", e);
            }
        }
        final var opCode = HiddenApiBridge.permissionToOpCode(permissionName);
        final var mode = grant ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED;
        SystemService.setUidMode(opCode, appInfo.uid, mode);
    }

    @Override
    public boolean isFuseBpfEnabled() {
        enforceManager(BuildConfig.DEBUG ? "isFuseBpfEnabled" : 7);
        return mServer.vfsLayerController.isFuseBpfEnabled();
    }

    @Override
    public PackageStatus getPackageStatus(@Nonnull final String packageName, final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getPackageStatus" : 8);
        return mServer.vfsLayerController.getPackageStatus(packageName, flags);
    }

    @Override
    public Map<String, PackageStatus> getSrPackagesStatus(final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getSrPackagesStatus" : 9);
        return mServer.vfsLayerController.getSrPackagesStatus(flags);
    }

    @Override
    public List<String> getMountedDirs() {
        enforceManager(BuildConfig.DEBUG ? "getMountedDirs" : 10);
        return mServer.vfsLayerController.getMountedDirs();
    }

    @Override
    public void notifyPreferencesChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifyPreferencesChanged" : 12);
        mStorageRedirectConfigController.onPreferencesChanged();
    }

    @Override
    public void notifySrChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifySrChanged" : 13);
        mStorageRedirectConfigController.onStorageRedirectChanged();
    }

    @Override
    public void notifyReadOnlyChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifyReadOnlyChanged" : 14);
        mStorageRedirectConfigController.onReadOnlyChanged();
    }

    @Override
    public void remount(@Nonnull final String[] packageNames) {
        enforceManager(BuildConfig.DEBUG ? "remount" : 15);
        mServer.vfsLayerController.remount(packageNames);
    }

    @Override
    public void registerFileChangeObserver(final IFileChangeObserver observer) {
        enforceManager(BuildConfig.DEBUG ? "registerFileChangeObserver" : 16);
        mFileChangeObservers.register(observer);
    }

    @Override
    public void unregisterFileChangeObserver(final IFileChangeObserver observer) {
        mFileChangeObservers.unregister(observer);
    }

    public synchronized void dispatchFileChange(
            final long timeMillis, final String packageName, final String path, final int flags,
            final boolean isAppSpecificStorage) {
        int i = mFileChangeObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final var observer = mFileChangeObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    observer.onEvent(timeMillis, packageName, path, flags, isAppSpecificStorage);
                } catch (final RemoteException ignored) {
                }
            }
        }
        mFileChangeObservers.finishBroadcast();
    }

    @Override
    public void pruneRecords(final long method, @Nullable final String[] packageNames,
                             final boolean isHideAppSpecificStorage, @Nullable final String queryText) {
        enforceManager(BuildConfig.DEBUG ? "pruneRecords" : 18);
        final var observer = ObserverManager.INSTANCE.getObserver(FileSystemObserver.class);
        if (observer != null) {
            observer.prune(method, packageNames, isHideAppSpecificStorage, queryText);
        }
    }

    @Override
    public BulkCursor<FileSystemEvent> queryAllRecords(final boolean isHideAppSpecificStorage,
                                                       @Nullable final String queryText) {
        enforceManager(BuildConfig.DEBUG ? "queryAllRecords" : 19);
        final var observer = ObserverManager.INSTANCE.getObserver(FileSystemObserver.class);
        if (observer == null) {
            return new BulkCursor<>(
                    new MatrixCursor(new String[]{}),
                    cursor -> new FileSystemEvent(
                            cursor.getLong(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getInt(4)
                    )
            );
        }
        return new BulkCursor<>(
                observer.queryAllRecords(isHideAppSpecificStorage, queryText),
                cursor -> new FileSystemEvent(
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getInt(4)
                )
        );
    }

    @Override
    public ParceledListSlice<FileSystemEvent> queryDistinctRecordsInclude(
            @Nonnull final String[] packageNames) {
        enforceManager(BuildConfig.DEBUG ? "queryDistinctRecordsInclude" : 20);
        final var observer = ObserverManager.INSTANCE.getObserver(FileSystemObserver.class);
        if (observer == null) {
            return new ParceledListSlice<>(Collections.emptyList());
        }
        final var cursor = observer.queryDistinctRecordsInclude(packageNames);
        final var res = new ArrayList<FileSystemEvent>(cursor.getCount());
        while (cursor.moveToNext()) {
            res.add(new FileSystemEvent(
                    0,
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2)
            ));
        }
        cursor.close();
        return new ParceledListSlice<>(res);
    }

    @Override
    public int countRecordsInclude(@Nonnull final String[] packageNames) {
        enforceManager(BuildConfig.DEBUG ? "countRecordsInclude" : 21);
        final var observer = ObserverManager.INSTANCE.getObserver(FileSystemObserver.class);
        if (observer == null) {
            return 0;
        }
        return observer.countRecordsInclude(packageNames);
    }

    @Override
    public int databaseCount() {
        enforceManager(BuildConfig.DEBUG ? "databaseSize" : 22);
        final var observer = ObserverManager.INSTANCE.getObserver(FileSystemObserver.class);
        if (observer == null) {
            return 0;
        }
        return observer.databaseCount();
    }

    @Override
    public IRootFileService newRootFileService() {
        enforceManager(BuildConfig.DEBUG ? "newRootFileService" : 23);
        return new RootFileService();
    }

    @Override
    public IRootWorkerService newRootWorkerService() {
        enforceManager(BuildConfig.DEBUG ? "newRootWorkerService" : 24);
        return new RootWorkerService();
    }

    @Override
    public FileModel createFileModel(@Nonnull final String path) {
        enforceManager(BuildConfig.DEBUG ? "createFileModel" : 25);
        return new FileModel(Paths.get(path));
    }

    @Override
    public ParceledListSlice<FileModel> listFiles(@Nonnull final String path) {
        enforceManager(BuildConfig.DEBUG ? "listFiles" : 26);
        try {
            return new ParceledListSlice<>(
                    PathsKt.listDirectoryEntries(Paths.get(path), "*").stream()
                            .map(FileModel::new)
                            .collect(Collectors.toList())
            );
        } catch (final Exception e) {
            return new ParceledListSlice<>(Collections.emptyList());
        }
    }

    @Override
    public boolean move(String from, String to) {
        enforceManager(BuildConfig.DEBUG ? "move" : 27);
        final var srcPath = Paths.get(from);
        final var dstPath = Paths.get(to);
        return RuntimeFileUtils.INSTANCE.move(srcPath, dstPath);
    }

    @Override
    public boolean copy(final String from, final String to) {
        enforceManager(BuildConfig.DEBUG ? "copy" : 28);
        final var srcPath = Paths.get(from);
        final var dstPath = Paths.get(to);
        return RuntimeFileUtils.INSTANCE.copy(srcPath, dstPath);
    }

    @Override
    public void exit() {
        enforceManager(BuildConfig.DEBUG ? "exit" : 29);
        mServer.onDestroy();
        System.exit(0);
    }

    @Override
    public OrchestratedStatus getOrchestratedStatus() {
        enforceManager(BuildConfig.DEBUG ? "getOrchestratedStatus" : 30);
        return mServer.layerOrchestrator.collectStatusForIpc();
    }

    @Override
    public ParcelFileDescriptor openDiagnosticsArchive() throws RemoteException {
        enforceManager(BuildConfig.DEBUG ? "openDiagnosticsArchive" : 31);
        try {
            return DiagnosticArchive.INSTANCE.open(mServer);
        } catch (final Exception e) {
            final var remoteException = new RemoteException("openDiagnosticsArchive failed: " +
                    e.getMessage());
            remoteException.initCause(e);
            throw remoteException;
        }
    }
}
