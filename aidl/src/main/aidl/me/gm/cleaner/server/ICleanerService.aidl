package me.gm.cleaner.server;

import me.gm.cleaner.browser.IRootFileService;
import me.gm.cleaner.browser.IRootWorkerService;
import me.gm.cleaner.model.BulkCursor;
import me.gm.cleaner.model.FileModel;
import me.gm.cleaner.model.FileSystemEvent;
import me.gm.cleaner.model.PackageStatus;
import me.gm.cleaner.model.ParceledListSlice;
import me.gm.cleaner.server.IFileChangeObserver;

interface ICleanerService {

    // server info
    int getServerVersion() = 0;

    int getServerException() = 1;

    int getServerPid() = 2;

    // system info
    ParceledListSlice<PackageInfo> getInstalledPackages(int flags) = 3;

    PackageInfo getPackageInfo(String packageName, int flags) = 4;

    int getPackagePermission(in ApplicationInfo appInfo, String permissionName, boolean isRuntime) = 5;

    void setPackagePermission(in ApplicationInfo appInfo, String permissionName, boolean isRuntime, int userId, boolean grant) = 6;

    boolean isFuseBpfEnabled() = 7;

    // mount
    PackageStatus getPackageStatus(String packageName, int flags) = 8;

    Map<String, PackageStatus> getSrPackagesStatus(int flags) = 9;

    List<String> getMountedDirs() = 10;

    void notifyPreferencesChanged() = 12;

    void notifySrChanged() = 13;

    void notifyReadOnlyChanged() = 14;

    void remount(in String[] packageNames) = 15;

    // filesystem record
    void registerFileChangeObserver(in IFileChangeObserver observer) = 16;

    void unregisterFileChangeObserver(in IFileChangeObserver observer) = 17;

    void pruneRecords(long method, in String[] packageNames, boolean isHideAppSpecificStorage, String queryText) = 18;

    BulkCursor<FileSystemEvent> queryAllRecords(boolean isHideAppSpecificStorage, String queryText) = 19;

    ParceledListSlice<FileSystemEvent> queryDistinctRecordsInclude(in String[] packageNames) = 20;

    int countRecordsInclude(in String[] packageNames) = 21;

    int databaseCount() = 22;

    // file service
    IRootFileService newRootFileService() = 23;

    IRootWorkerService newRootWorkerService() = 24;

    // TODO: refactor with the function above
    FileModel createFileModel(String path) = 25;

    ParceledListSlice<FileModel> listFiles(String path) = 26;

    boolean move(String from, String to) = 27;

    boolean copy(String from, String to) = 28;

    void exit() = 29;
}
