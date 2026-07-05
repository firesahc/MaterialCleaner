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
            setupReRegisterOnDeath();
            MediaProviderHooksService.requestReRegister("initial MediaProvider load");
            new MediaProviderHook(mediaProviderHooksService, lpparam.classLoader, mediaProviderClass);
            Log.i("MC_REDIRECT", "[XposedInit] MediaProviderHook created successfully");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e("MC_REDIRECT", "[XposedInit] MediaProvider hook setup FAILED", e);
        }
    }

    private boolean registerHooksCallback() {
        if (mContext != null) {
            return HookBridgeRegistrar.registerHooksCallback(mContext, mediaProviderHooksService);
        }
        return false;
    }

    // 在 onMediaProviderLoaded 中设置自动重连回调（失败时指数退避重试）
    private void setupReRegisterOnDeath() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] attempts = {0};
        final Runnable retryTask = new Runnable() {
            @Override
            public void run() {
                attempts[0]++;
                Log.i("MC_REDIRECT", "[XposedInit] Re-registering hooks callback...");
                if (registerHooksCallback()) {
                    Log.i("MC_REDIRECT", "[XposedInit] Re-registration call completed");
                    attempts[0] = 0;
                    return;
                }
                Log.e("MC_REDIRECT", "[XposedInit] Re-registration failed (attempt " + attempts[0] + ")");
                if (attempts[0] < 10) {
                    long delay = Math.min(1000L << (attempts[0] - 1), 30000L);
                    handler.postDelayed(this, delay);
                }
            }
        };
        MediaProviderHooksService.sReRegisterCallback = () -> {
            handler.removeCallbacks(retryTask);
            attempts[0] = 0;
            retryTask.run();
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
