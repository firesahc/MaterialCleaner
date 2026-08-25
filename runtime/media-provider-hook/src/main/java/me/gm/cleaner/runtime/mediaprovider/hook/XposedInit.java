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
    private boolean mInlineHookInitialized;

    private void onMediaProviderLoaded(LoadPackageParam lpparam, Context context) {
        mContext = context;
        try {
            final var mediaProviderClass = XposedHelpers.findClass(
                    "com.android.providers.media.MediaProvider", lpparam.classLoader
            );
            Log.i("MC_REDIRECT", "[XposedInit] MediaProvider class found, registering hooks...");
            NativeHookStatus.INSTANCE.markMediaProviderHookLoaded(lpparam.packageName);
            initializeInlineHook(lpparam.packageName);
            setupReRegisterOnDeath();
            MediaProviderHooksService.requestReRegister("initial MediaProvider load");
            mediaProviderHooksService.initPolicyCache();
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

    private void initializeInlineHook(String packageName) {
        if (mInlineHookInitialized) {
            return;
        }
        Log.i("MC_REDIRECT", "[XposedInit] Loading inline lib for package: " + packageName);
        try {
            final var nativeStatus = FuseNativePolicyAdapter.INSTANCE.initializeInlineHook();
            NativeHookStatus.INSTANCE.markInlineLoadSucceeded(nativeStatus);
            mInlineHookInitialized = isNativeHookReady(nativeStatus);
            Log.i("MC_REDIRECT", "[XposedInit] libinline loaded and xhook initialized");
        } catch (Throwable e) {
            NativeHookStatus.INSTANCE.markInlineLoadFailed(e);
            Log.e("MC_REDIRECT", "[XposedInit] Failed to load inline library, FUSE native hook disabled", e);
        }
    }

    private boolean isNativeHookReady(String nativeStatus) {
        return nativeStatus != null && nativeStatus.contains("\"containsMount\":true");
    }

    // 在 onMediaProviderLoaded 中设置自动重连回调，失败时有界突发+冷却探针恢复。
    private void setupReRegisterOnDeath() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final BridgeRegistrationRetryGate retryGate = new BridgeRegistrationRetryGate();
        final int[] attempts = {0};
        final boolean[] cooldownProbe = {false};
        final Runnable retryTask = new Runnable() {
            @Override
            public void run() {
                if (!retryGate.beginScheduledRun()) {
                    return;
                }
                final boolean isCooldownProbe = cooldownProbe[0];
                cooldownProbe[0] = false;
                if (!isCooldownProbe) {
                    // 冷却探针不计入突发预算：它本身就是预算耗尽后的低频试探。
                    attempts[0]++;
                    Log.i("MC_REDIRECT", "[XposedInit] Re-registering hooks callback...");
                }
                if (registerHooksCallback()) {
                    Log.i("MC_REDIRECT", "[XposedInit] Re-registration call completed");
                    attempts[0] = 0;
                    retryGate.markIdle();
                    return;
                }
                Log.e("MC_REDIRECT", "[XposedInit] Re-registration failed (attempt " + attempts[0] + ")");
                NativeHookStatus.INSTANCE.markBridgeFailed(
                        "re-register attempt " + attempts[0] + " failed");
                if (isCooldownProbe || BridgeRegistrationRetryPolicy.isBurstExhausted(attempts[0])) {
                    cooldownProbe[0] = true;
                    NativeHookStatus.INSTANCE.markBridgeRetryScheduled(attempts[0]);
                    retryGate.markWaiting();
                    if (!handler.postDelayed(this, BridgeRegistrationRetryPolicy.COOLDOWN_MILLIS)) {
                        retryGate.markIdle();
                    }
                    return;
                }
                NativeHookStatus.INSTANCE.markBridgeRetryScheduled(attempts[0]);
                retryGate.markWaiting();
                if (!handler.postDelayed(
                        this,
                        BridgeRegistrationRetryPolicy.delayMillis(attempts[0]))) {
                    retryGate.markIdle();
                }
            }
        };
        MediaProviderHooksService.sReRegisterCallback = () -> {
            // 合并重注册请求：突发/冷却期间的外部事件不重置预算，
            // 防止 Binder 死亡风暴无限重启退避序列。
            if (!retryGate.requestSchedule()) {
                Log.i("MC_REDIRECT", "[XposedInit] Re-registration request coalesced");
                return;
            }
            if (!handler.post(retryTask)) {
                retryGate.markIdle();
            }
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
        Log.i("MC_REDIRECT", "[XposedInit] Installing MediaProvider attach hook for package: " + lpparam.packageName);
        initializeInlineHook(lpparam.packageName);
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
