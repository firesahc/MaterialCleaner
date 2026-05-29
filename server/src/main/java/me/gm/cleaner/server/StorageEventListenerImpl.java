package me.gm.cleaner.server;

import android.os.storage.VolumeInfo;

import me.gm.cleaner.server.observer.IStorageEventListener;
import me.gm.cleaner.server.observer.ObserverManager;
import me.gm.cleaner.server.observer.StorageMountObserver;

public class StorageEventListenerImpl implements IStorageEventListener {
    private final CleanerServer mServer;

    public StorageEventListenerImpl(final CleanerServer server) {
        mServer = server;
    }

    public void start() {
        final var observer = ObserverManager.INSTANCE.getObserver(StorageMountObserver.class);
        if (observer != null) {
            observer.registerListener(this);
        }
    }

    @Override
    public void onStorageMounted(final VolumeInfo vol, final boolean isPrimary,
                                          final boolean isJustMounted) {
        mServer.onStorageMounted(vol, isPrimary, isJustMounted);
    }

    @Override
    public void onStorageUnmounted(final VolumeInfo vol) {
        mServer.onStorageUnmounted(vol);
    }
}
