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
        return DataBus.INSTANCE.ensureInitialized() &&
                DataBus.INSTANCE.writeSnapshot(name, content);
    }

    @Override
    public boolean signalDataBus(String name) {
        return DataBus.INSTANCE.signal(name);
    }
}
