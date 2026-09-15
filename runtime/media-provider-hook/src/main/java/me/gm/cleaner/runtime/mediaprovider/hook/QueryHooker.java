package me.gm.cleaner.runtime.mediaprovider.hook;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore.Files.FileColumns;
import android.util.ArraySet;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    // DatabaseUtils Class/Method 缓存：热路径每 query 解析一次 findClass/findMethod
    // 开销显著，一次解析后复用。ROM 差异导致缺失时保持 null 并回退到 XposedHelpers。
    private final Object mResolveLock = new Object();
    private volatile boolean mDatabaseUtilsResolved;
    private volatile Class<?> mDatabaseUtilsClass;
    private volatile Method mResolveQueryArgs;
    private volatile Method mRecoverAbusiveSortOrder;
    private volatile Method mRecoverAbusiveLimit;
    private volatile Method mRecoverAbusiveSelection;
    private volatile Class<?> mProviderClass;
    private volatile Method mEnsureCustomCollator;
    private volatile Method mGetCallingPackageTargetSdkVersion;
    private volatile Method mIsCallingPackageAllowedHidden;
    private volatile Method mMatchUri;
    private volatile Method mGetDatabaseForUri;
    private volatile Method mGetQueryBuilder;
    private volatile Class<?> mQbClass;
    private volatile Method mQbQuery;

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
        /** ARGUMENTS（轻量强转，不做 Bundle 拷贝/反射/DB 查询） */
        final var uri = (Uri) param.args[0];
        final var callingPackage = mHook.getCallingPackage(param.thisObject);
        if ("com.android.providers.media".equals(callingPackage) ||
                "com.android.providers.media.module".equals(callingPackage) ||
                "com.google.android.providers.media.module".equals(callingPackage)) {
            // Scanning files and internal queries.
            return;
        }

        // 策略门旁路：无重定向规则/被拒/记录开关全关时直接返回，避免 Bundle 拷贝、
        // DatabaseUtils 反射与后续 DB 查询。注意不能用 hasActiveSession 做门：
        // session 本身由本次 RECORD 创建，用它做门会导致首个 session 永不创建（冷启动死锁）。
        // Query 保持 record-only 语义。
        if (HookPolicyCache.INSTANCE.isDenied(callingPackage)
                || (!HookPolicyCache.INSTANCE.getRecordExternalAppSpecificStorage()
                && !HookPolicyCache.INSTANCE.getAggressivelyPromptForReadingMediaFiles())
                || !HookPolicyCache.INSTANCE.hasRedirectRules(callingPackage)) {
            return;
        }
        final var callingUid = getCallingUid(param.thisObject);

        final var queryArgs = param.args[2] == null ? Bundle.EMPTY : (Bundle) param.args[2];
        final var signal = (CancellationSignal) param.args[3];

        /** PARSE */
        final var query = new Bundle(queryArgs);
        query.remove(INCLUDED_DEFAULT_DIRECTORIES);
        final var honoredArgs = new ArraySet<String>();
        ensureResolved(param.thisObject);
        final Consumer<String> honoredConsumer = new Consumer<String>() {
            @Override
            public void accept(String s) {
                honoredArgs.add(s);
            }
        };
        final Function<String, String> collatorFunction = new Function<String, String>() {
            @Override
            public String apply(String s) {
                try {
                    final var m = mEnsureCustomCollator;
                    if (m != null) {
                        return (String) m.invoke(param.thisObject, s);
                    }
                } catch (IllegalAccessException | InvocationTargetException ignored) {
                }
                return (String) XposedHelpers.callMethod(param.thisObject, "ensureCustomCollator", s);
            }
        };
        if (mResolveQueryArgs != null) {
            try {
                mResolveQueryArgs.invoke(null, query, honoredConsumer, collatorFunction);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        } else {
            final var databaseUtilsClass = mDatabaseUtilsClass != null ? mDatabaseUtilsClass
                    : XposedHelpers.findClass(
                    "com.android.providers.media.util.DatabaseUtils", mClassLoader);
            XposedHelpers.callStaticMethod(
                    databaseUtilsClass, "resolveQueryArgs", query, honoredConsumer, collatorFunction);
        }

        final var targetSdkVersion = (int) invokeProviderMethod(
                param.thisObject, mGetCallingPackageTargetSdkVersion,
                "getCallingPackageTargetSdkVersion");
        final var allowHidden = (boolean) invokeProviderMethod(
                param.thisObject, mIsCallingPackageAllowedHidden,
                "isCallingPackageAllowedHidden");
        final var table = (int) invokeProviderMethod(
                param.thisObject, mMatchUri, "matchUri", uri, allowHidden);

        final var dataProjection = new String[]{FileColumns.DATA};
        final var helper = invokeProviderMethod(
                param.thisObject, mGetDatabaseForUri, "getDatabaseForUri", uri);
        final var qb = invokeProviderMethod(
                param.thisObject, mGetQueryBuilder, "getQueryBuilder",
                TYPE_QUERY, table, uri, query, honoredConsumer);

        if (targetSdkVersion < Build.VERSION_CODES.R) {
            // Some apps are abusing "ORDER BY" clauses to inject "LIMIT"
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveSortOrder, "recoverAbusiveSortOrder", query);

            // Some apps are abusing the Uri query parameters to inject LIMIT
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveLimit, "recoverAbusiveLimit", uri, query);
        }

        if (targetSdkVersion < Build.VERSION_CODES.Q) {
            // Some apps are abusing the "WHERE" clause by injecting "GROUP BY"
            // clauses; gracefully lift them out.
            invokeDatabaseUtilsStatic(mRecoverAbusiveSelection, "recoverAbusiveSelection", query);
        }

        /** QUERY */
        // try-with-resources 保证任何分支退出（含 dataColumn == -1 的提前返回与
        // 遍历中途异常）都关闭 cursor，修复旧代码在提前 return 路径上的泄漏。
        final ArrayList<String> data;
        try (Cursor c = (Cursor) invokeQbQuery(qb, helper, dataProjection, query, signal)) {
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
        } catch (InvocationTargetException e) {
            NativeHookStatus.INSTANCE.markGuardedHostExceptionSwallowed();
            Log.d("MC_REDIRECT", "[QueryHooker] Swallowed host exception from query", e);
            return;
        }

        /** RECORD */
        final var uid = callingUid != -1 ? callingUid : getCallingUid(param.thisObject);
        if (uid == -1) {
            return;
        }
        QuerySessionCache.recordQueriedPaths(callingPackage, uid, data);
    }

    private static int getCallingUid(Object provider) {
        try {
            final var threadLocal = (ThreadLocal<?>) XposedHelpers.getObjectField(
                    provider, "mCallingIdentity");
            final var identity = threadLocal != null ? threadLocal.get() : null;
            if (identity == null) {
                return -1;
            }
            return (int) XposedHelpers.getObjectField(identity, "uid");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void ensureResolved(Object provider) {
        if (mDatabaseUtilsResolved && mProviderClass == provider.getClass()) {
            return;
        }
        synchronized (mResolveLock) {
            if (!mDatabaseUtilsResolved) {
                try {
                    final var cls = XposedHelpers.findClass(
                            "com.android.providers.media.util.DatabaseUtils", mClassLoader);
                    mDatabaseUtilsClass = cls;
                    mResolveQueryArgs = findMethod(cls, "resolveQueryArgs", 3, true);
                    mRecoverAbusiveSortOrder = findMethod(cls, "recoverAbusiveSortOrder", 1, true);
                    mRecoverAbusiveLimit = findMethod(cls, "recoverAbusiveLimit", 2, true);
                    mRecoverAbusiveSelection = findMethod(cls, "recoverAbusiveSelection", 1, true);
                } catch (XposedHelpers.ClassNotFoundError ignored) {
                    mDatabaseUtilsClass = null;
                }
                mDatabaseUtilsResolved = true;
            }
            final var providerClass = provider.getClass();
            if (mProviderClass != providerClass) {
                mEnsureCustomCollator = findMethod(providerClass, "ensureCustomCollator", 1, false);
                mGetCallingPackageTargetSdkVersion =
                        findMethod(providerClass, "getCallingPackageTargetSdkVersion", 0, false);
                mIsCallingPackageAllowedHidden =
                        findMethod(providerClass, "isCallingPackageAllowedHidden", 0, false);
                mMatchUri = findMethod(providerClass, "matchUri", 2, false);
                mGetDatabaseForUri = findMethod(providerClass, "getDatabaseForUri", 1, false);
                mGetQueryBuilder = findMethod(providerClass, "getQueryBuilder", 5, false);
                mProviderClass = providerClass;
            }
        }
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount, boolean staticOnly) {
        for (var c = cls; c != null; c = c.getSuperclass()) {
            for (final var m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != paramCount) {
                    continue;
                }
                if (staticOnly != java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                }
                return m;
            }
        }
        return null;
    }

    private static Object invokeProviderMethod(Object target, Method cached, String name,
            Object... args) throws Throwable {
        if (cached != null) {
            try {
                return cached.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        return XposedHelpers.callMethod(target, name, args);
    }

    private void invokeDatabaseUtilsStatic(Method cached, String name, Object... args)
            throws Throwable {
        if (cached != null) {
            try {
                cached.invoke(null, args);
                return;
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
        final var cls = mDatabaseUtilsClass != null ? mDatabaseUtilsClass
                : XposedHelpers.findClass(
                "com.android.providers.media.util.DatabaseUtils", mClassLoader);
        XposedHelpers.callStaticMethod(cls, name, args);
    }

    private Object invokeQbQuery(Object qb, Object helper, String[] dataProjection,
            Bundle query, CancellationSignal signal) throws Throwable {
        final var qbClass = qb.getClass();
        var m = mQbQuery;
        if (m == null || mQbClass != qbClass) {
            synchronized (mResolveLock) {
                m = mQbQuery;
                if (m == null || mQbClass != qbClass) {
                    mQbQuery = findMethod(qbClass, "query", 4, false);
                    mQbClass = qbClass;
                    m = mQbQuery;
                }
            }
        }
        if (m != null) {
            return m.invoke(qb, helper, dataProjection, query, signal);
        }
        return XposedHelpers.callMethod(qb, "query", helper, dataProjection, query, signal);
    }
}
