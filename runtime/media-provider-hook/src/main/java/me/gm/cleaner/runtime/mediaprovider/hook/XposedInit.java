package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import me.gm.cleaner.core.common.err.ErrorCodes;
import me.gm.cleaner.runtime.mediaprovider.hook.bootstrap.MediaProviderRuntime;
import me.gm.cleaner.runtime.mediaprovider.hook.media.MediaProviderHooksService;
import me.gm.cleaner.runtime.mediaprovider.hook.policy.NativeHookStatus;

public class XposedInit implements IXposedHookLoadPackage {
    private static final String TAG = "MC_REDIRECT";
    private static final String MEDIA_PROVIDER_CLASS = "com.android.providers.media.MediaProvider";
    private static final long BOOTSTRAP_WATCHDOG_DELAY_MS = 15_000L;

    private final MediaProviderHooksService mediaProviderHooksService = new MediaProviderHooksService();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        // 只处理 MediaProvider 进程，忽略其他所有系统应用
        switch (lpparam.packageName) {
            case "com.android.providers.media":
            case "com.android.providers.media.module":
            case "com.google.android.providers.media.module":
                break;
            default:
                return;
        }
        Log.i(TAG, "[XposedInit] Installing MediaProvider attach hook for package: " + lpparam.packageName);
        MediaProviderRuntime.initializeInlineHook(lpparam.packageName);

        // 主触发：子类 attachInfo / onCreate（invoke-virtual，可拦截）
        hookMediaProviderClass(lpparam);
        // 冗余触发：父类 attachInfo（invoke-super 场景下可能拦不到，但覆盖面更广）
        hookBaseProviderAttachInfo(lpparam);

        armBootstrapWatchdog();
    }

    private void hookMediaProviderClass(LoadPackageParam lpparam) {
        final Class<?> mediaProviderClass = resolveMediaProviderClass(lpparam);
        if (mediaProviderClass == null) {
            Log.e(TAG, "[XposedInit] Cannot resolve " + MEDIA_PROVIDER_CLASS +
                    " in " + lpparam.packageName + "; only the base-class hook is active");
            NativeHookStatus.INSTANCE.recordHookError(
                    ErrorCodes.HOOK_JAVA_LOAD_CLASS_NOT_FOUND,
                    "stage=resolve_media_provider_class, package=" + lpparam.packageName
                            + ", class=" + MEDIA_PROVIDER_CLASS);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(mediaProviderClass, "attachInfo",
                    Context.class, ProviderInfo.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Context context = (Context) param.args[0];
                            final ProviderInfo providerInfo = (ProviderInfo) param.args[1];
                            final String authority =
                                    providerInfo == null ? null : providerInfo.authority;
                            Log.i(TAG, "[XposedInit] Detected MediaProvider loading, " +
                                    "package=" + lpparam.packageName + ", authority=" + authority);
                            MediaProviderRuntime.bootstrap(
                                    lpparam, context, mediaProviderHooksService);
                        }
                    });
            Log.i(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".attachInfo hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".attachInfo hook not installed", t);
            NativeHookStatus.INSTANCE.recordHookError(
                    ErrorCodes.HOOK_JAVA_LOAD_FAILED,
                    "stage=install_attach_info_hook, package=" + lpparam.packageName
                            + ", failureType=" + t.getClass().getSimpleName());
        }

        try {
            XposedHelpers.findAndHookMethod(mediaProviderClass, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof ContentProvider)) {
                                return;
                            }
                            final Context context =
                                    ((ContentProvider) param.thisObject).getContext();
                            Log.i(TAG, "[XposedInit] MediaProvider.onCreate reached, " +
                                    "bootstrapping (fallback trigger)");
                            MediaProviderRuntime.bootstrap(
                                    lpparam, context, mediaProviderHooksService);
                        }
                    });
            Log.i(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".onCreate fallback hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] " + MEDIA_PROVIDER_CLASS + ".onCreate fallback hook not installed", t);
            NativeHookStatus.INSTANCE.recordHookError(
                    ErrorCodes.HOOK_JAVA_LOAD_FAILED,
                    "stage=install_oncreate_fallback_hook, package=" + lpparam.packageName
                            + ", failureType=" + t.getClass().getSimpleName());
        }
    }

    private Class<?> resolveMediaProviderClass(LoadPackageParam lpparam) {
        try {
            return XposedHelpers.findClass(MEDIA_PROVIDER_CLASS, lpparam.classLoader);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(MEDIA_PROVIDER_CLASS, false, lpparam.classLoader);
        } catch (Throwable ignored) {
        }
        try {
            return lpparam.classLoader.loadClass(MEDIA_PROVIDER_CLASS);
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookBaseProviderAttachInfo(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Context context = (Context) param.args[0];
                            final ProviderInfo providerInfo = (ProviderInfo) param.args[1];
                            final String authority =
                                    providerInfo == null ? null : providerInfo.authority;
                            final Object self = param.thisObject;
                            Log.d(TAG, "[XposedInit] base attachInfo: class=" +
                                    (self == null ? "?" : self.getClass().getName()) +
                                    ", authority=" + authority);

                            if (MediaStore.AUTHORITY.equals(authority)) {
                                Log.i(TAG, "[XposedInit] Detected MediaProvider loading via base " +
                                        "hook, package=" + lpparam.packageName);
                                MediaProviderRuntime.bootstrap(
                                        lpparam, context, mediaProviderHooksService);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "[XposedInit] ContentProvider.attachInfo base hook not installed", t);
            NativeHookStatus.INSTANCE.recordHookError(
                    ErrorCodes.HOOK_JAVA_LOAD_FAILED,
                    "stage=install_base_attach_info_hook, package=" + lpparam.packageName
                            + ", failureType=" + t.getClass().getSimpleName());
        }
    }

    private void armBootstrapWatchdog() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MediaProviderRuntime.isBootstrapped()) {
                return;
            }
            Log.e(TAG, "[XposedInit] WATCHDOG: MediaProvider bootstrap never ran within " +
                    BOOTSTRAP_WATCHDOG_DELAY_MS + "ms. The Hook Binder will never be " +
                    "registered, so MEDIA_PROVIDER_JAVA_HOOK stays UNAVAILABLE and the server " +
                    "keeps trying to recover.");
        }, BOOTSTRAP_WATCHDOG_DELAY_MS);
    }
}
