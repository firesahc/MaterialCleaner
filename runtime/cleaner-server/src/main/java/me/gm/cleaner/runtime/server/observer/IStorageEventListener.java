package me.gm.cleaner.runtime.server.observer;

import android.os.storage.VolumeInfo;

public interface IStorageEventListener {

    void onStorageMounted(final VolumeInfo vol, final boolean isPrimary,
                          final boolean isJustMounted);

    void onStorageUnmounted(final VolumeInfo vol);
}
