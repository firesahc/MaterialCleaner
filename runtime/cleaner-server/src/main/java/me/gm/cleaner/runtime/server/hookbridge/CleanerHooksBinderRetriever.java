package me.gm.cleaner.runtime.server.hookbridge;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import api.SystemService;
import me.gm.cleaner.runtime.server.ktx.IContentProviderKt;

/**
 * Retrieves the hooks service Binder via the HooksBridgeProvider ContentProvider.
 *
 * <p>Replaces the previous Zygisk custom transaction mechanism. Both the root Server process
 * obtains the app-process bridge through the app's ContentProvider running in the
 * {@code me.gm.cleaner} process.</p>
 */
public class CleanerHooksBinderRetriever {
    private static final String TAG = "HooksBinderRetriever";
    private static final String AUTHORITY = "me.gm.cleaner.hooks_bridge";

    /**
     * Retrieve the {@link me.gm.cleaner.server.ICleanerHooksService} binder from the
     * ContentProvider bridge.
     *
     * @param context A valid context (Server or app process) for ContentResolver access.
     * @return The ICleanerHooksService binder, or {@code null} if the bridge is unavailable.
     */
    public static IBinder get(Context context) {
        // When called from the cleaner_server root process, the standard ContentResolver.call()
        // fails with "Unable to find app for caller" because the root process has no
        // ApplicationThread registered with the ActivityManager.
        // Use SystemService.getContentProviderExternal() to bypass this restriction,
        // same approach used by BinderSender.
        try {
            final var userId = 0;
            final var provider = SystemService.getContentProviderExternal(
                    AUTHORITY, userId, null, AUTHORITY);
            if (provider == null) {
                Log.e(TAG, "Failed to get content provider external");
                return null;
            }
            try {
                final var reply = IContentProviderKt.callCompat(
                        provider, null, AUTHORITY, "get_hooks_service", null, null);
                if (reply != null) {
                    return reply.getBinder("binder");
                }
            } finally {
                try {
                    SystemService.removeContentProviderExternal(AUTHORITY, null);
                } catch (final Throwable tr) {
                    Log.w(TAG, "Failed to remove content provider external", tr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get hooks service", e);
        }
        return null;
    }

}

