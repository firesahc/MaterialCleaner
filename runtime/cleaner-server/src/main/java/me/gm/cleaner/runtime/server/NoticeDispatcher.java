package me.gm.cleaner.runtime.server;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import java.util.Objects;
import java.util.function.Consumer;

import api.SystemService;
import me.gm.cleaner.core.common.RuntimeFileUtils;

/**
 * Control-plane UI notice dispatcher.
 *
 * Server/runtime components report facts as DataBus events or callbacks. This
 * class is the single place that turns those facts into app-facing notices.
 */
public class NoticeDispatcher {
    private final CleanerServer mServer;

    public NoticeDispatcher(final CleanerServer server) {
        mServer = server;
    }

    private void broadcastIntentDelayed(final Consumer<Intent> callback, long delayMillis) {
        final var extra = new Bundle(1);
        extra.putBinder(ServerConstants.EXTRA_BINDER, mServer.cleanerService);
        @SuppressLint("WrongConstant") final var intent = new Intent()
                .setClassName(mServer, ServerConstants.RECEIVER_ACTIVITY_NAME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .setPackage(ServerConstants.APPLICATION_ID)
                .putExtra(Intent.EXTRA_RESTRICTIONS_BUNDLE, extra);
        callback.accept(intent);
        Objects.requireNonNull(intent.getAction());
        mServer.handler.postDelayed(() -> SystemService.startActivityNoThrow(intent, null, 0),
                delayMillis);
    }

    public void broadcastIntentDelayed(final Consumer<Intent> callback) {
        broadcastIntentDelayed(callback, 2000);
    }

    public void broadcastIntent(final Consumer<Intent> callback) {
        broadcastIntentDelayed(callback, 0);
    }

    public void showRedirectNotice(String packageName, String originalPath,
                                   String mountedPath, String type) {
        final var finalPath = originalPath;
        broadcastIntent(intent -> {
            intent.setAction(ServerConstants.ACTION_REDIRECTED_TO_INTERNAL);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                    SystemService.getPackageInfoNoThrow(packageName, 0, 0));
            intent.putExtra(Intent.EXTRA_TEXT, mountedPath);
            intent.setType("INSERT".equals(type) ? "2" : "0");
            intent.putExtra(Intent.EXTRA_STREAM,
                    RuntimeFileUtils.INSTANCE.getPathAsUser(finalPath, 0));
        });
    }

    public void showMediaNotFoundNotice(String packageName, String path, boolean aggressive) {
        final var finalPath = path;
        broadcastIntent(intent -> {
            intent.setAction(ServerConstants.ACTION_MEDIA_NOT_FOUND);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                    SystemService.getPackageInfoNoThrow(packageName, 0, 0));
            intent.putExtra(Intent.EXTRA_TEXT, finalPath);
            if (aggressive) {
                intent.setType(Intent.EXTRA_SUBJECT);
            }
            intent.putExtra(Intent.EXTRA_STREAM,
                    RuntimeFileUtils.INSTANCE.getPathAsUser(finalPath, 0));
        });
    }
}
