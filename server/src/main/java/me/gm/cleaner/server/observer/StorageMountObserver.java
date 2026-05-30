package me.gm.cleaner.server.observer;

import android.os.storage.VolumeInfo;

import androidx.annotation.CallSuper;

import api.SystemService;
import me.gm.cleaner.server.CleanerServer;

public class StorageMountObserver extends BaseObserver implements IStorageEventListener {
    private final StorageEventListenerDelegate mListener = new StorageEventListenerDelegate();
    private CleanerServer mCleanerServer;

    public void setCleanerServer(final CleanerServer server) {
        mCleanerServer = server;
    }

    public void registerListener(final IStorageEventListener listener) {
        mListener.registerListener(listener);
    }

    public void unregisterListener(final IStorageEventListener listener) {
        mListener.unregisterListener(listener);
    }

    @CallSuper
    @Override
    protected void onStart() {
        super.onStart();
        SystemService.registerStorageEventListener(mListener);
    }

    @CallSuper
    @Override
    protected void onDestroy() {
        super.onDestroy();
        SystemService.unregisterStorageEventListener(mListener);
    }

    @Override
    public void onStorageMounted(final VolumeInfo vol, final boolean isPrimary,
                                          final boolean isJustMounted) {
        if (mCleanerServer != null) {
            mCleanerServer.onStorageMounted(vol, isPrimary, isJustMounted);
        }
    }

    @Override
    public void onStorageUnmounted(final VolumeInfo vol) {
        if (mCleanerServer != null) {
            mCleanerServer.onStorageUnmounted(vol);
        }
    }
}
