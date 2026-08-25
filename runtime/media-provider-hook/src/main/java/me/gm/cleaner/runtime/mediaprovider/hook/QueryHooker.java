package me.gm.cleaner.runtime.mediaprovider.hook;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * MediaProvider query Hook。
 *
 * <p>继承 {@link AbstractGuardedHook} 获得统一防护：任何 handler 异常不外抛、
 * 参数回滚、每方法熔断 —— 与 FuseJavaGate 同进程统一异常策略，
 * 保证宿主 MediaProvider 不因本 Hook 崩溃。
 * 本 Hook 无需回滚的 args 槽位（只读查询参数），guardedArgIndexes 为空。
 */
public class QueryHooker extends AbstractGuardedHook {
    private static final String INCLUDED_DEFAULT_DIRECTORIES = "android:included-default-directories";
    private static final int TYPE_QUERY = 0;
    private final MediaProviderHook mHook;
    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;

    public QueryHooker(MediaProviderHook hook, MediaProviderHooksService service, ClassLoader classLoader) {
        super("QueryHooker", "query", new int[0]);
        mHook = hook;
        mService = service;
        mClassLoader = classLoader;
    }

    @Override
    protected void handleBefore(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (mHook.isFuseThread()) {
            return;
        }
        /** ARGUMENTS */
        final var uri = (Uri) param.args[0];
        final var projection = (String[]) param.args[1];
        final Bundle queryArgs;
        if (param.args[2] == null) {
            queryArgs = Bundle.EMPTY;
        } else {
            queryArgs = (Bundle) param.args[2];
        }
        final var signal = (CancellationSignal) param.args[3];

        final var callingPackage = mHook.getCallingPackage(param.thisObject);
        if ("com.android.providers.media".equals(callingPackage) ||
                "com.android.providers.media.module".equals(callingPackage) ||
                "com.google.android.providers.media.module".equals(callingPackage)) {
            // Scanning files and internal queries.
            return;
        }

        /** PARSE */
        final var query = new Bundle(queryArgs);
        query.remove(INCLUDED_DEFAULT_DIRECTORIES);
        final var honoredArgs = new ArraySet<String>();
        final var databaseUtilsClass = XposedHelpers.findClass(
                "com.android.providers.media.util.DatabaseUtils", mClassLoader
        );
        XposedHelpers.callStaticMethod(
                databaseUtilsClass, "resolveQueryArgs", query, new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        honoredArgs.add(s);
                    }
                }, new Function<String, String>() {
                    @Override
                    public String apply(String s) {
                        return (String) XposedHelpers.callMethod(param.thisObject, "ensureCustomCollator", s);
                    }
                }
        );

        final var targetSdkVersion = (int) XposedHelpers.callMethod(
                param.thisObject, "getCallingPackageTargetSdkVersion");
        final var allowHidden = (boolean) XposedHelpers.callMethod(
                param.thisObject, "isCallingPackageAllowedHidden");
        final var table = (int) XposedHelpers.callMethod(param.thisObject, "matchUri", uri, allowHidden);

        final var dataProjection = new String[]{FileColumns.DATA};
        final var helper = XposedHelpers.callMethod(param.thisObject, "getDatabaseForUri", uri);
        final var qb = XposedHelpers.callMethod(param.thisObject, "getQueryBuilder",
                TYPE_QUERY, table, uri, query, new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        honoredArgs.add(s);
                    }
                });

        if (targetSdkVersion < Build.VERSION_CODES.R) {
            // Some apps are abusing "ORDER BY" clauses to inject "LIMIT"
            // clauses; gracefully lift them out.
            XposedHelpers.callStaticMethod(databaseUtilsClass, "recoverAbusiveSortOrder", query);

            // Some apps are abusing the Uri query parameters to inject LIMIT
            // clauses; gracefully lift them out.
            XposedHelpers.callStaticMethod(databaseUtilsClass, "recoverAbusiveLimit", uri, query);
        }

        if (targetSdkVersion < Build.VERSION_CODES.Q) {
            // Some apps are abusing the "WHERE" clause by injecting "GROUP BY"
            // clauses; gracefully lift them out.
            XposedHelpers.callStaticMethod(databaseUtilsClass, "recoverAbusiveSelection", query);
        }

        /** QUERY */
        // try-with-resources 保证任何分支退出（含 dataColumn == -1 的提前返回与
        // 遍历中途异常）都关闭 cursor，修复旧代码在提前 return 路径上的泄漏。
        final ArrayList<String> data;
        try (Cursor c = (Cursor) XposedHelpers.callMethod(
                qb, "query", helper, dataProjection, query, signal)) {
            if (c.getCount() == 0) {
                // querying nothing.
                return;
            }
            final var dataColumn = c.getColumnIndex(FileColumns.DATA);
            if (dataColumn == -1) {
                return;
            }
            data = new ArrayList<>();
            while (c.moveToNext()) {
                data.add(c.getString(dataColumn));
            }
        } catch (XposedHelpers.InvocationTargetError e) {
            // IllegalArgumentException that thrown from the media provider. Nothing I can do.
            // 预期宿主异常：计数进入 guardedHooks.swallowedHostExceptions 后静默放行，
            // 不计入熔断失败（避免常态化业务异常触发冷却）。
            NativeHookStatus.INSTANCE.markGuardedHostExceptionSwallowed();
            Log.d("MC_REDIRECT", "[QueryHooker] Swallowed host exception from query", e);
            return;
        }

        /** RECORD */
        final var threadLocal = (ThreadLocal<?>) XposedHelpers.getObjectField(
                param.thisObject, "mCallingIdentity");
        final var uid = (int) XposedHelpers.getObjectField(threadLocal.get(), "uid");
        QuerySessionCache.recordQueriedPaths(callingPackage, uid, data);
    }
}
