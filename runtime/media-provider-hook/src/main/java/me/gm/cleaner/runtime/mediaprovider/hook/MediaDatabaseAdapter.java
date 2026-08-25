package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentProvider;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
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
        XposedBridge.hookMethod(callMethod, new ScanCallHooker());
    }

    /**
     * scan_file Hook：把 MediaProvider 的扫描路径重定向到本地挂载路径。
     *
     * <p>继承 {@link AbstractGuardedHook} 获得统一防护：任何 handler 异常不外抛、
     * 参数回滚、每方法熔断 —— 与 FuseJavaGate 同进程统一异常策略。
     *
     * <p>参数回滚说明：R 分支重定向写入 {@code args[1]}（scan 路径槽位），
     * 由 guardedArgIndexes={1} 自动快照回滚；Q 分支的
     * {@code extras.putParcelable(Intent.EXTRA_STREAM, ...)} 修改的是 Bundle
     * 内部状态而非槽位引用，无法走槽位快照 —— 但该写入是 handler 的最后一步、
     * 其后仅剩日志调用，异常窗口为零，无需额外回滚。
     */
    private class ScanCallHooker extends AbstractGuardedHook {
        ScanCallHooker() {
            super("ScanCallHooker", "call:scan_file", new int[]{1});
        }

        @Override
        protected void handleBefore(XC_MethodHook.MethodHookParam param) {
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
            final var localMountedPath = HookPolicyCache.INSTANCE.getMountedPath(
                    callingPackage, file.getPath());
            if (localMountedPath != null && !file.getPath().equals(localMountedPath)) {
                applyScanPath(param, extras, localMountedPath);
                Log.i("MC_REDIRECT", "[MediaDatabaseAdapter] scan_file redirected (local cache): "
                        + file.getPath() + " -> " + localMountedPath);
            }
        }
    }

    private void applyScanPath(final XC_MethodHook.MethodHookParam param,
                               final Bundle extras, final String mountedPath) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            param.args[1] = mountedPath;
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            extras.putParcelable(Intent.EXTRA_STREAM, Uri.parse(mountedPath));
        }
    }

    // ── insertFile Hook ──

    private void initInsertHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        for (final var method : mMediaProviderClass.getDeclaredMethods()) {
            if (method.getName().equals("insertFile")) {
                XposedBridge.hookMethod(method, new InsertHooker(mHook, mClassLoader));
            }
        }
    }
}
