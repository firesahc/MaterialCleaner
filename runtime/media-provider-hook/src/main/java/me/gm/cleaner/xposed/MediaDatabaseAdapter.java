package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentProvider;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 媒体数据库适配器。
 *
 * 负责注册 insertFile、scan_file、query 的 Xposed Hook，
 * 确保 MediaProvider 的数据库写入、扫描路径与重定向策略一致。
 *
 * 不负责：FUSE 文件事件、只读拦截、native mountPoint、libfuse_jni.so。
 */
public class MediaDatabaseAdapter {
    private static final String SCAN_FILE_CALL = "scan_file";

    private final MediaProviderHook mHook;
    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;
    private final Class<?> mMediaProviderClass;

    public MediaDatabaseAdapter(MediaProviderHook hook, MediaProviderHooksService service,
                                ClassLoader classLoader, Class<?> mediaProviderClass) {
        mHook = hook;
        mService = service;
        mClassLoader = classLoader;
        mMediaProviderClass = mediaProviderClass;

        initScanHook();
        initInsertHook();
    }

    /**
     * 初始化 query Hook（需 FUSE 可用时才调用）。
     */
    public void initQueryHookIfFuseAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Method queryMethod;
            try {
                queryMethod = XposedHelpers.findMethodExact(mMediaProviderClass, "query",
                        Uri.class, String[].class, Bundle.class, CancellationSignal.class, boolean.class);
            } catch (final NoSuchMethodError e) {
                queryMethod = XposedHelpers.findMethodExact(mMediaProviderClass, "query",
                        Uri.class, String[].class, Bundle.class, CancellationSignal.class);
            }
            XposedBridge.hookMethod(queryMethod, new QueryHooker(mHook, mService, mClassLoader));
        }
    }

    // ── scan_file Hook ──

    private void initScanHook() {
        final var callMethod = XposedHelpers.findMethodExact(
                mMediaProviderClass, "call", String.class, String.class, Bundle.class);
        XposedBridge.hookMethod(callMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final XC_MethodHook.MethodHookParam param) throws Throwable {
                final var method = (String) param.args[0];
                final var arg = (String) param.args[1];
                final var extras = (Bundle) param.args[2];
                if (!SCAN_FILE_CALL.equals(method)) {
                    return;
                }
                final File file;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (arg == null) {
                        return;
                    }
                    file = new File(arg);
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    final var uri = (Uri) extras.getParcelable(Intent.EXTRA_STREAM);
                    if (uri == null) {
                        return;
                    }
                    file = new File(uri.getPath());
                } else {
                    return;
                }
                final var callingPackage = ((ContentProvider) param.thisObject).getCallingPackage();
                mService.whileAlive(service -> {
                    try {
                        final var mountedPath = service.getMountedPath(
                                callingPackage, file.getPath(), MediaProviderHook.TYPE_CONNECTION);
                        if (mountedPath != null && !file.getPath().equals(mountedPath)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                param.args[1] = mountedPath;
                            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                extras.putParcelable(Intent.EXTRA_STREAM, Uri.parse(mountedPath));
                            }
                        }
                    } catch (RemoteException e) {
                        Log.e("MediaDatabaseAdapter", "scan error", e);
                    }
                });
            }
        });
    }

    // ── insertFile Hook ──

    private void initInsertHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        for (final var method : mMediaProviderClass.getDeclaredMethods()) {
            if (method.getName().equals("insertFile")) {
                XposedBridge.hookMethod(method, new InsertHooker(mHook, mService, mClassLoader));
            }
        }
    }
}
