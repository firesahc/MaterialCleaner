package me.gm.cleaner.server;

import me.gm.cleaner.server.IMediaProviderHooksService;

interface ICleanerServerCallback {
    String readDataBusSnapshot(String name) = 30;

    long getDataBusSignalTimestamp(String name) = 31;

    long writeDataBusEvent(String queue, String content) = 32;

    boolean writeDataBusLease(String category, String key, String content) = 33;

    boolean writeDataBusSnapshot(String name, String content) = 34;

    boolean signalDataBus(String name) = 35;
}
