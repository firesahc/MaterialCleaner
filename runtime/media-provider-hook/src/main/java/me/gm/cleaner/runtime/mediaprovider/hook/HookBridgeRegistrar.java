package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

final class HookBridgeRegistrar {
    private static final String TAG = "HookBridgeRegistrar";
    private static final Uri HOOKS_URI = Uri.parse("content://me.gm.cleaner.hooks_bridge");
    private static final String EXTRA_REGISTERED = "registered";

    private HookBridgeRegistrar() {
    }

    static boolean registerHooksCallback(Context context, IBinder binder) {
        NativeHookStatus.INSTANCE.markBridgeRegistering();
        try {
            final Bundle extras = new Bundle();
            extras.putBinder("binder", binder);
            // 显式确认协议：新版 Provider 回传 registered 字段校验真实接入；
            // 旧版无确认字段时保持兼容，仅要求调用不抛异常。
            final Bundle result = context.getContentResolver().call(
                    HOOKS_URI, "register_hooks_callback", null, extras);
            final boolean accepted = BridgeRegistrationAckPolicy.isAccepted(
                    result != null,
                    result != null && result.containsKey(EXTRA_REGISTERED),
                    result != null && result.getBoolean(EXTRA_REGISTERED, false));
            if (!accepted) {
                NativeHookStatus.INSTANCE.markBridgeFailed(
                        "provider acknowledged registered=false");
                return false;
            }
            NativeHookStatus.INSTANCE.markBridgeRegistered();
            return true;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                throw (VirtualMachineError) e;
            }
            if (e instanceof ThreadDeath) {
                throw (ThreadDeath) e;
            }
            Log.e(TAG, "Failed to register hooks callback", e);
            NativeHookStatus.INSTANCE.markBridgeFailed(describeThrowable(e));
            return false;
        }
    }

    /** 供 markBridgeFailed 使用的受控异常描述；截断由 NativeHookStatus 统一处理。 */
    private static String describeThrowable(Throwable e) {
        final String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getName();
        }
        return e.getClass().getName() + ": " + message;
    }
}
