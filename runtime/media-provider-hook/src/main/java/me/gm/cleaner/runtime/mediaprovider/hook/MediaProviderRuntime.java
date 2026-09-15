package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class MediaProviderRuntime {
    private static Context sContext;
    private static MediaProviderHooksService sService;
    private static boolean sInlineHookInitialized;

    private MediaProviderRuntime() {
    }

    public static void bootstrap(LoadPackageParam lpparam, Context context, MediaProviderHooksService service) {
        sContext = context;
        sService = service;
        try {
            final var mediaProviderClass = XposedHelpers.findClass(
                    "com.android.providers.media.MediaProvider", lpparam.classLoader
            );
            Log.i("MC_REDIRECT", "[XposedInit] MediaProvider class found, registering hooks...");
            NativeHookStatus.INSTANCE.markMediaProviderHookLoaded(lpparam.packageName);
            initializeInlineHook(lpparam.packageName);
            setupReRegisterOnDeath();
            MediaProviderHooksService.requestReRegister("initial MediaProvider load");
            service.initPolicyCache();
            new MediaProviderHook(service, lpparam.classLoader, mediaProviderClass);
            Log.i("MC_REDIRECT", "[XposedInit] MediaProviderHook created successfully");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e("MC_REDIRECT", "[XposedInit] MediaProvider hook setup FAILED", e);
        }
    }

    private static boolean registerHooksCallback() {
        if (sContext != null && sService != null) {
            return HookBridgeRegistrar.registerHooksCallback(sContext, sService);
        }
        return false;
    }

    public static void initializeInlineHook(String packageName) {
        if (sInlineHookInitialized) {
            return;
        }
        Log.i("MC_REDIRECT", "[XposedInit] Loading inline lib for package: " + packageName);
        try {
            final var nativeStatus = FuseNativePolicyAdapter.INSTANCE.initializeInlineHook();
            NativeHookStatus.INSTANCE.markInlineLoadSucceeded(nativeStatus);
            sInlineHookInitialized = isNativeHookReady(nativeStatus);
            Log.i("MC_REDIRECT", "[XposedInit] libinline loaded and xhook initialized");
        } catch (Throwable e) {
            NativeHookStatus.INSTANCE.markInlineLoadFailed(e);
            Log.e("MC_REDIRECT", "[XposedInit] Failed to load inline library, FUSE native hook disabled", e);
        }
    }

    private static boolean isNativeHookReady(String nativeStatus) {
        return nativeStatus != null && nativeStatus.contains("\"containsMount\":true");
    }

    // 在 onMediaProviderLoaded 中设置自动重连回调，失败时有界突发+冷却探针恢复。
    private static void setupReRegisterOnDeath() {
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
}
