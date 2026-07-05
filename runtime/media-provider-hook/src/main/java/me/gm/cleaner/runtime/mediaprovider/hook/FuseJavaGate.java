package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.Build;
import android.os.FileObserver;
import android.os.RemoteException;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
                if (mService.isReadOnly(path, uid)) {
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
                if (mService.isReadOnly(path, uid)) {
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
                if (mService.isReadOnly(oldPath, uid) || mService.isReadOnly(newPath, uid)) {
                    param.setResult(OsConstants.EPERM);
                }
            }
        });
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
                    if (accessType != DIRECTORY_ACCESS_FOR_READ && mService.isReadOnly(path, uid)) {
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
                    if (mService.isReadOnly(path, uid)) {
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
}
