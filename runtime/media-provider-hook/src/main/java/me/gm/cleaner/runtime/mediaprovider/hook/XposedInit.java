package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.provider.MediaStore;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedInit implements IXposedHookLoadPackage {
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
        Log.i("MC_REDIRECT", "[XposedInit] Installing MediaProvider attach hook for package: " + lpparam.packageName);
        MediaProviderRuntime.initializeInlineHook(lpparam.packageName);
        XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                Context.class, ProviderInfo.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final var context = (Context) param.args[0];
                        final var providerInfo = (ProviderInfo) param.args[1];

                        if (MediaStore.AUTHORITY.equals(providerInfo.authority)) {
                            Log.i("MC_REDIRECT", "[XposedInit] Detected MediaProvider loading, package=" + lpparam.packageName);
                            MediaProviderRuntime.bootstrap(lpparam, context, mediaProviderHooksService);
                        }
                    }
                });
    }
}
