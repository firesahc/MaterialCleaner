package me.gm.cleaner.runtime.server;

import static hidden.HiddenApiBridge.Context_createPackageContextAsUser;
import static hidden.HiddenApiBridge.createUserHandle;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import api.SystemService;
import me.gm.cleaner.core.config.SecurityHelper;
import me.gm.cleaner.runtime.server.BuildConfig;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.observer.BaseProcessObserver;
import me.gm.cleaner.runtime.server.observer.ObserverManager;
import me.gm.cleaner.runtime.server.observer.StorageEventListenerDelegate;
import me.gm.cleaner.runtime.server.observer.StorageMountObserver;
import me.gm.cleaner.util.FileUtils;
import me.gm.cleaner.util.LibUtils;

public class CleanerServer extends ContextWrapper {
    public final Handler handler = new Handler(Looper.getMainLooper());
    public volatile CleanerService cleanerService;
    public final PackageInfo packageInfo;
    // System service lifecycle
    final PackageReceiver mPackageReceiver;
    final AutoLogging mAutoLogging;
    public final CleanerServerCallback mCleanerServerCallback;
    public final LayerOrchestrator layerOrchestrator;

    private Context createPackageContext(final String packageName) {
        try {
            return Context_createPackageContextAsUser(
                    ActivityThread.systemMain().getSystemContext(),
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                    createUserHandle(0));
        } catch (final PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void waitSystemService(final String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "waitSystemService " + name);
                Thread.sleep(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void waitSystemServices() {
        for (final var name : List.of(
                "activity", "package", Context.USER_SERVICE, Context.APP_OPS_SERVICE, "mount"
        )) {
            waitSystemService(name);
        }
    }

    void sendBinderToManger(final Binder binder) {
        for (final var userId : SystemService.getUserIdsNoThrow()) {
            BinderSender.sendBinderToManger(binder, userId);
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public CleanerServer() {
        super((Context) null);
        waitSystemServices();
        packageInfo = SystemService.getPackageInfoNoThrow(ServerConstants.APPLICATION_ID, 0, 0);
        if (packageInfo == null) {
            throw new RuntimeException("Failed to getPackageInfo");
        }
        attachBaseContext(createPackageContext(ServerConstants.APPLICATION_ID));
        LibUtils.loadLibrary(LibUtils.getLibSourceDir(packageInfo.applicationInfo), "cleaner");
        SecurityHelper.INSTANCE.warmUpJcaProviders();
        final var dpsContext = new ContextWrapper(createDeviceProtectedStorageContext()) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        SecurityHelper.INSTANCE.init(dpsContext);
        ServicePreferences.INSTANCE.init(dpsContext);
        mPackageReceiver = new PackageReceiver(this);
        mAutoLogging = new AutoLogging(packageInfo);
        mCleanerServerCallback = new CleanerServerCallback(this);
        cleanerService = new CleanerService(this, packageInfo.applicationInfo.uid);
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "Cleaner server v" + BuildConfig.VERSION_CODE + " started");
        layerOrchestrator = new LayerOrchestrator(this);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    public void onStorageManagerServiceReady() {
        layerOrchestrator.initialize();
    }

    public void onStorageMounted(final VolumeInfo vol, final boolean isPrimary,
                                 final boolean isJustMounted) {
        // these things should be done as soon as possible
        if (isPrimary) {
            FileUtils.INSTANCE.setExternalStorageDir(new File(vol.path, String.valueOf(0)));
        }
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer != null) {
            final var mountUserId = StorageEventListenerDelegate.getMountUserId(vol);
            observer.getMountedStorage().add(mountUserId);
            if (isJustMounted) {
                if (isPrimary) {
                    observer.remountAll();
                } else {
                    observer.remountAllWithCheck();
                }
            } else if (isPrimary) {
                observer.recordAll();
            }
        }
        // leisurely do remaining things
        if (isPrimary) {
            if (observer != null && observer.isFuseBpfEnabled()) {
                new Thread(() -> {
                    for (final var userId : SystemService.getUserIdsNoThrow()) {
                        for (final var packageName : ServicePreferences.INSTANCE.getSrPackages()) {
                            final var ai = SystemService.getApplicationInfoNoThrow(packageName, 0, userId);
                            if (ai != null) {
                                FileUtils.INSTANCE.switch_owner(
                                        FileUtils.INSTANCE.getPathAsUser(
                                                FileUtils.INSTANCE.buildExternalStorageAppDataDirs(ai.packageName).getPath(),
                                                userId
                                        ),
                                        ai.uid,
                                        true
                                );
                            }
                        }
                    }
                }).start();
            }
        }
    }

    public void onStorageUnmounted(final VolumeInfo vol) {
        final var observer = ObserverManager.INSTANCE.getObserver(BaseProcessObserver.class);
        if (observer != null) {
            final var mountUserId = StorageEventListenerDelegate.getMountUserId(vol);
            observer.getMountedStorage().remove(mountUserId);
        }
    }

    private void broadcastIntentDelayed(final Consumer<Intent> callback, long delayMillis) {
        final var extra = new Bundle(1);
        extra.putBinder(ServerConstants.EXTRA_BINDER, cleanerService);
        @SuppressLint("WrongConstant") final var intent = new Intent()
                .setClassName(this, ServerConstants.RECEIVER_ACTIVITY_NAME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .setPackage(ServerConstants.APPLICATION_ID)
                .putExtra(Intent.EXTRA_RESTRICTIONS_BUNDLE, extra);
        callback.accept(intent);
        Objects.requireNonNull(intent.getAction());
        handler.postDelayed(() -> SystemService.startActivityNoThrow(intent, null, 0), delayMillis);
    }

    public void broadcastIntentDelayed(final Consumer<Intent> callback) {
        broadcastIntentDelayed(callback, 2000);
    }

    public void broadcastIntent(final Consumer<Intent> callback) {
        broadcastIntentDelayed(callback, 0);
    }

    /**
     * 控制面方法：显示存储重定向提示。
     *
     * 由 [RedirectNoticeConsumer] 在消费 DataBus 积压事件时调用。
     * 使用 Java 实现避免 Kotlin stub Intent 遮蔽问题（hidden-api 模块）。
     */
    public void showRedirectNotice(String packageName, String originalPath,
                                   String mountedPath, String reason) {
        final var finalPath = originalPath;
        broadcastIntent(intent -> {
            intent.setAction(ServerConstants.ACTION_REDIRECTED_TO_INTERNAL);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                    SystemService.getPackageInfoNoThrow(packageName, 0, 0));
            intent.putExtra(Intent.EXTRA_TEXT, mountedPath);
            intent.setType(String.valueOf(reason));
            intent.putExtra(Intent.EXTRA_STREAM,
                    FileUtils.INSTANCE.getPathAsUser(finalPath, 0));
        });
    }

    public void onDestroy() {
        MediaProviderHookGateway.onDestroy();
        ObserverManager.INSTANCE.stopAllObservers();
        mCleanerServerCallback.releaseAll();
    }
}
