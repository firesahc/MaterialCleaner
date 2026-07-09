package me.gm.cleaner.runtime.server;

import static hidden.HiddenApiBridge.Context_createPackageContextAsUser;
import static hidden.HiddenApiBridge.createUserHandle;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.util.List;

import api.SystemService;
import me.gm.cleaner.core.common.RuntimeLibUtils;
import me.gm.cleaner.core.config.SecurityHelper;
import me.gm.cleaner.runtime.server.BuildConfig;
import me.gm.cleaner.runtime.server.hookbridge.MediaProviderHookGateway;
import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.observer.ObserverManager;

public class CleanerServer extends ContextWrapper {
    public final Handler handler = new Handler(Looper.getMainLooper());
    public volatile CleanerService cleanerService;
    public final PackageInfo packageInfo;
    // System service lifecycle
    final PackageReceiver mPackageReceiver;
    final AutoLogging mAutoLogging;
    public final CleanerServerCallback mCleanerServerCallback;
    public final LayerOrchestrator layerOrchestrator;
    public final VfsLayerController vfsLayerController;
    public final NoticeDispatcher noticeDispatcher;

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
        RuntimeLibUtils.loadLibrary(RuntimeLibUtils.getLibSourceDir(packageInfo.applicationInfo), "cleaner");
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
        mCleanerServerCallback = new CleanerServerCallback();
        cleanerService = new CleanerService(this, packageInfo.applicationInfo.uid);
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "Cleaner server v" + BuildConfig.VERSION_CODE + " started");
        vfsLayerController = new VfsLayerController();
        noticeDispatcher = new NoticeDispatcher(this);
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
        vfsLayerController.onStorageMounted(vol, isPrimary, isJustMounted);
    }

    public void onStorageUnmounted(final VolumeInfo vol) {
        vfsLayerController.onStorageUnmounted(vol);
    }

    public void onDestroy() {
        try {
            SnapshotPublisher.INSTANCE.publishStopped();
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "publish stopped snapshots failed", t);
        }
        try {
            MediaProviderHookGateway.refreshPolicyFromDataBus();
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "refresh hook stopped policy failed", t);
        }
        try {
            vfsLayerController.shutdown();
        } catch (final Throwable t) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "shutdown VFS layer failed", t);
        }
        MediaProviderHookGateway.onDestroy();
        ObserverManager.INSTANCE.stopAllObservers();
    }
}
