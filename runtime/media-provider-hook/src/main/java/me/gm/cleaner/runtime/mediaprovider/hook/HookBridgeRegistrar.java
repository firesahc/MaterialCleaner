package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

final class HookBridgeRegistrar {
    private static final String TAG = "HookBridgeRegistrar";
    private static final Uri HOOKS_URI = Uri.parse("content://me.gm.cleaner.hooks_bridge");

    private HookBridgeRegistrar() {
    }

    static boolean registerHooksCallback(Context context, IBinder binder) {
        try {
            final Bundle extras = new Bundle();
            extras.putBinder("binder", binder);
            context.getContentResolver().call(HOOKS_URI, "register_hooks_callback", null, extras);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register hooks callback", e);
            return false;
        }
    }
}
