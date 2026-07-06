package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.Build;
import android.os.FileObserver;
import android.os.RemoteException;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import org.json.JSONObject;

/**
 * FUSE Java 入口门。
 *
 * 负责注册 MediaProvider Java 层 FUSE 入口的 Xposed Hook：
 * - insertFileIfNecessaryForFuse / deleteFileForFuse / renameForFuse
 * - isDirAccessAllowedForFuse / isDirectoryCreationOrDeletionAllowedForFuse
 * - isUidAllowedAccessToDataOrObbPathForFuse
 *
 * 同时负责文件事件分发（仅 DataBus 通道，Binder 通道已移除）。
 *
 * 不负责：xhook 安装、containsMount、fuse_bpf_install、native mountPoint。
 */
public class FuseJavaGate {
    private static final int DIR = 0x40000000;
    static final int DIRECTORY_ACCESS_FOR_READ = 1;
    static final int DIRECTORY_ACCESS_FOR_WRITE = 2;
    static final int DIRECTORY_ACCESS_FOR_CREATE = 3;
    static final int DIRECTORY_ACCESS_FOR_DELETE = 4;

    private final MediaProviderHook mHook;
    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;
    private final Class<?> mMediaProviderClass;

    public FuseJavaGate(MediaProviderHook hook, MediaProviderHooksService service,
                        ClassLoader classLoader, Class<?> mediaProviderClass) {
        mHook = hook;
        mService = service;
        mClassLoader = classLoader;
        mMediaProviderClass = mediaProviderClass;
        initFuseHooks();
    }

    // ── FUSE Hook 安装 ──

    private void initFuseHooks() {
        Log.i("MC_REDIRECT", "[FuseJavaGate] initFuseHooks() - installing FUSE hooks...");

        hookInsertFileIfNecessaryForFuse();
        hookDeleteFileForFuse();
        hookRenameForFuse();
        hookOpenWithFuse();
        hookFileLookupForFuse();
        hookFileOpenForFuse();
        hookDirAccessCheck();
        hookUidAllowedAccess();
    }

    private void hookInsertFileIfNecessaryForFuse() {
        final var method = XposedHelpers.findMethodExact(
                mMediaProviderClass, "insertFileIfNecessaryForFuse", String.class, int.class);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                final var path = (String) param.args[0];
                final var uid = (int) param.args[1];
                final var packageName = getCallingPackageName(param.thisObject, uid);
                dispatchFileSystemEvent(packageName, path, FileObserver.CREATE);
                final var mountedPath = redirectFusePath(param, 0, uid, "insertFileIfNecessaryForFuse");
                if (mService.isReadOnly(mountedPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            }
        });
    }

    private void hookDeleteFileForFuse() {
        Method method;
        try {
            method = XposedHelpers.findMethodExact(mMediaProviderClass,
                    "deleteFileForFuse", String.class, int.class);
        } catch (final NoSuchMethodError e) {
            // Hyper OS (Android 14)
            method = XposedHelpers.findMethodExact(mMediaProviderClass,
                    "deleteFileForFuse", String.class, int.class, int.class);
        }
        final var finalMethod = method;
        XposedBridge.hookMethod(finalMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                final var path = (String) param.args[0];
                final var uid = (int) param.args[1];
                final var packageName = getCallingPackageName(param.thisObject, uid);
                dispatchFileSystemEvent(packageName, path, FileObserver.DELETE);
                final var mountedPath = redirectFusePath(param, 0, uid, "deleteFileForFuse");
                if (mService.isReadOnly(mountedPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            }
        });
    }

    private void hookRenameForFuse() {
        final var method = XposedHelpers.findMethodExact(
                mMediaProviderClass, "renameForFuse", String.class, String.class, int.class);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                final var oldPath = (String) param.args[0];
                final var newPath = (String) param.args[1];
                final var uid = (int) param.args[2];
                final var packageName = getCallingPackageName(param.thisObject, uid);
                var isDir = 0;
                if (!new File(oldPath).isFile()) {
                    isDir = DIR;
                }
                dispatchFileSystemEvent(packageName, oldPath, FileObserver.MOVED_FROM | isDir);
                dispatchFileSystemEvent(packageName, newPath, FileObserver.MOVED_TO | isDir);
                final var mountedOldPath = redirectFusePath(param, 0, uid, "renameForFuse.oldPath");
                final var mountedNewPath = redirectFusePath(param, 1, uid, "renameForFuse.newPath");
                if (mService.isReadOnly(mountedOldPath, uid) || mService.isReadOnly(mountedNewPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            }
        });
    }

    private void hookOpenWithFuse() {
        var hooked = false;
        for (final var method : mMediaProviderClass.getDeclaredMethods()) {
            if (!"openWithFuse".equals(method.getName()) || !hasStringPathArgument(method)) {
                continue;
            }
            final var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length < 2 || parameterTypes[1] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    final var uid = (int) param.args[1];
                    redirectFusePath(param, 0, uid, "openWithFuse");
                }
            });
            hooked = true;
            Log.i("MC_REDIRECT", "[FuseJavaGate] hooked openWithFuse signature=" +
                    Arrays.toString(parameterTypes));
        }
        if (!hooked) {
            Log.w("MC_REDIRECT", "[FuseJavaGate] openWithFuse not found");
        }
    }

    private void hookFileLookupForFuse() {
        hookFusePathEntrypoint("onFileLookupForFuse");
    }

    private void hookFileOpenForFuse() {
        hookFusePathEntrypoint("onFileOpenForFuse");
    }

    private void hookFusePathEntrypoint(final String methodName) {
        var hooked = false;
        for (final var method : mMediaProviderClass.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || !hasStringPathArgument(method)) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    final var originalPath = (String) param.args[0];
                    final var uid = findCallingUid(methodName, param.args);
                    if (uid < 0) {
                        Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName +
                                " cannot find uid, signature=" +
                                Arrays.toString(method.getParameterTypes()));
                        return;
                    }
                    final var mountedPath = redirectFusePath(param, 0, uid, methodName);
                    redirectMatchingStringPathArgs(param, originalPath, mountedPath);
                }
            });
            hooked = true;
            Log.i("MC_REDIRECT", "[FuseJavaGate] hooked " + methodName +
                    " signature=" + Arrays.toString(method.getParameterTypes()));
        }
        if (!hooked) {
            Log.w("MC_REDIRECT", "[FuseJavaGate] " + methodName + " not found");
        }
    }

    private boolean hasStringPathArgument(final Method method) {
        final var parameterTypes = method.getParameterTypes();
        return parameterTypes.length > 0 && parameterTypes[0] == String.class;
    }

    private void redirectMatchingStringPathArgs(final XC_MethodHook.MethodHookParam param,
                                                final String originalPath,
                                                final String mountedPath) {
        if (originalPath == null || mountedPath == null || originalPath.equals(mountedPath)) {
            return;
        }
        for (int i = 1; i < param.args.length; i++) {
            if (originalPath.equals(param.args[i])) {
                param.args[i] = mountedPath;
            }
        }
    }

    private int findCallingUid(final String methodName, final Object[] args) {
        if ("onFileOpenForFuse".equals(methodName) && args.length > 2 &&
                args[2] instanceof Integer) {
            return (int) args[2];
        }
        if ("onFileLookupForFuse".equals(methodName) && args.length > 1 &&
                args[1] instanceof Integer) {
            return (int) args[1];
        }
        return findCallingUid(args);
    }

    private int findCallingUid(final Object[] args) {
        var firstInt = -1;
        for (int i = 1; i < args.length; i++) {
            if (!(args[i] instanceof Integer)) {
                continue;
            }
            final var value = (int) args[i];
            if (firstInt < 0) {
                firstInt = value;
            }
            if (value >= 10000) {
                return value;
            }
        }
        return firstInt;
    }

    private void hookDirAccessCheck() {
        try {
            // Android 12+
            final var method = XposedHelpers.findMethodExact(mMediaProviderClass,
                    "isDirAccessAllowedForFuse", String.class, int.class, int.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    final var path = (String) param.args[0];
                    final var uid = (int) param.args[1];
                    final var accessType = (int) param.args[2];
                    if (accessType == DIRECTORY_ACCESS_FOR_CREATE ||
                            accessType == DIRECTORY_ACCESS_FOR_DELETE) {
                        final var packageName = getCallingPackageName(param.thisObject, uid);
                        dispatchFileSystemEvent(packageName, path,
                                (accessType == DIRECTORY_ACCESS_FOR_CREATE ?
                                        FileObserver.CREATE : FileObserver.DELETE) | DIR);
                    }
                    final var mountedPath = redirectFusePath(param, 0, uid, "isDirAccessAllowedForFuse");
                    if (accessType != DIRECTORY_ACCESS_FOR_READ && mService.isReadOnly(mountedPath, uid)) {
                        param.setResult(OsConstants.EPERM);
                    }
                }
            });
        } catch (final NoSuchMethodError e) {
            // Android 11 fallback
            final var method = XposedHelpers.findMethodExact(mMediaProviderClass,
                    "isDirectoryCreationOrDeletionAllowedForFuse",
                    String.class, int.class, boolean.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    final var path = (String) param.args[0];
                    final var uid = (int) param.args[1];
                    final var forCreate = (boolean) param.args[2];
                    final var packageName = getCallingPackageName(param.thisObject, uid);
                    dispatchFileSystemEvent(packageName, path,
                            (forCreate ? FileObserver.CREATE : FileObserver.DELETE) | DIR);
                    final var mountedPath = redirectFusePath(param, 0, uid,
                            "isDirectoryCreationOrDeletionAllowedForFuse");
                    if (mService.isReadOnly(mountedPath, uid)) {
                        param.setResult(OsConstants.EPERM);
                    }
                }
            });
        }
    }

    private void hookUidAllowedAccess() {
        final var method = XposedHelpers.findMethodExact(mMediaProviderClass,
                "isUidAllowedAccessToDataOrObbPathForFuse", int.class, String.class);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                mService.whileAlive(service -> {
                    try {
                        final var uid = (int) param.args[0];
                        final var path = (String) param.args[1];
                        final int i;
                        final var gcPackages = new ArrayList<String>();
                        final var gcIndices = new ArrayList<Integer>();
                        synchronized (mHook.mQueryRecord) {
                            final var size = mHook.mQueryRecord.size();
                            if (size == 0) return;
                            i = mHook.mQueryRecord.indexOfKey(uid);
                            if (i < 0) {
                                if (size > 1) {
                                    for (var j = 0; j < size; j++) {
                                        final var key = mHook.mQueryRecord.keyAt(j);
                                        final var value = mHook.mQueryRecord.valueAt(j);
                                        if (TimeUnit.MILLISECONDS.toSeconds(
                                                System.currentTimeMillis() - value) > 5) {
                                            gcPackages.add(getCallingPackageName(param.thisObject, key));
                                            gcIndices.add(j);
                                        }
                                    }
                                    final var iterator = gcIndices.listIterator(gcIndices.size());
                                    while (iterator.hasPrevious()) {
                                        mHook.mQueryRecord.removeAt(iterator.previous());
                                    }
                                }
                            }
                        }
                        for (int idx = 0; idx < gcPackages.size(); idx++) {
                            service.onReleaseQueriedPaths(gcPackages.get(idx));
                        }
                        if (i < 0) return;
                        final var packageName = getCallingPackageName(param.thisObject, uid);
                        if (service.onMaybeAccessQueriedPaths(packageName, path)) {
                            synchronized (mHook.mQueryRecord) {
                                mHook.mQueryRecord.removeAt(i);
                            }
                            service.onReleaseQueriedPaths(packageName);
                        }
                    } catch (RemoteException e) {
                        Log.e("FuseJavaGate", "error", e);
                    }
                });
            }
        });
    }

    // ── 文件事件分发 ──

    void dispatchFileSystemEvent(final String packageName, final String path, final int flags) {
        Log.d("MC_REDIRECT", "[FuseJavaGate] dispatchFileSystemEvent pkg=" + packageName + " path=" + path + " flags=" + flags);
        // 通过 DataBus 事件队列分发（异步，不影响主流程）。
        // 仅使用 DataBus 路径——不再通过 Binder 同步调用 onFileSystemEvent。
        // FileSystemEventConsumer 通过定时轮询 DataBus 消费事件。
        // 这消除了文件事件热路径对 Server Binder 的强依赖：
        // - Server 不可用时事件不丢失（积压在 DataBus 中）
        // - Server 恢复后通过消费者游标续消费
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", packageName);
            event.put("path", path);
            event.put("flags", flags);
            event.put("sourceLayer", "FUSE_JAVA_GATE");
            DataBus.INSTANCE.writeEvent(DataBus.EVENT_FILESYSTEM, event.toString());
            // 数据面契约 6.4: 写事件后发 signal，消除消费者端 2s 轮询延迟
            DataBus.INSTANCE.signal(DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED);
        } catch (Exception e) {
            Log.e("MC_REDIRECT", "[FuseJavaGate] DataBus write failed", e);
        }
    }

    // ── 工具方法 ──

    private String getCallingPackageName(final Object mp, final int uid) {
        final var localCallingIdentity = XposedHelpers.callMethod(
                mp, "getCachedCallingIdentityForFuse", uid);
        return (String) XposedHelpers.callMethod(localCallingIdentity, "getPackageName");
    }

    private String redirectFusePath(final XC_MethodHook.MethodHookParam param, final int pathIndex,
                                    final int uid, final String operation) {
        final var path = (String) param.args[pathIndex];
        if (path == null) {
            return null;
        }
        final String packageName;
        try {
            packageName = getCallingPackageName(param.thisObject, uid);
        } catch (Throwable t) {
            Log.w("MC_REDIRECT", "[FuseJavaGate] " + operation +
                    " cannot resolve package for uid=" + uid + ", path=" + path, t);
            return path;
        }
        final var mountedPath = HookPolicyCache.INSTANCE.getMountedPath(packageName, path);
        if (mountedPath == null || path.equals(mountedPath)) {
            return path;
        }
        param.args[pathIndex] = mountedPath;
        Log.i("MC_REDIRECT", "[FuseJavaGate] " + operation + " redirected: pkg=" +
                packageName + " " + path + " -> " + mountedPath);
        return mountedPath;
    }
}
