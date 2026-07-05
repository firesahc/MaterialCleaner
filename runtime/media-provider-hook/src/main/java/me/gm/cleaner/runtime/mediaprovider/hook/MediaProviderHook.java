package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.Build;
import android.util.Log;
import android.util.SparseLongArray;

import de.robv.android.xposed.XposedHelpers;

/**
 * MediaProvider Hook 引导装配器。
 *
 * 职责：
 * - 持有共享状态（mQueryRecord）
 * - 提供共享工具方法（isFuseThread, getCallingPackage）
 * - 装配子组件：MediaDatabaseAdapter + FuseJavaGate
 *
 * 不直接注册任何 Hook——具体 Hook 注册委托给子组件。
 */
public class MediaProviderHook {
    static final int TYPE_CONNECTION = 1;
    static final int TYPE_INSERT = 2;

    final SparseLongArray mQueryRecord = new SparseLongArray();

    private final MediaProviderHooksService mService;
    private final ClassLoader mClassLoader;
    private final Class<?> mMediaProviderClass;
    private final MediaDatabaseAdapter mDatabaseAdapter;
    private final FuseJavaGate mFuseJavaGate;

    public MediaProviderHook(MediaProviderHooksService service,
                             ClassLoader classLoader, Class<?> mediaProviderClass) {
        mService = service;
        mClassLoader = classLoader;
        mMediaProviderClass = mediaProviderClass;

        // 1. 装配数据库适配器（注册 scan + insert Hook）
        mDatabaseAdapter = new MediaDatabaseAdapter(this, mService, mClassLoader, mMediaProviderClass);

        // 2. 判断 FUSE 是否可用。
        // 注意：理想架构要求从 DataBus PlatformCapabilities snapshot 读取 FUSE 可用性，
        // 但构造时 HookPolicyCache 尚未初始化（bootstrapping 约束），因此回退到读系统属性。
        // 一旦 HookPolicyCache 通过 initFromDataBus() 加载能力，FUSE 状态以 DataBus 快照为准。
        final boolean isFuseAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                || HookSystemProperties.getBoolean("persist.sys.fuse", false);
        Log.i("MC_REDIRECT", "[MediaProviderHook] isFuseAvailable=" + isFuseAvailable
                + " SDK=" + Build.VERSION.SDK_INT
                + " persist.sys.fuse=" + HookSystemProperties.get("persist.sys.fuse", "(null)"));

        if (isFuseAvailable) {
            // 3. 注册 query Hook（需 FUSE 可用）
            mDatabaseAdapter.initQueryHookIfFuseAvailable();
            // 4. 装配 FUSE 门（注册所有 FUSE Java 方法 Hook）
            mFuseJavaGate = new FuseJavaGate(this, mService, mClassLoader, mMediaProviderClass);
        } else {
            mFuseJavaGate = null;
        }
    }

    // ── 共享工具方法 ──

    boolean isFuseThread() {
        try {
            final var fuseDaemonCls = XposedHelpers.findClass(
                    "com.android.providers.media.fuse.FuseDaemon", mClassLoader);
            return (boolean) XposedHelpers.callStaticMethod(
                    fuseDaemonCls, "native_is_fuse_thread");
        } catch (final XposedHelpers.ClassNotFoundError e) {
            return false;
        }
    }

    String getCallingPackage(final Object mp) {
        final var threadLocal = (ThreadLocal<?>) XposedHelpers.getObjectField(mp, "mCallingIdentity");
        return (String) XposedHelpers.callMethod(threadLocal.get(), "getPackageName");
    }
}
