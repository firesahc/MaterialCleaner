package me.gm.cleaner.server.observer;

import androidx.annotation.CallSuper;

import api.SystemService;

public class StorageMountObserver extends BaseObserver {
    private final StorageEventListenerDelegate mListener = new StorageEventListenerDelegate();

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
}
