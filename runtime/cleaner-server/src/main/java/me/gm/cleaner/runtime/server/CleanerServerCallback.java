package me.gm.cleaner.runtime.server;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import me.gm.cleaner.server.ICleanerServerCallback;

/**
 * Server-side DataBus proxy exposed to the MediaProvider Hook process.
 *
 * The Hook layer reads policies and writes events through DataBus. The callback
 * only provides a privileged server-side proxy when direct filesystem access is
 * unavailable; it must not become a policy or UI side-effect endpoint again.
 */
public class CleanerServerCallback extends ICleanerServerCallback.Stub {
    private static boolean isHookWritableSnapshot(String name) {
        return DataBus.SNAPSHOT_NATIVE_HOOK_STATUS.equals(name);
    }

    private static boolean isHookWritableSignal(String name) {
        return DataBus.SIGNAL_FILESYSTEM_EVENTS_CHANGED.equals(name) ||
                DataBus.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED.equals(name) ||
                DataBus.SIGNAL_QUERY_SESSION_LEASES_CHANGED.equals(name) ||
                DataBus.SIGNAL_NATIVE_HOOK_STATUS_CHANGED.equals(name);
    }

    @Override
    public String readDataBusSnapshot(String name) {
        final var snapshot = DataBus.INSTANCE.readSnapshot(name);
        return snapshot == null ? "" : snapshot;
    }

    @Override
    public long getDataBusSignalTimestamp(String name) {
        return DataBus.INSTANCE.getSignalTimestamp(name);
    }

    @Override
    public long writeDataBusEvent(String queue, String content) {
        return DataBus.INSTANCE.writeEvent(queue, content);
    }

    @Override
    public boolean writeDataBusLease(String category, String key, String content) {
        return DataBus.INSTANCE.writeLease(category, key, content);
    }

    @Override
    public boolean writeDataBusSnapshot(String name, String content) {
        if (!isHookWritableSnapshot(name)) {
            return false;
        }
        return DataBus.INSTANCE.ensureInitialized() &&
                DataBus.INSTANCE.writeSnapshot(name, content);
    }

    @Override
    public boolean signalDataBus(String name) {
        if (!isHookWritableSignal(name)) {
            return false;
        }
        return DataBus.INSTANCE.signal(name);
    }
}
