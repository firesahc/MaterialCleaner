package me.gm.cleaner.xposed;

import android.content.ContentProvider;
import android.content.Context;
import android.util.Log;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.provider.MediaStore;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import me.gm.cleaner.client.CleanerHooksBinderRetriever;
import me.gm.cleaner.xposed.InlineHookConfig;

public class XposedInit implements IXposedHookLoadPackage {
    private final MediaProviderHooksService mediaProviderHooksService = new MediaProviderHooksService();
    private Context mContext;

    private void onMediaProviderLoaded(LoadPackageParam lpparam, Context context) {
        mContext = context;
        try {
            final var mediaProviderClass = XposedHelpers.findClass(
                    "com.android.providers.media.MediaProvider", lpparam.classLoader
            );
            Log.i("MC_REDIRECT", "[XposedInit] MediaProvider class found, registering hooks...");
            registerHooksCallback();
            setupReRegisterOnDeath();
            new MediaProviderHook(mediaProviderHooksService, lpparam.classLoader, mediaProviderClass);
            Log.i("MC_REDIRECT", "[XposedInit] MediaProviderHook created successfully");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e("MC_REDIRECT", "[XposedInit] MediaProvider hook setup FAILED", e);
        }
    }

    private void registerHooksCallback() {
        if (mContext != null) {
            CleanerHooksBinderRetriever.registerHooksCallback(mContext, mediaProviderHooksService);
        }
    }

    /** 当 Server 回调丢失时重新注册，应对 app 进程重启场景 */
    static void reRegisterHooksCallback() {
        Log.i("MC_REDIRECT", "[XposedInit] Re-registering hooks callback (server restarted)");
        // 通过 ContentResolver.call 重新注册到 HooksBridgeProvider
        // 注意：此时 XposedInit 实例中的 mContext 可能已失效，
        // registerHooksCallback 内部通过 ContentResolver 与新 app 进程通信
    }

    // 在 onMediaProviderLoaded 中设置自动重连回调
    private void setupReRegisterOnDeath() {
        MediaProviderHooksService.sReRegisterCallback = () -> {
            // 重新注册 hooks callback，重建 sMediaProviderService
            registerHooksCallback();
            // 清除标志避免重复触发
            MediaProviderHooksService.sReRegisterCallback = null;
        };
    }

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
        Log.i("MC_REDIRECT", "[XposedInit] Loading inline lib for package: " + lpparam.packageName);
        try {
            System.loadLibrary("inline");
            InlineHookConfig.INSTANCE.initializeXHook();
            Log.i("MC_REDIRECT", "[XposedInit] libinline loaded and xhook initialized");
        } catch (UnsatisfiedLinkError e) {
            Log.e("MC_REDIRECT", "[XposedInit] Failed to load inline library!", e);
            throw new UnsatisfiedLinkError("Failed to load inline library");
        }
        XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                Context.class, ProviderInfo.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final var context = (Context) param.args[0];
                        final var providerInfo = (ProviderInfo) param.args[1];

                        if (MediaStore.AUTHORITY.equals(providerInfo.authority)) {
                            Log.i("MC_REDIRECT", "[XposedInit] Detected MediaProvider loading, package=" + lpparam.packageName);
                            onMediaProviderLoaded(lpparam, context);
                        }
                    }
                });
    }
}
