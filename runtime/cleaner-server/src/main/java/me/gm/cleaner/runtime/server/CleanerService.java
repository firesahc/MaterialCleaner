package me.gm.cleaner.runtime.server;

import static hidden.HiddenApiBridge.PackageInfo_isOverlayPackage;
import static hidden.HiddenApiBridge.UserHandle_isIsolated;
import static me.gm.cleaner.model.PackageStatus.GET_FROM_ALL_PROCESS;
import static me.gm.cleaner.model.PackageStatus.GET_FROM_RECORDS;

import android.annotation.SuppressLint;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AppOpsManager;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import com.google.common.collect.ArrayListMultimap;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import api.SystemService;
import hidden.HiddenApiBridge;
import me.gm.cleaner.runtime.server.BuildConfig;
import kotlin.collections.ArraysKt;
import kotlin.io.path.PathsKt;
import me.gm.cleaner.browser.IRootFileService;
import me.gm.cleaner.browser.IRootWorkerService;
import me.gm.cleaner.core.common.RuntimeFileUtils;
import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.model.BulkCursor;
import me.gm.cleaner.model.FileModel;
import me.gm.cleaner.model.FileSystemEvent;
import me.gm.cleaner.model.PackageStatus;
import me.gm.cleaner.model.ParceledListSlice;
import me.gm.cleaner.core.common.nio.RootFileService;
import me.gm.cleaner.core.common.nio.RootWorkerService;
import me.gm.cleaner.server.ICleanerService;
import me.gm.cleaner.server.IFileChangeObserver;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.runtime.server.observer.ActivityManagerLogsObserver;
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver;
import me.gm.cleaner.runtime.server.observer.FileSystemObserver;
import me.gm.cleaner.runtime.server.observer.ObserverManager;
import me.gm.cleaner.runtime.server.observer.PackageInfoMapper;
import me.gm.cleaner.runtime.server.observer.StorageEventListenerDelegate;
import me.gm.cleaner.runtime.server.observer.StorageMountObserver;

public class CleanerService extends ICleanerService.Stub {
    private static final String TAG = "CleanerService";
    private final CleanerServer mServer;
    private final int mManagerAid;
    private final RemoteCallbackList<IFileChangeObserver> mFileChangeObservers = new RemoteCallbackList<>();

    public CleanerService(final CleanerServer service, final int uid) {
        mServer = service;
        mManagerAid = uid;
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
                Log.w("CleanerService", "setPackagePermission: runtime permission API not available, " +
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
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        return observer != null && observer.isFuseBpfEnabled();
    }

    @Override
    public PackageStatus getPackageStatus(@Nonnull final String packageName, final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getPackageStatus" : 8);
        final var pids = new ArrayList<Integer>();
        final var pidFlags = new ArrayList<Integer>();
        final var userIds = new ArrayList<Integer>();

        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer == null) {
            return new PackageStatus();
        }
        final var startUpAwarePids = observer.getStartUpAwarePids(packageName);
        final var mountFailedPids = observer.getMountFailedPids();
        final var mkdir = observer.getMountedPackages().contains(packageName);
        final List<RunningAppProcessInfo> processes;
        switch (flags) {
            case GET_FROM_ALL_PROCESS:
                processes = SystemService.getRunningAppProcessesNoThrow();
                break;
            case GET_FROM_RECORDS:
                processes = SystemService.getRunningAppProcessesNoThrow().stream()
                        .filter(it -> startUpAwarePids.contains(it.pid))
                        .collect(Collectors.toList());
                break;
            default:
                processes = Collections.emptyList();
                break;
        }
        processes.stream()
                .filter(procInfo -> !UserHandle_isIsolated(RuntimeFileUtils.INSTANCE.read_uid(procInfo.pid)))
                .sorted(Comparator.comparingInt(value -> value.pid))
                .forEach(procInfo ->
                        Arrays.stream(procInfo.pkgList).filter(packageName::equals).forEach(it -> {
                            pids.add(procInfo.pid);
                            final var userId = RuntimeFileUtils.INSTANCE.toUserId(procInfo.uid);
                            final var targets = ServicePreferences.INSTANCE
                                    .getPackageSr(packageName, userId).getSecond();
                            final var mountedIndices = RuntimeFileUtils.INSTANCE.check_mounts(
                                    procInfo.pid, targets.stream().toArray(String[]::new));
                            var pidFlag = 0;
                            if (mountedIndices == null) {
                                pidFlag |= PackageStatus.PID_FLAG_UNKNOWN;
                            } else if (Arrays.stream(mountedIndices).anyMatch(i -> i < 0)) {
                                if (ArraysKt.contains(mountedIndices, -1)) {
                                    pidFlag |= PackageStatus.PID_FLAG_DELETED;
                                }
                                if (ArraysKt.contains(mountedIndices, -2)) {
                                    pidFlag |= PackageStatus.PID_FLAG_OVERRIDE;
                                }
                            } else if (targets.size() == mountedIndices.length) {
                                pidFlag |= PackageStatus.PID_FLAG_MOUNTED;
                            }
                            if (startUpAwarePids.contains(procInfo.pid)) {
                                pidFlag |= PackageStatus.PID_FLAG_STARTUP_AWARE;
                            }
                            if (mountFailedPids.contains(procInfo.pid)) {
                                pidFlag |= PackageStatus.PID_FLAG_MOUNT_FAILED;
                            }
                            if (!mkdir) {
                                pidFlag |= PackageStatus.PID_FLAG_MKDIR_FAILED;
                            }
                            pidFlags.add(pidFlag);
                            userIds.add(userId);
                        }));

        final var packageStatus = new PackageStatus();
        packageStatus.pids = pids.stream().mapToInt(value -> value).toArray();
        packageStatus.pidFlags = pidFlags.stream().mapToInt(value -> value).toArray();
        packageStatus.userIds = userIds.stream().mapToInt(value -> value).toArray();
        return packageStatus;
    }

    @Override
    public Map<String, PackageStatus> getSrPackagesStatus(final int flags) {
        enforceManager(BuildConfig.DEBUG ? "getSrPackagesStatus" : 9);
        final ArrayListMultimap<String, Integer> pids = ArrayListMultimap.create();
        final ArrayListMultimap<String, Integer> pidFlags = ArrayListMultimap.create();
        final ArrayListMultimap<String, Integer> userIds = ArrayListMultimap.create();

        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer == null) {
            return Collections.emptyMap();
        }
        final var startUpAwarePids = observer.getAllStartUpAwarePids();
        final var mountFailedPids = observer.getMountFailedPids();
        final var mountedPackages = observer.getMountedPackages();
        final List<RunningAppProcessInfo> processes;
        switch (flags) {
            case GET_FROM_ALL_PROCESS:
                processes = SystemService.getRunningAppProcessesNoThrow();
                break;
            case GET_FROM_RECORDS:
                processes = SystemService.getRunningAppProcessesNoThrow().stream()
                        .filter(it -> startUpAwarePids.contains(it.pid))
                        .collect(Collectors.toList());
                break;
            default:
                processes = Collections.emptyList();
                break;
        }
        final var srPackages = ServicePreferences.INSTANCE.getSrPackages();
        processes.stream()
                .filter(procInfo -> !UserHandle_isIsolated(RuntimeFileUtils.INSTANCE.read_uid(procInfo.pid)))
                .sorted(Comparator.comparingInt(value -> value.pid))
                .forEach(procInfo ->
                        Arrays.stream(procInfo.pkgList).filter(srPackages::contains).forEach(packageName -> {
                            pids.put(packageName, procInfo.pid);
                            final var userId = RuntimeFileUtils.INSTANCE.toUserId(procInfo.uid);
                            final var targets = ServicePreferences.INSTANCE
                                    .getPackageSr(packageName, userId).getSecond();
                            final var mountedIndices = RuntimeFileUtils.INSTANCE.check_mounts(
                                    procInfo.pid, targets.stream().toArray(String[]::new));
                            var pidFlag = 0;
                            if (mountedIndices == null) {
                                pidFlag |= PackageStatus.PID_FLAG_UNKNOWN;
                            } else if (Arrays.stream(mountedIndices).anyMatch(i -> i < 0)) {
                                if (ArraysKt.contains(mountedIndices, -1)) {
                                    pidFlag |= PackageStatus.PID_FLAG_DELETED;
                                }
                                if (ArraysKt.contains(mountedIndices, -2)) {
                                    pidFlag |= PackageStatus.PID_FLAG_OVERRIDE;
                                }
                            } else if (targets.size() == mountedIndices.length) {
                                pidFlag |= PackageStatus.PID_FLAG_MOUNTED;
                            }
                            if (startUpAwarePids.contains(procInfo.pid)) {
                                pidFlag |= PackageStatus.PID_FLAG_STARTUP_AWARE;
                            }
                            if (mountFailedPids.contains(procInfo.pid)) {
                                pidFlag |= PackageStatus.PID_FLAG_MOUNT_FAILED;
                            }
                            if (!mountedPackages.contains(packageName)) {
                                pidFlag |= PackageStatus.PID_FLAG_MKDIR_FAILED;
                            }
                            pidFlags.put(packageName, pidFlag);
                            userIds.put(packageName, userId);
                        }));

        final var srPackageStatus = new HashMap<String, PackageStatus>();
        pids.keySet().forEach(packageName -> {
            final var packageStatus = new PackageStatus();
            packageStatus.pids = pids.get(packageName).stream().mapToInt(value -> value).toArray();
            packageStatus.pidFlags = pidFlags.get(packageName).stream().mapToInt(value -> value).toArray();
            packageStatus.userIds = userIds.get(packageName).stream().mapToInt(value -> value).toArray();
            srPackageStatus.put(packageName, packageStatus);
        });
        return srPackageStatus;
    }

    @Override
    public List<String> getMountedDirs() {
        enforceManager(BuildConfig.DEBUG ? "getMountedDirs" : 10);
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer == null) {
            return Collections.emptyList();
        }
        return observer.getMountedDirs();
    }

    /**
     * Cautious: {@link SharedPreferences.Editor} uses {@link SharedPreferences.Editor#apply()}
     * by default, which may cause this reload method called before the preferences are written to
     * the disk and the values before writing is read.
     */
    @SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
    @Override
    public void notifyPreferencesChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifyPreferencesChanged" : 12);
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
        // 发布偏好变更快照到 DataBus（偏好变更可能影响策略）
        // DataBus 是唯一的配置分发通道；旧 Binder fallback 路径已移除。
        SnapshotPublisher.INSTANCE.publishRedirectPolicy();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    @Override
    public void notifySrChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifySrChanged" : 13);
        ServicePreferences.INSTANCE.invalidateSrCache();
        PackageInfoMapper.invalidate();
        // 发布规则变更快照到 DataBus，控制面只触发 Hook 侧刷新。
        // DataBus 是唯一的配置分发通道；旧 Binder fallback 路径已移除。
        SnapshotPublisher.INSTANCE.publishRedirectPolicy();
        SnapshotPublisher.INSTANCE.publishConfiguredMountPoints();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    @Override
    public void notifyReadOnlyChanged() {
        enforceManager(BuildConfig.DEBUG ? "notifyReadOnlyChanged" : 14);
        ServicePreferences.INSTANCE.invalidateReadOnlyCache();
        // 发布只读变更快照到 DataBus
        // DataBus 是唯一的配置分发通道；旧 Binder fallback 路径已移除。
        SnapshotPublisher.INSTANCE.publishReadOnly();
        MediaProviderHookGateway.refreshPolicyFromDataBus();
    }

    @Override
    public void remount(@Nonnull final String[] packageNames) {
        enforceManager(BuildConfig.DEBUG ? "remount" : 15);
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer != null) {
            observer.remountForPackages(packageNames);
        }
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
    public String getOrchestratedStatusJson() {
        enforceManager(BuildConfig.DEBUG ? "getOrchestratedStatusJson" : 30);
        return mServer.layerOrchestrator.collectStatusJson();
    }
}
